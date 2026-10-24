/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import io.github.rwpp.AppContext
import io.github.rwpp.desktop.impl.AppContextImpl
import io.github.rwpp.desktop.impl.DesktopNetworkModCache
import io.github.rwpp.game.mod.NetworkModCacheFiles
import io.github.rwpp.game.mod.NetworkModDescriptor
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class DesktopNetworkModCacheTest {
    @Test fun corruptReplacementKeepsPreviousActiveVersion() = withRoot { root, context ->
        val cache = DesktopNetworkModCache(context)
        val old = NetworkModDescriptor.fromBytes("same", "old".toByteArray())
        cache.storeVerified(old, "old".toByteArray())
        val oldFile = cache.activate(old).payloadFile
        val next = NetworkModDescriptor.fromBytes("same", "new".toByteArray())
        val encrypted = cache.storeVerified(next, "new".toByteArray()).payloadFile
        encrypted.writeBytes(encrypted.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        assertFails { cache.activate(next) }
        assertEquals("old", oldFile.readText())
        assertFalse(NetworkModCacheFiles.activeFile(File(root, "mods/units"), next).exists())
        assertFalse(File(root, "mods/units").listFiles().orEmpty().any { it.name.endsWith(NetworkModCacheFiles.TEMP_SUFFIX) })
    }

    @Test fun restartRestoresEncryptedEntriesAndEngineVisibleFiles() = withRoot { root, context ->
        val payloads = listOf("first mod", "second mod").map { it.toByteArray() }
        val descriptors = payloads.mapIndexed { i, bytes -> NetworkModDescriptor.fromBytes("fixture-$i", bytes) }
        val first = DesktopNetworkModCache(context)
        descriptors.zip(payloads).forEach { (descriptor, bytes) ->
            first.storeVerified(descriptor, bytes)
            first.activate(descriptor)
        }
        first.cleanupWorkingCopiesOnExit()
        val second = DesktopNetworkModCache(context)
        second.prepareStartup()
        second.prepareStartup() // 幂等，不重新清空刚恢复的索引/工作副本。
        descriptors.zip(payloads).forEach { (descriptor, bytes) ->
            assertNotNull(second.find(descriptor))
            val active = NetworkModCacheFiles.activeFile(File(root, "mods/units"), descriptor)
            assertContentEquals(bytes, active.readBytes())
            assertEquals(descriptor, second.descriptorForManagedPath(active.path))
            assertEquals(first.find(descriptor)!!.createdAtMillis, second.find(descriptor)!!.createdAtMillis)
        }
        assertFalse(File(root, "units").exists())
    }

    @Test fun badEnvelopeDoesNotPreventValidCacheRestoration() = withRoot { root, context ->
        val bytes = "valid mod".toByteArray()
        val descriptor = NetworkModDescriptor.fromBytes("valid", bytes)
        DesktopNetworkModCache(context).storeVerified(descriptor, bytes)
        val corrupt = File(root, ".rwpp/network-mod-cache-v2/broken.rwcache").apply { writeText("broken") }
        val restarted = DesktopNetworkModCache(context)
        restarted.prepareStartup()
        assertNotNull(restarted.find(descriptor))
        assertFalse(corrupt.exists())
        assertTrue(NetworkModCacheFiles.activeFile(File(root, "mods/units"), descriptor).isFile)
    }

    @Test fun newestAuthenticatedVersionWinsAndOlderVersionCanStillBeActivated() = withRoot { root, context ->
        val now = System.currentTimeMillis()
        val cache = DesktopNetworkModCache(context)
        val old = storeAt(root, cache, "same", "old".toByteArray(), now - 2_000)
        val newest = storeAt(root, cache, "same", "new".toByteArray(), now - 1_000)
        val restarted = DesktopNetworkModCache(context)
        restarted.prepareStartup()
        val work = File(root, "mods/units")
        assertEquals("new", NetworkModCacheFiles.activeFile(work, newest).readText())
        assertFalse(NetworkModCacheFiles.activeFile(work, old).exists())
        assertNotNull(restarted.find(old))
        assertEquals("old", restarted.activate(old).payloadFile.readText())
        assertFalse(NetworkModCacheFiles.activeFile(work, newest).exists())
    }

    @Test fun corruptNewestFallsBackAndExpiredEntriesAreRemoved() = withRoot { root, context ->
        val now = System.currentTimeMillis()
        val cache = DesktopNetworkModCache(context)
        val old = storeAt(root, cache, "same", "old".toByteArray(), now - 2_000)
        val corrupt = storeAt(root, cache, "same", "new".toByteArray(), now - 1_000)
        val corruptFile = cache.find(corrupt)!!.payloadFile
        corruptFile.writeBytes(corruptFile.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        val expired = storeAt(root, cache, "expired", byteArrayOf(1), now - io.github.rwpp.game.mod.NETWORK_MOD_CACHE_TTL_MILLIS)
        val restarted = DesktopNetworkModCache(context)
        restarted.prepareStartup()
        assertEquals("old", NetworkModCacheFiles.activeFile(File(root, "mods/units"), old).readText())
        assertNull(restarted.find(corrupt))
        assertNull(restarted.find(expired))
        assertFalse(corruptFile.exists())
    }

    @Test fun maximumUtf8NameAndEmptyPayloadSurviveHeaderProbe() = withRoot { root, context ->
        val descriptor = NetworkModDescriptor.fromBytes("模".repeat(NetworkModDescriptor.MAX_NAME_LENGTH), byteArrayOf())
        DesktopNetworkModCache(context).storeVerified(descriptor, byteArrayOf())
        val restarted = DesktopNetworkModCache(context)
        restarted.prepareStartup()
        assertNotNull(restarted.find(descriptor))
        assertContentEquals(byteArrayOf(), NetworkModCacheFiles.activeFile(File(root, "mods/units"), descriptor).readBytes())
    }

    /** 固定认证时间戳，避免依赖 sleep 或文件系统时间分辨率来测试版本排序和 TTL。 */
    private fun storeAt(root: File, cache: DesktopNetworkModCache, name: String, bytes: ByteArray, createdAt: Long): NetworkModDescriptor {
        val descriptor = NetworkModDescriptor.fromBytes(name, bytes)
        val file = cache.storeVerified(descriptor, bytes).payloadFile
        val payload = File(root, "fixture.tmp").apply { writeBytes(bytes) }
        try {
            file.outputStream().use {
                io.github.rwpp.game.mod.ChunkedModEnvelope.write(payload, descriptor, createdAt,
                    File(root, ".rwpp/network-mod-cache-v2/cache.key").readBytes(), it)
            }
        } finally { payload.delete() }
        return descriptor
    }

    private fun withRoot(test: (File, AppContext) -> Unit) {
        val scratch = File("build/tmp/network-cache-test").apply { mkdirs() }.canonicalFile
        val root = Files.createTempDirectory(scratch.toPath(), "cache-").toFile().canonicalFile
        val context = object : AppContext by AppContextImpl() {
            override fun externalStoragePath(path: String) = File(root, path).path
        }
        try { test(root, context) } finally {
            check(root.toPath().startsWith(scratch.toPath()) && root != scratch)
            root.deleteRecursively()
        }
    }
}
