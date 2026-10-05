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
import java.nio.file.Files
import kotlin.test.*

class DesktopNetworkModCacheTest {
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
        val corrupt = File(root, ".rwpp/network-mod-cache/broken.rwcache").apply { writeText("broken") }
        val restarted = DesktopNetworkModCache(context)
        restarted.prepareStartup()
        assertNotNull(restarted.find(descriptor))
        assertFalse(corrupt.exists())
        assertTrue(NetworkModCacheFiles.activeFile(File(root, "mods/units"), descriptor).isFile)
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
