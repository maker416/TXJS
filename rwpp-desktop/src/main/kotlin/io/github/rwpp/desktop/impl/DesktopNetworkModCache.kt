/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import io.github.rwpp.AppContext
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.ChunkedModEnvelope
import io.github.rwpp.game.mod.NetworkModCacheEntry
import io.github.rwpp.game.mod.NetworkModCacheFiles
import io.github.rwpp.game.mod.NetworkModDescriptor
import org.koin.core.annotation.Single
import org.slf4j.LoggerFactory
import java.io.File
import java.security.SecureRandom

@Single([NetworkModCache::class])
class DesktopNetworkModCache(
    private val appContext: AppContext,
) : NetworkModCache {
    private val lock = Any()
    private val encryptedRoot: File get() = File(appContext.externalStoragePath(".rwpp/network-mod-cache-v2/"))
    private val workingRoot: File get() = File(appContext.externalStoragePath("mods/units/"))
    private val entries = mutableMapOf<String, DesktopEntry>()
    private val random = SecureRandom()
    private var prepared = false
    private val cacheLogger = LoggerFactory.getLogger(DesktopNetworkModCache::class.java)

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
        cacheRoot.listFiles { file -> file.extension == CACHE_EXTENSION }.orEmpty().forEach { cacheFile ->
            val entry = try {
                readEnvelope(cacheFile, key)
            } catch (e: Exception) {
                cacheLogger.warn("Invalid network mod cache: {}", cacheFile.name, e)
                null
            }
            if (entry == null || NetworkModCacheFiles.isExpired(entry.createdAtMillis, now)) {
                NetworkModCacheFiles.deleteOrQuarantine(cacheRoot, cacheFile)
                return@forEach
            }
            entries[entry.descriptor.cacheKey()] = entry
        }
        entries.values.groupBy { it.descriptor.name }.forEach { (_, group) ->
            val latest = group.maxByOrNull { it.createdAtMillis } ?: return@forEach
            try {
                // 初始化期间不能回调公开 activate()，它会再次进入 prepareStartup()。
                activatePrepared(latest.descriptor)
            } catch (e: Exception) {
                cacheLogger.warn("Failed to restore network mod cache: {}", latest.descriptor.name, e)
            }
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
        val staging = File.createTempFile("legacy-payload-", ".tmp", cacheRoot)
        try {
            staging.writeBytes(bytes)
            NetworkModCacheFiles.writeAtomic(cacheFile, cacheRoot) { output ->
                ChunkedModEnvelope.write(staging, descriptor, createdAt, key, output)
            }
        } finally { staging.delete() }
        val entry = DesktopEntry(descriptor, cacheFile, createdAt)
        entries[descriptor.cacheKey()] = entry
        entry.asCacheEntry()
    }

    override fun storeVerified(descriptor: NetworkModDescriptor, file: File): NetworkModCacheEntry = synchronized(lock) {
        require(descriptor.matches(file)) { "Network mod file does not match descriptor" }
        prepareStartup()
        val cacheRoot = encryptedRoot.apply { mkdirs() }
        val key = loadOrCreateKey(cacheRoot)
        val createdAt = System.currentTimeMillis()
        val cacheFile = File(cacheRoot, "${descriptor.cacheKey()}.$CACHE_EXTENSION")
        NetworkModCacheFiles.writeAtomic(cacheFile, cacheRoot) { output ->
            ChunkedModEnvelope.write(file, descriptor, createdAt, key, output)
        }
        val entry = DesktopEntry(descriptor, cacheFile, createdAt)
        entries[descriptor.cacheKey()] = entry
        entry.asCacheEntry()
    }

    override fun activate(descriptor: NetworkModDescriptor): NetworkModCacheEntry = synchronized(lock) {
        prepareStartup()
        activatePrepared(descriptor)
    }

    /** 调用方持有 lock，且条目索引已建立；不递归触发启动初始化。 */
    private fun activatePrepared(descriptor: NetworkModDescriptor): NetworkModCacheEntry {
        val entry = entries[descriptor.cacheKey()] ?: throw IllegalStateException("Network mod cache miss: ${descriptor.name}")
        check(!NetworkModCacheFiles.isExpired(entry.createdAtMillis, System.currentTimeMillis())) {
            "Network mod cache expired: ${descriptor.name}"
        }
        val workRoot = workingRoot.apply { mkdirs() }
        val active = NetworkModCacheFiles.activeFile(workRoot, descriptor)
        val key = loadOrCreateKey(encryptedRoot.apply { mkdirs() })
        NetworkModCacheFiles.writeAtomic(active, workRoot) { output ->
            ChunkedModEnvelope.read(entry.cacheFile, key, output, descriptor)
        }
        demoteSameName(workRoot, descriptor)
        return entry.asCacheEntry(active)
    }

    override fun descriptorForManagedPath(path: String): NetworkModDescriptor? = synchronized(lock) {
        prepareStartup()
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return@synchronized null
        entries.values.firstOrNull { NetworkModCacheFiles.activeFile(workingRoot, it.descriptor).canonicalFile == file }?.descriptor
    }

    override fun cleanupWorkingCopiesOnExit() = synchronized(lock) {
        cleanupWorkingCopies(workingRoot.apply { mkdirs() })
    }

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

    private fun readEnvelope(file: File, key: ByteArray): DesktopEntry {
        val metadata = ChunkedModEnvelope.read(file, key)
        return DesktopEntry(metadata.descriptor, file, metadata.createdAtMillis)
    }

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
    }
}
