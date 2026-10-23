/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import io.github.rwpp.AppContext
import io.github.rwpp.android.impl.AndroidNetworkModCache
import io.github.rwpp.game.mod.NETWORK_MOD_CACHE_TTL_MILLIS
import io.github.rwpp.game.mod.NetworkModCacheFiles
import io.github.rwpp.game.mod.NetworkModDescriptor
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.*

class AndroidNetworkModCacheTest {
    @Test fun startupRestoresNewestVersionAndRemovesInvalidPayloads() = withRoot { root, context ->
        val units = File(root, "units").apply { mkdirs() }
        val now = System.currentTimeMillis()
        fun fixture(name: String, body: String, createdAt: Long, active: Boolean): NetworkModDescriptor {
            val bytes = body.toByteArray()
            val descriptor = NetworkModDescriptor.fromBytes(name, bytes)
            val file = if (active) NetworkModCacheFiles.activeFile(units, descriptor)
                else NetworkModCacheFiles.inactiveFile(units, descriptor)
            file.writeBytes(bytes)
            NetworkModCacheFiles.writeMetadata(NetworkModCacheFiles.metadataFile(units, descriptor),
                descriptor, createdAt, file.name, units)
            return descriptor
        }
        val old = fixture("same", "old", now - 2_000, true)
        val newest = fixture("same", "new", now - 1_000, false)
        val expired = fixture("expired", "stale", now - NETWORK_MOD_CACHE_TTL_MILLIS, true)
        val corrupt = fixture("corrupt", "good", now, true)
        NetworkModCacheFiles.activeFile(units, corrupt).writeText("evil") // 同长度，仍必须做 SHA-256。
        val orphan = File(units, "orphan.network.rwmod").apply { writeText("orphan") }
        File(units, "broken.meta").writeText("broken")

        val cache = AndroidNetworkModCache(context)
        cache.prepareStartup()
        cache.prepareStartup()
        assertEquals("new", NetworkModCacheFiles.activeFile(units, newest).readText())
        assertEquals("old", NetworkModCacheFiles.inactiveFile(units, old).readText())
        assertFalse(NetworkModCacheFiles.activeFile(units, old).exists())
        assertNotNull(cache.find(old))
        assertNotNull(cache.find(newest))
        assertNull(cache.find(expired))
        assertNull(cache.find(corrupt))
        assertFalse(NetworkModCacheFiles.activeFile(units, expired).exists())
        assertFalse(NetworkModCacheFiles.activeFile(units, corrupt).exists())
        assertFalse(orphan.exists())
        assertFalse(File(units, "broken.meta").exists())
    }

    private fun withRoot(test: (File, AppContext) -> Unit) {
        val scratch = File("build/tmp/network-cache-test").apply { mkdirs() }.canonicalFile
        val root = Files.createTempDirectory(scratch.toPath(), "cache-").toFile()
        val context = Proxy.newProxyInstance(AppContext::class.java.classLoader, arrayOf(AppContext::class.java)) { _, method, args ->
            check(method.name == "internalStoragePath") { "Unexpected call: ${method.name}" }
            File(root, args!![0] as String).path
        } as AppContext
        try { test(root, context) } finally {
            check(root.canonicalFile.toPath().startsWith(scratch.toPath()) && root.canonicalFile != scratch)
            root.deleteRecursively()
        }
    }
}
