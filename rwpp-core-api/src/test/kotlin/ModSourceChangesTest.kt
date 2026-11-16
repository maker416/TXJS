/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.ModPlaytimeHash
import io.github.rwpp.game.mod.ModPlaytimeHashCache
import io.github.rwpp.game.mod.ModSourceChanges
import io.github.rwpp.game.mod.ModSourceTracker
import io.github.rwpp.game.mod.archiveFile
import io.github.rwpp.game.mod.archivePath
import io.github.rwpp.game.mod.deleteModFileSafely
import io.github.rwpp.game.mod.pathBelongsToSource
import io.github.rwpp.io.copyImportedFile
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModSourceChangesTest {
    private lateinit var root: File

    @BeforeTest fun setup() { root = Files.createTempDirectory("mod-source-").toFile() }
    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun source() = File(root, "same.rwmod").apply { writeText("old") }

    @Test fun unknownExistingSourceInvalidatesOnceAndUnchangedSourcesAreReused() {
        val source = source()
        val tracker = ModSourceTracker()
        assertEquals(setOf(source.canonicalPath), tracker.changed(listOf(source)))
        assertTrue(tracker.changed(listOf(source)).isEmpty())
        val missing = File(root, "absent.rwmod")
        assertTrue(tracker.changed(listOf(missing)).isEmpty())
    }

    @Test fun rememberedNewSourcesDoNotInvalidateUntilTheirMetadataChanges() {
        val source = source()
        val tracker = ModSourceTracker()
        tracker.remember(listOf(source))
        assertTrue(tracker.changed(listOf(source)).isEmpty())
        source.appendText("more")
        assertEquals(setOf(source.canonicalPath), tracker.changed(listOf(source)))
        assertTrue(source.setLastModified(source.lastModified() + 5_000L))
        assertEquals(setOf(source.canonicalPath), tracker.changed(listOf(source)))
        assertTrue(source.delete())
        assertEquals(setOf(source.canonicalPath), tracker.changed(listOf(source)))
        assertTrue(tracker.changed(listOf(source)).isEmpty())
    }

    @Test fun explicitReplacementInvalidatesSourceAndHashWithIdenticalMetadata() = runBlocking {
        val source = source()
        val tracker = ModSourceTracker().apply { remember(listOf(source)) }
        val cache = ModPlaytimeHashCache(File(root, "hash-cache.json"))
        val oldHash = cache.sha256(source)
        val timestamp = source.lastModified()
        File(root, "alias").mkdir()
        val alias = File(root, "alias/../${source.name}")
        ModSourceChanges.sourceChanged(alias)
        source.writeText("new")
        assertTrue(source.setLastModified(timestamp))
        assertEquals(setOf(source.canonicalPath), tracker.changed(listOf(alias, source)))
        assertEquals(ModPlaytimeHash.sha256(source), cache.sha256(source))
        assertNotEquals(oldHash, cache.sha256(source))
        assertTrue(tracker.changed(listOf(source)).isEmpty())
    }

    @Test fun deletionAndExternalReimportInvalidateEvenWhenScanNeverSeesMissingFile() {
        val source = source()
        val tracker = ModSourceTracker().apply { remember(listOf(source)) }
        val timestamp = source.lastModified()
        assertTrue(deleteModFileSafely(source, listOf(root)))
        copyImportedFile("new".byteInputStream(), root, source.name)
        assertTrue(source.setLastModified(timestamp))
        assertEquals(setOf(source.canonicalPath), tracker.changed(listOf(source)))
        assertEquals("new", source.readText())
    }

    @Test fun externalOverwriteNotifiesWithoutPrecedingDeletion() {
        val source = source()
        val tracker = ModSourceTracker().apply { remember(listOf(source)) }
        val timestamp = source.lastModified()
        copyImportedFile("new".byteInputStream(), root, source.name)
        assertTrue(source.setLastModified(timestamp))
        assertEquals(setOf(source.canonicalPath), tracker.changed(listOf(source)))
    }

    @Test fun rememberAfterScanDoesNotHideAConcurrentMutationOfExistingSource() {
        val source = source()
        val tracker = ModSourceTracker()
        tracker.changed(listOf(source))
        ModSourceChanges.sourceChanged(source)
        tracker.remember(listOf(source))
        assertEquals(setOf(source.canonicalPath), tracker.changed(listOf(source)))
    }

    @Test fun archivePathsStopAtARealContainerBoundaryAcrossSlashStyles() {
        assertEquals("/mods/example.rwmod", archivePath("/mods/example.rwmod/units/a.ini"))
        assertEquals("C:\\mods\\example.RWMOD", archivePath("C:\\mods\\example.RWMOD\\units\\a.ini"))
        assertEquals("/mods/example.rwmod", archivePath("/mods/example.rwmod"))
        assertEquals(File("C:/mods/example.RWMOD"), archiveFile("C:\\mods\\example.RWMOD\\units\\a.ini"))
        assertNull(archivePath("/mods/example.rwmod2/units/a.ini"))
        assertNull(archivePath("/mods/example.rwmod.bak"))
    }

    @Test fun resourceCacheKeysRespectSourceBoundariesAndEngineDecorations() {
        val source = "/mods/example.rwmod"
        assertTrue(pathBelongsToSource(source, source))
        assertTrue(pathBelongsToSource("$source/units/a.png", source))
        assertTrue(pathBelongsToSource("true:false:$source/units/a.png:shadow", source))
        assertTrue(pathBelongsToSource("shadow:$source", source))
        assertTrue(pathBelongsToSource("C:\\mods\\example.rwmod\\units\\a.png", "C:/mods/example.rwmod"))
        assertFalse(pathBelongsToSource("${source}2/units/a.png", source))
        assertFalse(pathBelongsToSource("$source.bak/units/a.png", source))
        assertFalse(pathBelongsToSource("/prefix$source/units/a.png", source))
        assertFalse(pathBelongsToSource("/mods/other.rwmod/units/a.png", source))
        assertFalse(pathBelongsToSource(source, ""))
    }
}
