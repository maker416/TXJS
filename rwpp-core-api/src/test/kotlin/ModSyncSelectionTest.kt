/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModCacheEntry
import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.game.mod.resolveModEnabledByFileName
import io.github.rwpp.game.mod.selectModSyncFiles
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModSyncSelectionTest {
    private class FileMod(override val name: String, private val file: File) : Mod {
        override val id = 1
        override val description = ""
        override val minVersion = ""
        override val errorMessage: String? = null
        override var isEnabled = true
        override val path get() = file.path
        override fun getRamUsed() = ""
        override fun getSize() = file.length()
        override fun getBytes() = file.readBytes()
    }

    private class Cache(private val root: File, private val bytes: ByteArray) : NetworkModCache {
        var activations = 0
        override fun prepareStartup() {}
        override fun find(descriptor: NetworkModDescriptor): NetworkModCacheEntry? = null
        override fun storeVerified(descriptor: NetworkModDescriptor, bytes: ByteArray): NetworkModCacheEntry = error("unused")
        override fun descriptorForManagedPath(path: String): NetworkModDescriptor? = null
        override fun activate(descriptor: NetworkModDescriptor): NetworkModCacheEntry {
            activations++
            val file = root.resolve("${descriptor.cacheKey()}.network.rwmod")
            file.writeBytes(bytes)
            return NetworkModCacheEntry(descriptor, file, file, 0L)
        }
    }

    @Test
    fun staleLocalVersionIsDisabledAndNewCacheFileIsEnabledBeforeEngineRegistersIt() {
        val root = createTempDirectory().toFile()
        try {
            val stale = root.resolve("demo.rwmod").apply { writeText("old units") }
            val bytes = "new units".encodeToByteArray()
            val descriptor = NetworkModDescriptor.fromBytes("Demo", bytes)
            val cache = Cache(root, bytes)
            val selected = selectModSyncFiles(listOf(FileMod("Demo", stale)), listOf(descriptor), cache)
            val selection = selected.associate { it.path to true }

            assertEquals(1, cache.activations)
            assertEquals(1, selected.size)
            assertFalse(resolveModEnabledByFileName(listOf(stale.path), selection))
            val activated = selected.single()
            assertTrue(resolveModEnabledByFileName(listOf(activated.path), selection))
            assertTrue(descriptor.matches(activated.readBytes()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun matchingOrdinaryLocalModWinsOverDuplicateNetworkCopyWithoutActivatingAnother() {
        val root = createTempDirectory().toFile()
        try {
            val bytes = "same units".encodeToByteArray()
            val local = root.resolve("demo.rwmod").apply { writeBytes(bytes) }
            val network = root.resolve("demo.network.rwmod").apply { writeBytes(bytes) }
            val descriptor = NetworkModDescriptor.fromBytes("Demo", bytes)
            val cache = Cache(root, bytes)
            val selected = selectModSyncFiles(
                listOf(FileMod("Demo", network), FileMod("Demo", local)),
                listOf(descriptor),
                cache,
            )
            assertEquals(setOf(local.canonicalFile), selected)
            assertEquals(0, cache.activations)
            assertFalse(resolveModEnabledByFileName(listOf(network.path), selected.associate { it.path to true }))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unreadableOldWorkingCopyCannotPreventActivatingVerifiedCache() {
        val root = createTempDirectory().toFile()
        try {
            val removed = root.resolve("removed.network.rwmod")
            val bytes = "units".encodeToByteArray()
            val descriptor = NetworkModDescriptor.fromBytes("Demo", bytes)
            val cache = Cache(root, bytes)
            var failures = 0
            val selected = selectModSyncFiles(listOf(FileMod("Demo", removed)), listOf(descriptor), cache) { _, _ -> failures++ }
            assertEquals(1, failures)
            assertEquals(1, cache.activations)
            assertTrue(descriptor.matches(selected.single().readBytes()))
        } finally {
            root.deleteRecursively()
        }
    }
}
