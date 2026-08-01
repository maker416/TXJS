/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import io.github.rwpp.AppContext
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModCacheEntry
import io.github.rwpp.game.mod.NetworkModCacheFiles
import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.modDir
import org.koin.core.annotation.Single
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Single([NetworkModCache::class])
class DesktopNetworkModCache(
    private val appContext: AppContext,
) : NetworkModCache {
    private val lock = Any()
    /**
     * 加密缓存必须可写：一体包装在 Program Files 时 `user.dir/.rwpp` 常因权限导致
     * atomicWrite/rename 失败。迁到 %LOCALAPPDATA%\Minxyzgo\RWJS\network-mod-cache\。
     */
    private val encryptedRoot: File get() = resolveEncryptedCacheRoot()
    /**
     * 引擎可见明文目录：Desktop 在 `l.aU=true` 时扫描 `mods/units/`（非裸 `units/`）。
     * 与 [modDir] / [customMapDir] 的桌面路径约定一致。
     */
    private val workingRoot: File get() = File(modDir)
    private val entries = mutableMapOf<String, DesktopEntry>()
    private val random = SecureRandom()
    private var prepared = false

    override fun prepareStartup() = synchronized(lock) {
        if (prepared) return@synchronized
        val now = System.currentTimeMillis()
        val cacheRoot = encryptedRoot.apply { mkdirs() }
        val workRoot = workingRoot.apply { mkdirs() }
        entries.clear()
        cleanupWorkingCopies(workRoot)
        cacheRoot.listFiles().orEmpty().forEach { file ->
            if (file.name.endsWith(NetworkModCacheFiles.TEMP_SUFFIX) || file.name.contains(NetworkModCacheFiles.QUARANTINE_MARKER)) {
                NetworkModCacheFiles.deleteOrQuarantine(cacheRoot, file)
            }
        }
        val key = loadOrCreateKey(cacheRoot)
        NetworkModCacheFiles.cleanupExpiredPartialDirs(cacheRoot, now)
        cacheRoot.listFiles { file -> file.extension == CACHE_EXTENSION }.orEmpty().forEach { cacheFile ->
            val entry = runCatching { readEnvelope(cacheFile, key) }.getOrNull()
            if (entry == null || NetworkModCacheFiles.isExpired(entry.createdAtMillis, now)) {
                NetworkModCacheFiles.deleteOrQuarantine(cacheRoot, cacheFile)
                return@forEach
            }
            entries[entry.descriptor.cacheKey()] = entry
        }
        entries.values.groupBy { it.descriptor.name }.forEach { (_, group) ->
            val latest = group.maxByOrNull { it.createdAtMillis } ?: return@forEach
            runCatching { activate(latest.descriptor) }
        }
        prepared = true
    }

    override fun find(descriptor: NetworkModDescriptor): NetworkModCacheEntry? = synchronized(lock) {
        prepareStartup()
        val entry = entries[descriptor.cacheKey()] ?: return@synchronized null
        if (NetworkModCacheFiles.isExpired(entry.createdAtMillis, System.currentTimeMillis())) return@synchronized null
        NetworkModCacheEntry(descriptor, entry.cacheFile, entry.cacheFile, entry.createdAtMillis)
    }

    override fun storeVerified(descriptor: NetworkModDescriptor, bytes: ByteArray): NetworkModCacheEntry = synchronized(lock) {
        require(descriptor.matches(bytes)) { "Network mod bytes do not match descriptor: ${descriptor.name}" }
        prepareStartup()
        val cacheRoot = encryptedRoot.apply { mkdirs() }
        val key = loadOrCreateKey(cacheRoot)
        val createdAt = System.currentTimeMillis()
        val cacheFile = File(cacheRoot, "${descriptor.cacheKey()}.$CACHE_EXTENSION")
        val envelope = writeEnvelope(descriptor, bytes, createdAt, key)
        NetworkModCacheFiles.atomicWrite(cacheFile, envelope, cacheRoot)
        val entry = DesktopEntry(descriptor, cacheFile, createdAt)
        entries[descriptor.cacheKey()] = entry
        entry.asCacheEntry()
    }

    override fun activate(descriptor: NetworkModDescriptor): NetworkModCacheEntry = synchronized(lock) {
        prepareStartup()
        val entry = entries[descriptor.cacheKey()] ?: throw IllegalStateException("Network mod cache miss: ${descriptor.name}")
        val workRoot = workingRoot.apply { mkdirs() }
        demoteSameName(workRoot, descriptor)
        val active = NetworkModCacheFiles.activeFile(workRoot, descriptor)
        if (!active.exists()) {
            // 目标文件名由内容哈希派生：已存在即代表内容一定正确，直接复用。
            // 重复加入同一房间时该文件通常仍被引擎持有句柄（当前对局正在使用），
            // 无条件重新解密写入在 Windows 上会因文件被占用直接失败
            // （java.io.IOException: Failed to replace existing file）。
            val key = loadOrCreateKey(encryptedRoot.apply { mkdirs() })
            val bytes = readEnvelopeBytes(entry.cacheFile, key, descriptor)
            NetworkModCacheFiles.atomicWrite(active, bytes, workRoot)
        }
        entry.asCacheEntry(active)
    }

    override fun descriptorForManagedPath(path: String): NetworkModDescriptor? = synchronized(lock) {
        prepareStartup()
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return@synchronized null
        entries.values.firstOrNull { NetworkModCacheFiles.activeFile(workingRoot, it.descriptor).canonicalFile == file }?.descriptor
    }

    override fun cleanupWorkingCopiesOnExit() = synchronized(lock) {
        cleanupWorkingCopies(workingRoot.apply { mkdirs() })
    }

    override fun storePartialChunk(descriptor: NetworkModDescriptor, chunkIndex: Int, bytes: ByteArray): Unit = synchronized(lock) {
        prepareStartup()
        val cacheRoot = encryptedRoot.apply { mkdirs() }
        val key = loadOrCreateKey(cacheRoot)
        val dir = NetworkModCacheFiles.partialDir(cacheRoot, descriptor).apply { mkdirs() }
        NetworkModCacheFiles.ensurePartialMeta(dir, System.currentTimeMillis())
        NetworkModCacheFiles.atomicWrite(
            NetworkModCacheFiles.partialChunkFile(dir, chunkIndex),
            encryptChunk(descriptor, chunkIndex, bytes, key),
            cacheRoot,
        )
    }

    override fun partialChunks(descriptor: NetworkModDescriptor): Map<Int, ByteArray> = synchronized(lock) {
        prepareStartup()
        val cacheRoot = encryptedRoot
        val dir = NetworkModCacheFiles.partialDir(cacheRoot, descriptor)
        if (!dir.isDirectory) return@synchronized emptyMap()
        val createdAt = NetworkModCacheFiles.readPartialCreatedAt(dir)
        if (createdAt == null || NetworkModCacheFiles.isExpired(createdAt, System.currentTimeMillis())) {
            NetworkModCacheFiles.deleteOrQuarantine(cacheRoot, dir)
            return@synchronized emptyMap()
        }
        val key = loadOrCreateKey(cacheRoot)
        dir.listFiles().orEmpty().mapNotNull { file ->
            if (!file.isFile || !file.name.startsWith(NetworkModCacheFiles.PARTIAL_CHUNK_PREFIX)) return@mapNotNull null
            val index = file.name.removePrefix(NetworkModCacheFiles.PARTIAL_CHUNK_PREFIX).toIntOrNull()
                ?: return@mapNotNull null
            runCatching { index to decryptChunk(descriptor, index, file.readBytes(), key) }.getOrNull()
        }.toMap()
    }

    override fun discardPartial(descriptor: NetworkModDescriptor): Unit = synchronized(lock) {
        val dir = NetworkModCacheFiles.partialDir(encryptedRoot, descriptor)
        if (dir.exists()) NetworkModCacheFiles.deleteOrQuarantine(encryptedRoot, dir)
    }

    /**
     * 分块级 AES-GCM：每块独立随机 nonce，AAD 绑定 cacheKey+块序号，
     * 防止 partial 文件被跨 mod/跨块调换。与整包加密共用 cache.key。
     */
    private fun encryptChunk(descriptor: NetworkModDescriptor, chunkIndex: Int, bytes: ByteArray, key: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(chunkAad(descriptor, chunkIndex))
        return nonce + cipher.doFinal(bytes)
    }

    private fun decryptChunk(descriptor: NetworkModDescriptor, chunkIndex: Int, envelope: ByteArray, key: ByteArray): ByteArray {
        require(envelope.size > NONCE_SIZE_BYTES) { "partial chunk too small" }
        val nonce = envelope.copyOfRange(0, NONCE_SIZE_BYTES)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(chunkAad(descriptor, chunkIndex))
        return cipher.doFinal(envelope.copyOfRange(NONCE_SIZE_BYTES, envelope.size))
    }

    private fun chunkAad(descriptor: NetworkModDescriptor, chunkIndex: Int): ByteArray =
        "${descriptor.cacheKey()}:$chunkIndex".toByteArray(StandardCharsets.UTF_8)

    private fun cleanupWorkingCopies(root: File) {
        root.listFiles().orEmpty()
            .filter { it.name.endsWith(NetworkModCacheFiles.ACTIVE_SUFFIX) && it.name.contains("-") }
            .forEach { NetworkModCacheFiles.deleteOrQuarantine(root, it) }
        root.listFiles().orEmpty()
            .filter { it.name.endsWith(NetworkModCacheFiles.TEMP_SUFFIX) || it.name.contains(NetworkModCacheFiles.QUARANTINE_MARKER) }
            .forEach { NetworkModCacheFiles.deleteOrQuarantine(root, it) }
    }

    private fun demoteSameName(root: File, descriptor: NetworkModDescriptor) {
        entries.values.filter { it.descriptor.name == descriptor.name && it.descriptor != descriptor }
            .map { NetworkModCacheFiles.activeFile(root, it.descriptor) }
            .forEach { if (it.exists()) NetworkModCacheFiles.deleteOrQuarantine(root, it) }
    }

    private fun loadOrCreateKey(root: File): ByteArray {
        val keyFile = File(root, KEY_FILE)
        if (keyFile.isFile && keyFile.length() == KEY_SIZE_BYTES.toLong()) return keyFile.readBytes()
        if (keyFile.exists()) NetworkModCacheFiles.deleteOrQuarantine(root, keyFile)
        val key = ByteArray(KEY_SIZE_BYTES).also { random.nextBytes(it) }
        NetworkModCacheFiles.atomicWrite(keyFile, key, root)
        return key
    }

    private fun writeEnvelope(descriptor: NetworkModDescriptor, bytes: ByteArray, createdAtMillis: Long, key: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE_BYTES).also { random.nextBytes(it) }
        val header = encodeHeader(descriptor, createdAtMillis, nonce)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        val encrypted = cipher.doFinal(bytes)
        return header + encrypted
    }

    private fun readEnvelope(file: File, key: ByteArray): DesktopEntry {
        val bytes = file.readBytes()
        val parsed = parseHeader(bytes)
        val plain = decrypt(bytes, parsed, key)
        require(parsed.descriptor.matches(plain)) { "Desktop cache hash mismatch: ${file.name}" }
        return DesktopEntry(parsed.descriptor, file, parsed.createdAtMillis)
    }

    private fun readEnvelopeBytes(file: File, key: ByteArray, descriptor: NetworkModDescriptor): ByteArray {
        val bytes = file.readBytes()
        val parsed = parseHeader(bytes)
        require(parsed.descriptor == descriptor) { "Desktop cache descriptor mismatch: ${file.name}" }
        val plain = decrypt(bytes, parsed, key)
        require(descriptor.matches(plain)) { "Desktop cache hash mismatch: ${file.name}" }
        return plain
    }

    private fun decrypt(envelope: ByteArray, header: Header, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, header.nonce))
        cipher.updateAAD(envelope.copyOfRange(0, header.headerLength))
        return cipher.doFinal(envelope.copyOfRange(header.headerLength, envelope.size))
    }

    private fun encodeHeader(descriptor: NetworkModDescriptor, createdAtMillis: Long, nonce: ByteArray): ByteArray {
        val nameBytes = descriptor.name.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(MAGIC)
            data.writeByte(FORMAT_VERSION)
            data.writeByte(nonce.size)
            data.write(nonce)
            data.writeLong(createdAtMillis)
            data.writeInt(nameBytes.size)
            data.write(nameBytes)
            data.writeLong(descriptor.payloadSize)
            data.writeUTF(descriptor.normalizedSha256)
        }
        return out.toByteArray()
    }

    private fun parseHeader(envelope: ByteArray): Header {
        DataInputStream(envelope.inputStream()).use { input ->
            require(input.readInt() == MAGIC) { "Invalid cache magic" }
            require(input.readUnsignedByte() == FORMAT_VERSION) { "Invalid cache version" }
            val nonceLength = input.readUnsignedByte()
            require(nonceLength == NONCE_SIZE_BYTES) { "Invalid nonce length" }
            val nonce = ByteArray(nonceLength).also { input.readFully(it) }
            val createdAt = input.readLong()
            val nameLength = input.readInt().also { require(it in 1..NetworkModDescriptor.MAX_NAME_LENGTH * 4) { "Invalid name length" } }
            val name = String(ByteArray(nameLength).also { input.readFully(it) }, StandardCharsets.UTF_8)
            val size = input.readLong()
            val sha256 = input.readUTF()
            val descriptor = NetworkModDescriptor(name, size, sha256)
            val headerLength = envelope.size - input.available()
            require(headerLength < envelope.size) { "Missing ciphertext" }
            return Header(descriptor, createdAt, nonce, headerLength)
        }
    }

    private data class Header(
        val descriptor: NetworkModDescriptor,
        val createdAtMillis: Long,
        val nonce: ByteArray,
        val headerLength: Int,
    )

    private data class DesktopEntry(
        val descriptor: NetworkModDescriptor,
        val cacheFile: File,
        val createdAtMillis: Long,
    ) {
        fun asCacheEntry(payloadFile: File = cacheFile): NetworkModCacheEntry =
            NetworkModCacheEntry(descriptor, payloadFile, cacheFile, createdAtMillis)
    }

    companion object {
        private const val CACHE_EXTENSION = "rwcache"
        private const val KEY_FILE = "cache.key"
        private const val KEY_SIZE_BYTES = 32
        private const val NONCE_SIZE_BYTES = 12
        private const val TAG_BITS = 128
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val MAGIC = 0x52574d43 // RWMC
        private const val FORMAT_VERSION = 1
        private const val CACHE_VENDOR = "Minxyzgo"
        private const val CACHE_PRODUCT = "RWJS"
        private const val CACHE_DIR_NAME = "network-mod-cache"

        internal fun resolveEncryptedCacheRoot(): File {
            val localAppData = System.getenv("LOCALAPPDATA")
                ?.takeIf { it.isNotBlank() }
                ?: File(System.getProperty("user.home"), "AppData/Local").absolutePath
            return File(localAppData, "$CACHE_VENDOR/$CACHE_PRODUCT/$CACHE_DIR_NAME")
        }
    }
}
