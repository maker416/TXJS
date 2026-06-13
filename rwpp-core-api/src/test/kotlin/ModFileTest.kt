/*
 * Copyright 2023-2025 RWPP contributors
 * 姝ゆ簮浠ｇ爜鐨勪娇鐢ㄥ彈 GNU AFFERO GENERAL PUBLIC LICENSE version 3 璁稿彲璇佺殑绾︽潫, 鍙互鍦ㄤ互涓嬮摼鎺ユ壘鍒拌璁稿彲璇?
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.ModSourceType
import io.github.rwpp.game.mod.deleteModFileSafely
import io.github.rwpp.game.mod.detectModSourceType
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModFileTest {
    @Test
    fun deleteModFileSafelyDeletesFolderInsideAllowedRoot() {
        val root = createTempDirectory().toFile()
        val modFolder = root.resolve("brokenMod")
        val child = modFolder.resolve("units/test.ini")
        child.parentFile.mkdirs()
        child.writeText("[core]\n")

        assertTrue(deleteModFileSafely(modFolder, allowedRoots = listOf(root)))
        assertFalse(modFolder.exists())
    }

    @Test
    fun deleteModFileSafelyRejectsPathsOutsideAllowedRoot() {
        val root = createTempDirectory().toFile()
        val outside = createTempDirectory().toFile().resolve("outside.rwmod")
        outside.writeText("data")

        assertFalse(deleteModFileSafely(outside, allowedRoots = listOf(root)))
        assertTrue(outside.exists())
    }

    @Test
    fun deleteModFileSafelyRejectsAllowedRootItself() {
        val root = createTempDirectory().toFile()

        assertFalse(deleteModFileSafely(root, allowedRoots = listOf(root)))
        assertTrue(root.exists())
    }

    @Test
    fun detectModSourceTypeIdentifiesSupportedSources() {
        val root = createTempDirectory().toFile()
        val rwmod = root.resolve("a.rwmod").apply { writeText("data") }
        val ini = root.resolve("b.ini").apply { writeText("[core]\n") }
        val folder = root.resolve("folder").apply { mkdirs() }
        val unknown = root.resolve("notes.txt").apply { writeText("data") }

        assertEquals(ModSourceType.RwMod, detectModSourceType(rwmod))
        assertEquals(ModSourceType.Ini, detectModSourceType(ini))
        assertEquals(ModSourceType.Folder, detectModSourceType(folder))
        assertEquals(ModSourceType.Unknown, detectModSourceType(unknown))
    }
}
