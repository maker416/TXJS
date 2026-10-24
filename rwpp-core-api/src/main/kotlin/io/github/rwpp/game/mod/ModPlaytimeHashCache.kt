/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest

/** 仅保存内容身份，不保存账号或 Token。目录指纹校验与哈希使用相同的链接边界。 */
class ModPlaytimeHashCache(
    private val file: File,
    private val capacity: Int = 256,
    private val hash: (File, () -> Unit) -> String = ModPlaytimeHash::sha256,
) {
    @Serializable private data class Entry(val path: String, val signature: String, val sha256: String)
    @Serializable private data class Store(val version: Int, val entries: List<Entry>)
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var loaded = false
    private var mutation = 0L

    init {
        require(capacity > 0)
        synchronized(instances) {
            instances.removeAll { it.get() == null }
            instances.add(WeakReference(this))
        }
    }

    suspend fun sha256(source: File): String = withContext(Dispatchers.IO) {
        // 串行计算让同源并发请求在前一个结果落盘后命中，同时限制压缩争用。
        hashing.withLock {
            val context = currentCoroutineContext()
            val checkpoint = { context.ensureActive() }
            checkpoint()
            val path = source.canonicalPath
            val epoch = synchronized(lock) { load(); mutation }
            val before = signature(source, checkpoint)
            synchronized(lock) {
                check(epoch == mutation) { "Mod changed during fingerprinting" }
                entries[path]?.takeIf { it.signature == before }?.let { return@withContext it.sha256 }
            }
            val result = hash(source, checkpoint)
            checkpoint()
            if (before != signature(source, checkpoint)) throw IOException("Mod changed during hashing")
            require(result.matches(Regex("[0-9a-f]{64}"))) { "Invalid mod SHA-256" }
            synchronized(lock) {
                check(epoch == mutation) { "Mod changed during hashing" }
                entries[path] = Entry(path, before, result)
                while (entries.size > capacity) entries.remove(entries.keys.first())
                save()
            }
            result
        }
    }

    /** 在应用自身写入/删除前调用，哪怕新文件保留了旧 mtime 也不能命中。 */
    private fun invalidate(source: File) = synchronized(lock) {
        load()
        mutation++
        val path = source.canonicalPath
        val removed = entries.keys.removeAll {
            it == path || it.startsWith(path + File.separator) || path.startsWith(it + File.separator)
        }
        if (removed) save()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            if (file.length() > 2 * 1024 * 1024) return
            val store = Json.decodeFromString<Store>(file.readText())
            if (store.version == FORMAT_VERSION) store.entries.takeLast(capacity).forEach {
                if (it.sha256.matches(Regex("[0-9a-f]{64}"))) entries[it.path] = it
            }
        }
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            file.parentFile?.mkdirs()
            tmp.outputStream().use { output ->
                output.write(Json.encodeToString(Store(version = FORMAT_VERSION, entries = entries.values.toList())).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure {
            // 写失败仍可使用进程内结果；移除旧文件以避免下次启动复用已失效的条目。
            runCatching { file.delete() }
        }
        runCatching { tmp.delete() }
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private val hashing = Mutex()
        private val instances = mutableListOf<WeakReference<ModPlaytimeHashCache>>()

        fun sourceChanged(source: File) {
            val caches = synchronized(instances) { instances.mapNotNull { it.get() } }
            caches.forEach { cache -> runCatching { cache.invalidate(source) } }
        }

        internal fun signature(source: File, checkpoint: () -> Unit = {}): String {
            val root = source.canonicalFile
            val digest = MessageDigest.getInstance("SHA-256")
            val sink = object : OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {}
            }
            DataOutputStream(DigestOutputStream(sink, digest)).use { output ->
                val visited = HashSet<String>()
                fun append(item: File) {
                    checkpoint()
                    val canonical = item.canonicalFile
                    if (canonical != root && !canonical.toPath().startsWith(root.toPath())) {
                        throw IOException("Mod folder contains a link outside its root")
                    }
                    val relative = root.toPath().relativize(item.absoluteFile.toPath()).toString().replace('\\', '/')
                    val name = relative.toByteArray(Charsets.UTF_8)
                    output.writeInt(name.size); output.write(name)
                    if (item.isDirectory) {
                        if (!visited.add(canonical.path)) throw IOException("Mod folder contains a directory link cycle")
                        output.writeByte(1)
                        val children = item.listFiles() ?: throw IOException("Cannot read mod folder")
                        children.sortedBy { it.name }.forEach(::append)
                    } else if (item.isFile) {
                        output.writeByte(2); output.writeLong(item.length()); output.writeLong(item.lastModified())
                    } else throw IOException("Mod source does not exist")
                }
                append(root)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
