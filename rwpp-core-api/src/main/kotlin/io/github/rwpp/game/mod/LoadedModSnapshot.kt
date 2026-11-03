/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import io.github.rwpp.io.zipFolderTo
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Immutable engine records; no unit registry or live Mod object is retained. */
data class LoadedModSource(
    val name: String,
    val path: String,
    val enabled: Boolean,
    val errorMessage: String?,
    val managedNetworkMod: Boolean,
) {
    companion object {
        fun from(mod: Mod) = LoadedModSource(mod.name, mod.path, mod.isEnabled, mod.errorMessage, mod.isNetworkMod)
    }
}

/** A snapshot is published only when sources match before parsing and after a successful load. */
class LoadedModSnapshot {
    private data class Source(val path: String, val descriptor: NetworkModDescriptor, val metadata: String)
    private val lock = Any()
    private var generation = 0L
    private var recording = false
    private var beforeCaptured = false
    private var before: List<Source>? = null
    private var loaded: List<Source>? = null

    fun beginLoad(): Long = synchronized(lock) {
        generation++
        recording = true
        beforeCaptured = false
        before = null
        loaded = null
        generation
    }

    fun invalidate() = synchronized(lock) {
        generation++
        recording = false
        beforeCaptured = false
        before = null
        loaded = null
    }

    /** Call after directory scanning and selection, before the engine reads any unit or map content. */
    fun beforeLoad(sources: List<LoadedModSource>, cache: NetworkModCache) {
        val token = synchronized(lock) {
            if (!recording || beforeCaptured) return
            // A later rescan must not replace the baseline after parsing has already started.
            beforeCaptured = true
            generation
        }
        val captured = capture(sources, cache, allowPriorErrors = true)
        synchronized(lock) { if (recording && generation == token) before = captured }
    }

    /** Failures leave the old snapshot invalid; this method must only follow a successful engine load. */
    fun completeLoad(token: Long, sources: List<LoadedModSource>, cache: NetworkModCache) {
        val expected = synchronized(lock) {
            if (!recording || generation != token) return
            before
        }
        val actual = if (expected != null) capture(sources, cache) else null
        synchronized(lock) {
            if (recording && generation == token) {
                loaded = actual?.takeIf { it == expected }
                before = null
                recording = false
            }
        }
    }

    fun isExact(
        sources: List<LoadedModSource>, descriptors: List<NetworkModDescriptor>, cache: NetworkModCache,
        checkCancelled: () -> Unit = {},
    ): Boolean {
        val (token, snapshot) = synchronized(lock) {
            if (recording) return false
            generation to (loaded ?: return false)
        }
        val keys = descriptors.map { it.cacheKey() }.toSet()
        if (keys.size != descriptors.size || descriptors.map { it.name }.toSet().size != descriptors.size ||
            snapshot.size != descriptors.size || snapshot.map { it.descriptor.cacheKey() }.toSet() != keys) return false
        if (capture(sources, cache, checkCancelled = checkCancelled) != snapshot) return false
        return synchronized(lock) { !recording && generation == token && loaded === snapshot }
    }

    private fun capture(
        sources: List<LoadedModSource>, cache: NetworkModCache, allowPriorErrors: Boolean = false,
        checkCancelled: () -> Unit = {},
    ): List<Source>? {
        return try {
            checkCancelled()
            val enabled = sources.filter { it.enabled }
            if (!allowPriorErrors && enabled.any { it.errorMessage != null }) return null
            val captured = enabled.map { source ->
                val file = File(source.path).canonicalFile
                val beforeMetadata = metadata(file, checkCancelled)
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val sink = object : OutputStream() {
                    override fun write(value: Int) {
                        checkCancelled()
                        digest.update(value.toByte())
                        size++
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        checkCancelled()
                        digest.update(bytes, offset, length)
                        size += length
                    }
                }
                if (file.isDirectory) file.zipFolderTo(sink)
                else file.inputStream().use { it.copyTo(sink, 64 * 1024) }
                val actual = NetworkModDescriptor(source.name, size, hex(digest.digest()))
                // This is the engine-visible plaintext copy, not the encrypted cache envelope.
                val descriptor = if (source.managedNetworkMod) {
                    val managed = cache.descriptorForManagedPath(file.path) ?: return null
                    if (managed.cacheKey() != actual.cacheKey()) return null
                    managed.copy()
                } else actual
                if (metadata(file, checkCancelled) != beforeMetadata) return null
                Source(file.path, descriptor, beforeMetadata)
            }.sortedBy { it.path }
            if (captured.map { it.path }.toSet().size != captured.size ||
                captured.map { it.descriptor.name }.toSet().size != captured.size) null else captured
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null // Unreadable, virtual or changing sources cannot prove what the engine loaded.
        }
    }

    private fun metadata(root: File, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() { override fun write(value: Int) {} }
        DataOutputStream(DigestOutputStream(sink, digest)).use { output ->
            fun append(file: File) {
                checkCancelled()
                val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                check(!attributes.isSymbolicLink && (attributes.isRegularFile || attributes.isDirectory))
                output.writeUTF(root.toPath().relativize(file.toPath()).toString())
                output.writeBoolean(attributes.isDirectory)
                output.writeUTF(attributes.fileKey()?.toString().orEmpty())
                output.writeUTF(attributes.creationTime().toString())
                output.writeUTF(attributes.lastModifiedTime().toString())
                output.writeLong(attributes.size())
                if (attributes.isDirectory) {
                    val children = checkNotNull(file.listFiles())
                    children.sortedBy { it.name }.forEach(::append)
                }
            }
            append(root)
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
