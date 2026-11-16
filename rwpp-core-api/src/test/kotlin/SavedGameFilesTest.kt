/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.map.SavedGameFile
import io.github.rwpp.game.map.deleteSavedGameFile
import io.github.rwpp.game.map.importSavedGameFile
import io.github.rwpp.game.map.renameSavedGameFile
import io.github.rwpp.game.map.scanSavedGameFiles
import io.github.rwpp.game.map.scanSavedGameFilesFromContext
import io.github.rwpp.AppContext
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SavedGameFilesTest {
    @Test
    fun scansFreshFilesNewestFirstAndExcludesIncompleteAndAuxiliaryFiles() = withRoot { root ->
        save(root, "older.rwsave").setLastModified(1_000L)
        save(root, "newer.RWSAVE").setLastModified(2_000L)
        save(root, "unfinished.rwsave.tmp")
        save(root, "unfinished.rwsave.tmp2")
        save(root, "older.rwsave.map")
        root.resolve("readme.txt").writeText("not a saved game")
        root.resolve("nested.rwsave").mkdir()
        save(root.resolve("nested.rwsave"), "hidden.rwsave")
        assertEquals(listOf("newer.RWSAVE", "older.rwsave"), scanSavedGameFiles(root).map { it.name })
        save(root, "latest.rwsave").setLastModified(3_000L)
        assertEquals("latest.rwsave", scanSavedGameFiles(root).first().name)
    }

    @Test
    fun preservesDottedLegacySaveNamesAndExactEngineFilename() = withRoot { root ->
        val legacy = SavedGameFile(save(root, "My_game v1.15 (21.50.37)"))
        val current = SavedGameFile(save(root, "My_game v1.15.rwsave"))
        assertEquals(2, scanSavedGameFiles(root).size)
        for (entry in listOf(legacy, current)) {
            val map = entry.toGameMap(3)
            assertEquals(entry.name, map.mapName + map.getMapSuffix())
            assertEquals(entry.file.readBytes().toList(), map.openInputStream().use { it.readBytes().toList() })
        }
        assertEquals("My_game v1.15 (21.50.37)", legacy.displayName())
        assertEquals("My_game v1.15", current.displayName())
    }

    @Test
    fun corruptSaveRemainsVisibleForManagement() = withRoot { root ->
        root.resolve("broken.rwsave").writeText("broken")
        assertEquals(listOf("broken.rwsave"), scanSavedGameFiles(root).map { it.name })
    }

    @Test
    fun metadataDescribesActualFile() = withRoot { root ->
        val file = save(root, "game.rwsave").also { it.setLastModified(4_000L) }
        val savedGame = scanSavedGameFiles(root).single()
        assertEquals(4_000L, savedGame.lastModifiedMillis)
        assertEquals(file.length(), savedGame.fileSizeBytes)
    }

    @Test
    fun renameMovesSaveAndFullFilenameMapWithoutTruncatingDots() = withRoot { root ->
        val file = save(root, "old v1.15.rwsave")
        root.resolve(file.name + ".map").writeText("map bytes")
        val renamed = renameSavedGameFile(SavedGameFile(file), "new_v1.16", root)
        assertEquals("new_v1.16.rwsave", renamed.name)
        assertFalse(file.exists())
        assertEquals("map bytes", root.resolve(renamed.name + ".map").readText())
        assertFalse(root.resolve(file.name + ".map").exists())
    }

    @Test
    fun renameKeepsLegacyNoSuffixStyle() = withRoot { root ->
        val file = save(root, "old v1.15")
        val renamed = renameSavedGameFile(SavedGameFile(file), "new v1.16", root)
        assertEquals("new v1.16", renamed.name)
        assertEquals(listOf(renamed), scanSavedGameFiles(root))
    }

    @Test
    fun renameNeverOverwritesSaveOrOrphanedMap() = withRoot { root ->
        val original = save(root, "original.rwsave")
        val existing = save(root, "existing.rwsave")
        assertFailsWith<IOException> { renameSavedGameFile(SavedGameFile(original), "existing", root) }
        root.resolve("orphan.rwsave.map").writeText("valuable map")
        assertFailsWith<IOException> { renameSavedGameFile(SavedGameFile(original), "orphan", root) }
        assertTrue(original.exists())
        assertTrue(existing.exists())
        assertEquals("valuable map", root.resolve("orphan.rwsave.map").readText())
    }

    @Test
    fun rejectsPathTraversalReservedNamesAndTemporaryNames() = withRoot { root ->
        val original = SavedGameFile(save(root, "original.rwsave"))
        for (name in listOf("../outside", "..\\outside", "CON", "bad:name", "", ".rwsave", "file.tmp", "file.")) {
            assertFailsWith<IllegalArgumentException>(name) { renameSavedGameFile(original, name, root) }
        }
        assertTrue(original.file.exists())
    }

    @Test
    fun deleteRemovesOnlySaveAndMatchingMap() = withRoot { root ->
        val original = save(root, "game.rwsave")
        root.resolve("game.rwsave.map").writeText("map")
        val unrelated = save(root, "another.rwsave")
        deleteSavedGameFile(SavedGameFile(original), root)
        assertFalse(original.exists())
        assertFalse(root.resolve("game.rwsave.map").exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun invalidCompanionCannotCausePartialRenameOrDelete() = withRoot { root ->
        val original = save(root, "game.rwsave")
        root.resolve("game.rwsave.map").mkdir()
        assertFailsWith<IOException> { renameSavedGameFile(SavedGameFile(original), "renamed", root) }
        assertFailsWith<IOException> { deleteSavedGameFile(SavedGameFile(original), root) }
        assertTrue(original.exists())
        assertFalse(root.resolve("renamed.rwsave").exists())
    }

    @Test
    fun cannotManageOutsideFilesOrNestedFiles() = withRoot { root ->
        val outside = File.createTempFile("outside", ".rwsave")
        try {
            outside.writeText("outside save")
            val nested = save(root.resolve("nested").also { it.mkdir() }, "nested.rwsave")
            for (file in listOf(outside, nested)) {
                assertFailsWith<IllegalArgumentException> { deleteSavedGameFile(SavedGameFile(file), root) }
                assertFailsWith<IllegalArgumentException> { renameSavedGameFile(SavedGameFile(file), "bad", root) }
                assertTrue(file.exists())
            }
        } finally { outside.delete() }
    }

    @Test
    fun importCopiesMapAndAutomaticallyKeepsBothSameNameSaves() = withRoot { root ->
        val sourceDir = createTempDirectory("save-source").toFile()
        try {
            val source = save(sourceDir, "version 1.15.rwsave")
            sourceDir.resolve(source.name + ".map").writeText("map")
            val first = importSavedGameFile(source, root)
            val second = importSavedGameFile(source, root)
            assertEquals("version 1.15.rwsave", first.name)
            assertEquals("version 1.15 (2).rwsave", second.name)
            assertEquals(source.readBytes().toList(), second.file.readBytes().toList())
            assertEquals("map", root.resolve(second.name + ".map").readText())
            assertEquals(2, scanSavedGameFiles(root).size)
            assertTrue(root.listFiles()!!.none { it.name.endsWith(".tmp2") })
            assertTrue(source.exists())
        } finally { sourceDir.deleteRecursively() }
    }

    @Test
    fun importRejectsUnrelatedFileAndDoesNotDuplicateSameDirectorySource() = withRoot { root ->
        val junk = root.resolve("junk.txt").also { it.writeText("junk") }
        assertFailsWith<IllegalArgumentException> { importSavedGameFile(junk, root) }
        val save = save(root, "same.rwsave")
        assertEquals(save.canonicalFile, importSavedGameFile(save, root).file)
        assertEquals(1, scanSavedGameFiles(root).size)
    }

    @Test
    fun interruptedImportLeavesOriginalSaveAndNoPublishedOrTemporaryFiles() = withRoot { root ->
        val sourceDir = createTempDirectory("save-source").toFile()
        try {
            val source = save(sourceDir, "cancelled.rwsave")
            val before = source.readBytes().toList()
            sourceDir.resolve(source.name + ".map").writeText("map")
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<InterruptedIOException> { importSavedGameFile(source, root) }
            } finally {
                Thread.interrupted()
            }
            assertEquals(before, source.readBytes().toList())
            assertEquals("map", sourceDir.resolve(source.name + ".map").readText())
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { sourceDir.deleteRecursively() }
    }

    @Test
    fun androidListsBothBackendsAndLoadsSameNamePrivateSaveUsingEngineTag() = withRoot { root ->
        val primary = root.resolve("public/saves").also { it.mkdirs() }
        val secondary = root.resolve("private/saves").also { it.mkdirs() }
        save(primary, "same.rwsave").setLastModified(1_000L)
        val privateSave = save(secondary, "same.rwsave").also { it.setLastModified(2_000L) }
        val files = scanSavedGameFilesFromContext(context(primary.parentFile, secondary.parentFile))
        assertEquals(listOf(privateSave, primary.resolve("same.rwsave")), files.map { it.file })
        assertEquals(listOf("[INTERNAL-PATH]/same.rwsave", "same.rwsave"), files.map { it.toGameMap(0).mapName })
        assertEquals("same", files.first().displayName())
        assertEquals(privateSave.readBytes().toList(), files.first().toGameMap(0).openInputStream().use { it.readBytes().toList() })
        val renamedPrivate = renameSavedGameFile(files.first(), "private renamed", secondary)
        assertEquals("[INTERNAL-PATH]/private renamed.rwsave", renamedPrivate.toGameMap(0).mapName)
        assertTrue(primary.resolve("same.rwsave").exists())
        deleteSavedGameFile(renamedPrivate, secondary)
        assertFalse(renamedPrivate.file.exists())
    }

    @Test
    fun desktopOnlyScansPrimaryAndMatchingBackendRootsDoNotDuplicate() = withRoot { root ->
        val primary = root.resolve("public/saves").also { it.mkdirs() }
        val secondary = root.resolve("private/saves").also { it.mkdirs() }
        save(primary, "public.rwsave")
        save(secondary, "private.rwsave")
        assertEquals(listOf("public.rwsave"), scanSavedGameFilesFromContext(
            context(primary.parentFile, secondary.parentFile, desktop = true),
        ).map { it.name })
        assertEquals(1, scanSavedGameFilesFromContext(context(primary.parentFile, primary.parentFile)).size)
    }

    private fun context(primaryRoot: File, secondaryRoot: File, desktop: Boolean = false): AppContext =
        Proxy.newProxyInstance(AppContext::class.java.classLoader, arrayOf(AppContext::class.java)) { _, method, args ->
            when (method.name) {
                "externalStoragePath" -> File(primaryRoot, args!![0] as String).path
                "internalStoragePath" -> File(secondaryRoot, args!![0] as String).path
                "isDesktop" -> desktop
                else -> error("Unexpected AppContext call: ${method.name}")
            }
        } as AppContext

    private fun save(root: File, name: String): File = root.resolve(name).also {
        DataOutputStream(it.outputStream()).use { out ->
            out.writeUTF("rustedWarfareSave")
            out.writeInt(176)
        }
    }

    private inline fun withRoot(block: (File) -> Unit) {
        val root = createTempDirectory("save-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
