/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.LoadedModSnapshot
import io.github.rwpp.game.mod.LoadedModSource
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModCacheEntry
import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.io.zipFolderToByte
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadedModSnapshotTest {
    private class Cache : NetworkModCache {
        val managed = mutableMapOf<String, NetworkModDescriptor>()
        var onLookup: () -> Unit = {}
        override fun prepareStartup() = Unit
        override fun find(descriptor: NetworkModDescriptor): NetworkModCacheEntry? = null
        override fun storeVerified(descriptor: NetworkModDescriptor, bytes: ByteArray): NetworkModCacheEntry =
            error("Snapshot validation must read the loaded source, not populate the transfer cache")
        override fun activate(descriptor: NetworkModDescriptor): NetworkModCacheEntry = error("Must not activate")
        override fun descriptorForManagedPath(path: String): NetworkModDescriptor? {
            onLookup()
            return managed[File(path).canonicalPath]
        }
    }

    private fun source(file: File, enabled: Boolean = true, managed: Boolean = false) =
        LoadedModSource("test", file.path, enabled, null, managed)

    private fun descriptor(file: File) = NetworkModDescriptor.fromBytes(
        "test", if (file.isDirectory) file.zipFolderToByte() else file.readBytes(),
    )

    private fun load(snapshot: LoadedModSnapshot, sources: List<LoadedModSource>, cache: Cache) {
        val token = snapshot.beginLoad()
        snapshot.beforeLoad(sources, cache)
        snapshot.completeLoad(token, sources, cache)
    }

    private fun withDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("loaded-mod-snapshot-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun currentDiskAndEnabledFlagsDoNotProveAnEngineLoad() = withDirectory { root ->
        val file = File(root, "test.rwmod").apply { writeText("already downloaded") }
        val sources = listOf(source(file))
        val expected = listOf(descriptor(file))
        val snapshot = LoadedModSnapshot()
        val cache = Cache()
        assertFalse(snapshot.isExact(sources, expected, cache))
        val token = snapshot.beginLoad()
        snapshot.completeLoad(token, sources, cache) // No source capture before parsing.
        assertFalse(snapshot.isExact(sources, expected, cache))
        load(snapshot, sources, cache)
        assertTrue(snapshot.isExact(sources, expected, cache))
        snapshot.beginLoad() // Failed or unfinished reload must invalidate the old success.
        assertFalse(snapshot.isExact(sources, expected, cache))
    }

    @Test fun changedEnableSelectionCannotReuseLoadedContent() = withDirectory { root ->
        val file = File(root, "test.rwmod").apply { writeText("units") }
        val snapshot = LoadedModSnapshot()
        val cache = Cache()
        val disabled = listOf(source(file, enabled = false))
        load(snapshot, disabled, cache)
        assertTrue(snapshot.isExact(disabled, emptyList(), cache))
        assertFalse(snapshot.isExact(listOf(source(file)), listOf(descriptor(file)), cache))
        load(snapshot, listOf(source(file)), cache)
        assertFalse(snapshot.isExact(disabled, emptyList(), cache))
    }

    @Test fun sameLengthFileChangeWithRestoredTimestampStillInvalidates() = withDirectory { root ->
        val file = File(root, "test.rwmod").apply { writeText("version1") }
        val sources = listOf(source(file))
        val oldDescriptor = descriptor(file)
        val snapshot = LoadedModSnapshot()
        val cache = Cache()
        load(snapshot, sources, cache)
        assertTrue(snapshot.isExact(sources, listOf(oldDescriptor), cache))
        val stamp = Files.getLastModifiedTime(file.toPath())
        file.writeText("version2")
        Files.setLastModifiedTime(file.toPath(), stamp)
        assertFalse(snapshot.isExact(sources, listOf(oldDescriptor), cache))
        assertFalse(snapshot.isExact(sources, listOf(descriptor(file)), cache))
    }

    @Test fun pureMapFolderIsCapturedWithoutAnyUnitRegistry() = withDirectory { root ->
        val folder = File(root, "maps-only").apply { mkdirs() }
        File(folder, "mod-info.txt").writeText("[mod]\ntitle: maps")
        val map = File(folder, "island.tmx").apply { writeText("map version 1") }
        val sources = listOf(source(folder))
        val snapshot = LoadedModSnapshot()
        val cache = Cache()
        val original = descriptor(folder)
        load(snapshot, sources, cache)
        assertTrue(snapshot.isExact(sources, listOf(original), cache))
        map.writeText("map version 2")
        assertFalse(snapshot.isExact(sources, listOf(original), cache))
        assertFalse(snapshot.isExact(sources, listOf(descriptor(folder)), cache))
    }

    @Test fun rescanCannotHideAChangeDuringParsing() = withDirectory { root ->
        val file = File(root, "test.rwmod").apply { writeText("version1") }
        val sources = listOf(source(file))
        val snapshot = LoadedModSnapshot()
        val cache = Cache()
        val token = snapshot.beginLoad()
        snapshot.beforeLoad(sources, cache)
        file.writeText("version2")
        snapshot.beforeLoad(sources, cache) // A post-parse scan must not overwrite version 1.
        snapshot.completeLoad(token, sources, cache)
        assertFalse(snapshot.isExact(sources, listOf(descriptor(file)), cache))
        load(snapshot, sources, cache)
        assertTrue(snapshot.isExact(sources, listOf(descriptor(file)), cache))
    }

    @Test fun failedModAndUnavailableSourceCannotBecomeLoadedSnapshots() = withDirectory { root ->
        val file = File(root, "test.rwmod").apply { writeText("units") }
        val source = source(file)
        val snapshot = LoadedModSnapshot()
        val cache = Cache()
        val failed = listOf(source.copy(errorMessage = "Unit parse failed"))
        load(snapshot, failed, cache)
        assertFalse(snapshot.isExact(failed, listOf(descriptor(file)), cache))
        // Stale errors from the previous attempt may disappear after a successful new load.
        val token = snapshot.beginLoad()
        snapshot.beforeLoad(failed, cache)
        snapshot.completeLoad(token, listOf(source), cache)
        assertTrue(snapshot.isExact(listOf(source), listOf(descriptor(file)), cache))
        assertFalse(snapshot.isExact(failed, listOf(descriptor(file)), cache))
        val expected = descriptor(file)
        file.delete()
        assertFalse(snapshot.isExact(listOf(source), listOf(expected), cache))
    }

    @Test fun managedWorkingCopyUsesPlaintextDescriptorAndDetectsTampering() = withDirectory { root ->
        val working = File(root, "test.network.rwmod").apply { writeText("plaintext payload") }
        // This envelope has a different size/hash and is deliberately never supplied as the source.
        val envelope = File(root, "test.rwcache").apply { writeText("encrypted cache envelope") }
        val expected = descriptor(working)
        val sources = listOf(source(working, managed = true))
        val cache = Cache().apply { managed[working.canonicalPath] = expected }
        val snapshot = LoadedModSnapshot()
        load(snapshot, sources, cache)
        assertTrue(snapshot.isExact(sources, listOf(expected), cache))
        cache.managed[working.canonicalPath] = descriptor(envelope)
        assertFalse(snapshot.isExact(sources, listOf(expected), cache))
        cache.managed[working.canonicalPath] = expected
        working.writeText("tampered! payload")
        assertFalse(snapshot.isExact(sources, listOf(expected), cache))
        assertFalse(snapshot.isExact(sources, listOf(descriptor(working)), cache))
    }

    @Test fun unmanagedNetworkCopyAndMutableCacheClaimsCannotProveALoad() = withDirectory { root ->
        val file = File(root, "test.network.rwmod").apply { writeText("version1") }
        val sources = listOf(source(file, managed = true))
        val snapshot = LoadedModSnapshot()
        val cache = Cache()
        load(snapshot, sources, cache)
        assertFalse(snapshot.isExact(sources, listOf(descriptor(file)), cache))
        cache.managed[file.canonicalPath] = descriptor(file)
        val token = snapshot.beginLoad()
        snapshot.beforeLoad(sources, cache)
        file.writeText("version2")
        cache.managed[file.canonicalPath] = descriptor(file)
        snapshot.completeLoad(token, sources, cache)
        assertFalse(snapshot.isExact(sources, listOf(descriptor(file)), cache))
    }

    @Test fun staleCompletionAndConcurrentInvalidationCannotPublishOrReuseSnapshot() = withDirectory { root ->
        val file = File(root, "test.network.rwmod").apply { writeText("units") }
        val expected = descriptor(file)
        val sources = listOf(source(file, managed = true))
        val cache = Cache().apply { managed[file.canonicalPath] = expected }
        val snapshot = LoadedModSnapshot()
        val old = snapshot.beginLoad()
        snapshot.beforeLoad(sources, cache)
        val current = snapshot.beginLoad()
        snapshot.beforeLoad(sources, cache)
        snapshot.completeLoad(old, sources, cache)
        assertFalse(snapshot.isExact(sources, listOf(expected), cache))
        snapshot.completeLoad(current, sources, cache)
        assertTrue(snapshot.isExact(sources, listOf(expected), cache))
        cache.onLookup = { snapshot.invalidate() }
        assertFalse(snapshot.isExact(sources, listOf(expected), cache))
    }

    @Test fun emptyLoadDuplicatesAndCancellationRemainConservative() = withDirectory { root ->
        val snapshot = LoadedModSnapshot()
        val cache = Cache()
        load(snapshot, emptyList(), cache)
        assertTrue(snapshot.isExact(emptyList(), emptyList(), cache))
        val file = File(root, "test.rwmod").apply { writeText("units") }
        val sources = listOf(source(file))
        val expected = descriptor(file)
        load(snapshot, sources, cache)
        assertFalse(snapshot.isExact(sources, listOf(expected, expected), cache))
        assertFailsWith<CancellationException> {
            snapshot.isExact(sources, listOf(expected), cache) { throw CancellationException("cancel") }
        }
        // Cancelling validation must not rewrite a previously successful load.
        assertTrue(snapshot.isExact(sources, listOf(expected), cache))
        load(snapshot, sources + sources, cache)
        assertFalse(snapshot.isExact(sources + sources, listOf(expected), cache))
    }
}
