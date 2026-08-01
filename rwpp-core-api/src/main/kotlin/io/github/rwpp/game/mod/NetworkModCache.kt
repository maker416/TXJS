/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import io.github.rwpp.io.HashUtils
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

const val NETWORK_MOD_CACHE_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L
const val NETWORK_MOD_CACHE_FUTURE_SKEW_MILLIS: Long = 5L * 60L * 1000L

/**
 * Content identity for a network-transferred mod.
 *
 * [payloadSize] and [sha256] describe the exact bytes transferred over the network. For folder mods
 * this means the zipped payload returned by [Mod.getBytes], not the folder's recursive file size.
 */
data class NetworkModDescriptor(
    val name: String,
    val payloadSize: Long,
    val sha256: String,
) {
    init {
        require(name.isNotBlank()) { "Network mod name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) { "Network mod name is too long" }
        require(payloadSize >= 0L) { "Network mod payload size must not be negative" }
        require(sha256.lowercase(Locale.ROOT).matches(SHA256_REGEX)) { "Invalid SHA-256: $sha256" }
    }

    val normalizedSha256: String = sha256.lowercase(Locale.ROOT)

    fun cacheKey(): String = NetworkModCacheFiles.cacheKey(this)

    fun matches(bytes: ByteArray): Boolean =
        payloadSize == bytes.size.toLong() && normalizedSha256 == HashUtils.sha256(bytes)

    companion object {
        const val MAX_NAME_LENGTH = 512

        fun fromBytes(name: String, bytes: ByteArray): NetworkModDescriptor =
            NetworkModDescriptor(name, bytes.size.toLong(), HashUtils.sha256(bytes))
    }
}

data class NetworkModCacheEntry(
    val descriptor: NetworkModDescriptor,
    val payloadFile: File,
    val metadataFile: File?,
    val createdAtMillis: Long,
)

interface NetworkModCache {
    /** Runs once before the original game engine scans mods. */
    fun prepareStartup()

    /** Returns an exact, unexpired cache entry. Must not renew the TTL. */
    fun find(descriptor: NetworkModDescriptor): NetworkModCacheEntry?

    /** Stores bytes that were already verified by the transfer layer. Implementations verify again. */
    fun storeVerified(descriptor: NetworkModDescriptor, bytes: ByteArray): NetworkModCacheEntry

    /** Makes the exact descriptor visible to the original engine as a single .network.rwmod. */
    fun activate(descriptor: NetworkModDescriptor): NetworkModCacheEntry

    /** Returns the descriptor for a managed engine-visible path, if this cache owns it. */
    fun descriptorForManagedPath(path: String): NetworkModDescriptor?

    /** Best-effort cleanup of runtime plaintext copies. */
    fun cleanupWorkingCopiesOnExit() {}

    /**
     * 存储一个已通过块级 SHA-256 校验的分块（断点续传用）。
     * 与整包缓存共用 24h TTL；进程重启后仍可通过 [partialChunks] 读回。
     */
    fun storePartialChunk(descriptor: NetworkModDescriptor, chunkIndex: Int, bytes: ByteArray) {}

    /**
     * 读取某 descriptor 全部已缓存的未完成分块（块序号 → 字节）。
     * 无 partial、已过期或内容损坏的块会被忽略，返回空 map 表示「从头下载」。
     */
    fun partialChunks(descriptor: NetworkModDescriptor): Map<Int, ByteArray> = emptyMap()

    /** 传输完成（或主动放弃）后清理该 descriptor 的全部 partial 数据。 */
    fun discardPartial(descriptor: NetworkModDescriptor) {}
}

object NetworkModCacheFiles {
    const val FORMAT_VERSION = 1
    const val META_EXTENSION = "meta"
    const val INACTIVE_EXTENSION = "payload"
    const val ACTIVE_SUFFIX = ".network.rwmod"
    const val TEMP_SUFFIX = ".tmp"
    const val QUARANTINE_MARKER = ".quarantine-"

    /** 断点续传 partial 目录名前缀（`{前缀}{cacheKey}`）。 */
    const val PARTIAL_DIR_PREFIX = "partial-"
    /** partial 目录内分块文件名前缀（`{前缀}{chunkIndex}`）。 */
    const val PARTIAL_CHUNK_PREFIX = "chunk-"
    /** partial 目录内记录创建时间的元数据文件名。 */
    const val PARTIAL_META_FILE = "partial.meta"

    fun cacheKey(descriptor: NetworkModDescriptor): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val nameBytes = descriptor.name.toByteArray(StandardCharsets.UTF_8)
        val hashBytes = descriptor.normalizedSha256.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        val encoded = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(1)
                output.writeInt(nameBytes.size)
                output.write(nameBytes)
                output.writeLong(descriptor.payloadSize)
                output.write(hashBytes)
            }
            bytes.toByteArray()
        }
        return toHex(digest.digest(encoded))
    }

    fun safeNamePrefix(name: String, maxLength: Int = 48): String {
        val cleaned = buildString {
            name.forEach { ch ->
                append(
                    when {
                        ch.isLetterOrDigit() -> ch
                        ch == '-' || ch == '_' || ch == '.' -> ch
                        else -> '_'
                    }
                )
            }
        }.trim('.', '_', '-').ifBlank { "mod" }
        val reserved = setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "LPT1", "LPT2", "LPT3")
        val safe = if (cleaned.uppercase(Locale.ROOT) in reserved) "mod_$cleaned" else cleaned
        return safe.take(maxLength).ifBlank { "mod" }
    }

    fun isExpired(createdAtMillis: Long, nowMillis: Long): Boolean {
        if (createdAtMillis > nowMillis + NETWORK_MOD_CACHE_FUTURE_SKEW_MILLIS) return true
        return nowMillis - createdAtMillis >= NETWORK_MOD_CACHE_TTL_MILLIS
    }

    fun activeFile(root: File, descriptor: NetworkModDescriptor): File =
        File(root, "${safeNamePrefix(descriptor.name)}-${descriptor.cacheKey()}$ACTIVE_SUFFIX")

    fun inactiveFile(root: File, descriptor: NetworkModDescriptor): File =
        File(root, "${safeNamePrefix(descriptor.name)}-${descriptor.cacheKey()}.$INACTIVE_EXTENSION")

    fun metadataFile(root: File, descriptor: NetworkModDescriptor): File =
        File(root, "${descriptor.cacheKey()}.$META_EXTENSION")

    fun partialDir(root: File, descriptor: NetworkModDescriptor): File =
        File(root, "$PARTIAL_DIR_PREFIX${descriptor.cacheKey()}")

    fun partialChunkFile(dir: File, chunkIndex: Int): File =
        File(dir, "$PARTIAL_CHUNK_PREFIX$chunkIndex")

    /** 写入 partial 目录的创建时间（仅首次写入生效，后续追加分块不刷新 TTL）。 */
    fun ensurePartialMeta(dir: File, createdAtMillis: Long) {
        val meta = File(dir, PARTIAL_META_FILE)
        if (meta.isFile) return
        atomicWrite(meta, "createdAtMillis=$createdAtMillis".toByteArray(StandardCharsets.UTF_8), dir)
    }

    fun readPartialCreatedAt(dir: File): Long? = runCatching {
        val meta = File(dir, PARTIAL_META_FILE)
        if (!meta.isFile) return null
        meta.readText(StandardCharsets.UTF_8).lineSequence()
            .mapNotNull { line -> line.removePrefix("createdAtMillis=").toLongOrNull() }
            .firstOrNull()
    }.getOrNull()

    /** 清理 root 下所有过期的 partial 目录（启动时调用）。 */
    fun cleanupExpiredPartialDirs(root: File, nowMillis: Long) {
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(PARTIAL_DIR_PREFIX) }
            .forEach { dir ->
                val createdAt = readPartialCreatedAt(dir)
                if (createdAt == null || isExpired(createdAt, nowMillis)) {
                    deleteOrQuarantine(root, dir)
                }
            }
    }

    fun requireInside(root: File, file: File): File {
        val canonicalRoot = root.canonicalFile
        val canonicalFile = file.canonicalFile
        if (canonicalFile == canonicalRoot || !canonicalFile.isInside(canonicalRoot)) {
            throw IOException("Refuse to access path outside cache root: $canonicalFile")
        }
        return canonicalFile
    }

    fun deleteOrQuarantine(root: File, file: File): Boolean {
        if (!file.exists()) return true
        val target = requireInside(root, file)
        if (target.deleteRecursively()) return true
        val quarantine = File(target.parentFile, target.name.removeSuffix(".rwmod") + QUARANTINE_MARKER + System.currentTimeMillis())
        val safeQuarantine = requireInside(root, quarantine)
        return target.renameTo(safeQuarantine)
    }

    fun atomicWrite(file: File, bytes: ByteArray, root: File): File {
        root.mkdirs()
        val target = requireInside(root, file)
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile(target.name, TEMP_SUFFIX, target.parentFile)
        try {
            tmp.writeBytes(bytes)
            // 快速路径：目标不存在（或能被直接删除）时走普通 delete+rename。
            // 目标已存在且删除失败（Windows 上常见于文件仍被引擎持有句柄，例如正在使用中的
            // 已激活网络模组）不再直接抛异常——统一落到下面更健壮的 NIO 替换链，其中
            // Files.move(..., REPLACE_EXISTING) 在纯 delete 失败的场景下仍有机会成功。
            val fastPathOk = (!target.exists() || target.delete()) && tmp.renameTo(target)
            if (!fastPathOk) {
                publishTempFile(tmp, target)
            }
            return target
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun publishTempFile(tmp: File, target: File) {
        val tmpPath = tmp.toPath()
        val targetPath = target.toPath()
        try {
            Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (_: Exception) {
            // ATOMIC_MOVE 在部分文件系统不受支持，继续回退
        }
        try {
            Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (_: Exception) {
            // 继续复制覆盖
        }
        try {
            Files.copy(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(tmpPath)
        } catch (e: Exception) {
            throw IOException("Failed to publish temp file: $target", e)
        }
    }

    fun writeMetadata(file: File, descriptor: NetworkModDescriptor, createdAtMillis: Long, payloadName: String, root: File) {
        val text = buildString {
            appendLine("version=$FORMAT_VERSION")
            appendLine("name=${encode(descriptor.name)}")
            appendLine("payloadSize=${descriptor.payloadSize}")
            appendLine("sha256=${descriptor.normalizedSha256}")
            appendLine("createdAtMillis=$createdAtMillis")
            appendLine("cacheKey=${descriptor.cacheKey()}")
            appendLine("payloadName=${encode(payloadName)}")
        }
        atomicWrite(file, text.toByteArray(StandardCharsets.UTF_8), root)
    }

    fun readMetadata(file: File): ParsedMetadata? = runCatching {
        val values = file.readLines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()
        if (values["version"]?.toIntOrNull() != FORMAT_VERSION) return@runCatching null
        val descriptor = NetworkModDescriptor(
            name = decode(values["name"] ?: return@runCatching null),
            payloadSize = values["payloadSize"]?.toLongOrNull() ?: return@runCatching null,
            sha256 = values["sha256"] ?: return@runCatching null,
        )
        if (values["cacheKey"] != descriptor.cacheKey()) return@runCatching null
        ParsedMetadata(
            descriptor = descriptor,
            createdAtMillis = values["createdAtMillis"]?.toLongOrNull() ?: return@runCatching null,
            payloadName = decode(values["payloadName"] ?: return@runCatching null),
        )
    }.getOrNull()

    fun validatePayload(file: File, descriptor: NetworkModDescriptor): Boolean =
        file.isFile && file.length() == descriptor.payloadSize &&
                HashUtils.sha256(file).equals(descriptor.normalizedSha256, ignoreCase = true)

    private fun encode(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String =
        String(java.util.Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    private fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hex = "0123456789abcdef".toCharArray()
        bytes.forEachIndexed { index, byte ->
            val v = byte.toInt() and 0xff
            chars[index * 2] = hex[v ushr 4]
            chars[index * 2 + 1] = hex[v and 0x0f]
        }
        return String(chars)
    }

    private fun File.isInside(parent: File): Boolean {
        var current: File? = this
        while (current != null) {
            if (current == parent) return true
            current = current.parentFile
        }
        return false
    }
}

data class ParsedMetadata(
    val descriptor: NetworkModDescriptor,
    val createdAtMillis: Long,
    val payloadName: String,
)
