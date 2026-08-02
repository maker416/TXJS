/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import io.github.rwpp.AppContext
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModCacheEntry
import io.github.rwpp.game.mod.NetworkModCacheFiles
import io.github.rwpp.game.mod.NetworkModDescriptor
import org.koin.core.annotation.Single
import java.io.File

@Single([NetworkModCache::class])
class AndroidNetworkModCache(
    private val appContext: AppContext,
) : NetworkModCache {
    private val lock = Any()
    private val root: File get() = File(appContext.internalStoragePath("units/"))
    /**
     * 断点续传 partial 根目录。必须位于引擎模组扫描目录（units/）**之外**，
     * 否则 `partial-*` 文件夹会被引擎当作文件夹型模组扫描。
     */
    private val partialRoot: File get() = File(appContext.internalStoragePath("network-mod-partial/"))
    private val entries = mutableMapOf<String, NetworkModCacheEntry>()
    private var prepared = false

    override fun prepareStartup() = synchronized(lock) {
        if (prepared) return@synchronized
        val now = System.currentTimeMillis()
        val cacheRoot = root.apply { mkdirs() }
        entries.clear()
        NetworkModCacheFiles.cleanupExpiredPartialDirs(partialRoot.apply { mkdirs() }, now)

        cacheRoot.listFiles().orEmpty().forEach { file ->
            when {
                file.name.endsWith(NetworkModCacheFiles.TEMP_SUFFIX) -> NetworkModCacheFiles.deleteOrQuarantine(cacheRoot, file)
                file.name.contains(NetworkModCacheFiles.QUARANTINE_MARKER) -> NetworkModCacheFiles.deleteOrQuarantine(cacheRoot, file)
                file.name.endsWith(NetworkModCacheFiles.ACTIVE_SUFFIX) && !hasMetadataForPayload(cacheRoot, file.name) -> {
                    // Legacy or orphan network mod: it has no descriptor/createdAt, so do not let engine scan it.
                    NetworkModCacheFiles.deleteOrQuarantine(cacheRoot, file)
                }
            }
        }

        cacheRoot.listFiles { file -> file.extension == NetworkModCacheFiles.META_EXTENSION }.orEmpty().forEach { meta ->
            val parsed = NetworkModCacheFiles.readMetadata(meta)
            if (parsed == null || NetworkModCacheFiles.isExpired(parsed.createdAtMillis, now)) {
                parsed?.let { NetworkModCacheFiles.deleteOrQuarantine(cacheRoot, File(cacheRoot, it.payloadName)) }
                NetworkModCacheFiles.deleteOrQuarantine(cacheRoot, meta)
                return@forEach
            }
            val payload = File(cacheRoot, parsed.payloadName)
            if (!payload.exists() || !NetworkModCacheFiles.validatePayload(payload, parsed.descriptor)) {
                NetworkModCacheFiles.deleteOrQuarantine(cacheRoot, payload)
                NetworkModCacheFiles.deleteOrQuarantine(cacheRoot, meta)
                return@forEach
            }
            val entry = NetworkModCacheEntry(parsed.descriptor, payload, meta, parsed.createdAtMillis)
            entries[parsed.descriptor.cacheKey()] = entry
        }

        entries.values.groupBy { it.descriptor.name }.forEach { (_, group) ->
            val active = group.maxByOrNull { it.createdAtMillis } ?: return@forEach
            runCatching { activate(active.descriptor) }
        }
        prepared = true
    }

    override fun find(descriptor: NetworkModDescriptor): NetworkModCacheEntry? = synchronized(lock) {
        prepareStartup()
        val entry = entries[descriptor.cacheKey()] ?: return@synchronized null
        if (NetworkModCacheFiles.isExpired(entry.createdAtMillis, System.currentTimeMillis())) return@synchronized null
        if (!NetworkModCacheFiles.validatePayload(entry.payloadFile, descriptor)) return@synchronized null
        entry
    }

    override fun storeVerified(descriptor: NetworkModDescriptor, bytes: ByteArray): NetworkModCacheEntry = synchronized(lock) {
        require(descriptor.matches(bytes)) { "Network mod bytes do not match descriptor: ${descriptor.name}" }
        prepareStartup()
        val cacheRoot = root.apply { mkdirs() }
        val inactive = NetworkModCacheFiles.inactiveFile(cacheRoot, descriptor)
        val meta = NetworkModCacheFiles.metadataFile(cacheRoot, descriptor)
        NetworkModCacheFiles.atomicWrite(inactive, bytes, cacheRoot)
        val createdAt = System.currentTimeMillis()
        NetworkModCacheFiles.writeMetadata(meta, descriptor, createdAt, inactive.name, cacheRoot)
        val entry = NetworkModCacheEntry(descriptor, inactive, meta, createdAt)
        entries[descriptor.cacheKey()] = entry
        entry
    }

    override fun activate(descriptor: NetworkModDescriptor): NetworkModCacheEntry = synchronized(lock) {
        val cacheRoot = root.apply { mkdirs() }
        val entry = entries[descriptor.cacheKey()] ?: find(descriptor)
            ?: throw IllegalStateException("Network mod cache miss: ${descriptor.name}")
        demoteSameName(cacheRoot, descriptor)
        val active = NetworkModCacheFiles.activeFile(cacheRoot, descriptor)
        if (entry.payloadFile.canonicalFile != active.canonicalFile) {
            if (active.exists() && !NetworkModCacheFiles.deleteOrQuarantine(cacheRoot, active)) {
                throw IllegalStateException("Failed to clear active network mod: $active")
            }
            if (!entry.payloadFile.renameTo(active)) {
                throw IllegalStateException("Failed to activate network mod: ${entry.payloadFile} -> $active")
            }
        }
        NetworkModCacheFiles.writeMetadata(entry.metadataFile ?: NetworkModCacheFiles.metadataFile(cacheRoot, descriptor), descriptor, entry.createdAtMillis, active.name, cacheRoot)
        val activeEntry = entry.copy(payloadFile = active)
        entries[descriptor.cacheKey()] = activeEntry
        activeEntry
    }

    override fun descriptorForManagedPath(path: String): NetworkModDescriptor? = synchronized(lock) {
        prepareStartup()
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return@synchronized null
        entries.values.firstOrNull { it.payloadFile.canonicalFile == file }?.descriptor
    }

    override fun storePartialChunk(descriptor: NetworkModDescriptor, chunkIndex: Int, bytes: ByteArray): Unit = synchronized(lock) {
        prepareStartup()
        val partialBase = partialRoot.apply { mkdirs() }
        val dir = NetworkModCacheFiles.partialDir(partialBase, descriptor).apply { mkdirs() }
        NetworkModCacheFiles.ensurePartialMeta(dir, System.currentTimeMillis())
        // 非原子直写：partial 块读侧有块级 SHA-256 兜底（校验失败即丢弃重下），
        // 半写文件天然容错，无需 temp+rename——这是接收热路径，原子写每块多 2 次文件操作。
        NetworkModCacheFiles.partialChunkFile(dir, chunkIndex).writeBytes(bytes)
    }

    override fun partialChunks(descriptor: NetworkModDescriptor): Map<Int, ByteArray> = synchronized(lock) {
        prepareStartup()
        val partialBase = partialRoot
        val dir = NetworkModCacheFiles.partialDir(partialBase, descriptor)
        if (!dir.isDirectory) return@synchronized emptyMap()
        val createdAt = NetworkModCacheFiles.readPartialCreatedAt(dir)
        if (createdAt == null || NetworkModCacheFiles.isExpired(createdAt, System.currentTimeMillis())) {
            NetworkModCacheFiles.deleteOrQuarantine(partialBase, dir)
            return@synchronized emptyMap()
        }
        dir.listFiles().orEmpty().mapNotNull { file ->
            if (!file.isFile || !file.name.startsWith(NetworkModCacheFiles.PARTIAL_CHUNK_PREFIX)) return@mapNotNull null
            val index = file.name.removePrefix(NetworkModCacheFiles.PARTIAL_CHUNK_PREFIX).toIntOrNull()
                ?: return@mapNotNull null
            runCatching { index to file.readBytes() }.getOrNull()
        }.toMap()
    }

    override fun discardPartial(descriptor: NetworkModDescriptor): Unit = synchronized(lock) {
        val dir = NetworkModCacheFiles.partialDir(partialRoot, descriptor)
        if (dir.exists()) NetworkModCacheFiles.deleteOrQuarantine(partialRoot, dir)
    }

    private fun demoteSameName(cacheRoot: File, descriptor: NetworkModDescriptor) {
        entries.values.filter { it.descriptor.name == descriptor.name && it.descriptor != descriptor }
            .forEach { entry ->
                if (entry.payloadFile.name.endsWith(NetworkModCacheFiles.ACTIVE_SUFFIX)) {
                    val inactive = NetworkModCacheFiles.inactiveFile(cacheRoot, entry.descriptor)
                    if (inactive.exists()) NetworkModCacheFiles.deleteOrQuarantine(cacheRoot, inactive)
                    if (!entry.payloadFile.renameTo(inactive)) {
                        throw IllegalStateException("Failed to demote network mod: ${entry.payloadFile}")
                    }
                    NetworkModCacheFiles.writeMetadata(entry.metadataFile ?: NetworkModCacheFiles.metadataFile(cacheRoot, entry.descriptor), entry.descriptor, entry.createdAtMillis, inactive.name, cacheRoot)
                    entries[entry.descriptor.cacheKey()] = entry.copy(payloadFile = inactive)
                }
            }
    }

    private fun hasMetadataForPayload(cacheRoot: File, payloadName: String): Boolean =
        cacheRoot.listFiles { file -> file.extension == NetworkModCacheFiles.META_EXTENSION }.orEmpty()
            .any { NetworkModCacheFiles.readMetadata(it)?.payloadName == payloadName }
}
