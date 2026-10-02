/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.ModReloadSelection
import io.github.rwpp.game.mod.resolveModEnabledByFileName
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModEnabledLookupTest {
    @Test
    fun matchesFileNameCaseInsensitive() {
        assertFalse(
            resolveModEnabledByFileName(
                listOf("/units/Demo.rwmod"),
                mapOf("demo.rwmod" to false),
            )
        )
        assertTrue(
            resolveModEnabledByFileName(
                listOf("/units/Demo.rwmod"),
                mapOf("demo.rwmod" to true),
            )
        )
    }

    @Test
    fun doesNotMatchFilenameSuffixFalsePositive() {
        // pack.rwmod 不得误匹配 SuperWeaponPack.rwmod
        assertFalse(
            resolveModEnabledByFileName(
                listOf("/units/SuperWeaponPack.rwmod"),
                mapOf("pack.rwmod" to true),
            )
        )
        assertTrue(
            resolveModEnabledByFileName(
                listOf("/units/SuperWeaponPack.rwmod"),
                mapOf("superweaponpack.rwmod" to true),
            )
        )
    }

    @Test
    fun unknownFileDefaultsToDisabled() {
        assertFalse(
            resolveModEnabledByFileName(
                listOf("/units/other.rwmod"),
                mapOf("pack.rwmod" to true),
            )
        )
    }

    @Test
    fun fullPathSelectsOnlyOneOfIdenticalFileNamesInDifferentDirectories() {
        val selected = File("selected/demo.rwmod").canonicalFile
        val stale = File("stale/demo.rwmod").canonicalFile
        val selection = mapOf(selected.path to true, stale.path to false)
        assertTrue(resolveModEnabledByFileName(listOf(selected.path), selection))
        assertFalse(resolveModEnabledByFileName(listOf(stale.path), selection))
        assertFalse(resolveModEnabledByFileName(listOf(File("unknown/demo.rwmod").path), selection))
    }

    @Test
    fun fullPathTakesPriorityOverConflictingFilenameFallback() {
        val selected = File("selected/demo.rwmod").canonicalFile
        val selection = mapOf(selected.path to true, "demo.rwmod" to false)
        assertTrue(resolveModEnabledByFileName(listOf("demo.rwmod", selected.path), selection))
    }

    @Test
    fun fullPathCaseFollowsPlatformWhileFilenameFallbackStaysCaseInsensitive() {
        val selected = File("selected/Demo.rwmod").canonicalFile
        val other = File("selected/demo.rwmod").canonicalFile
        assertEquals(
            File.separatorChar == '\\',
            resolveModEnabledByFileName(listOf(other.path), mapOf(selected.path to true)),
        )
        assertTrue(resolveModEnabledByFileName(listOf(other.path), mapOf("DEMO.RWMOD" to true)))
    }

    @Test
    fun reloadSelectionIsOnlyActiveInsideControlledReload() {
        assertNull(ModReloadSelection.resolve(listOf("/units/demo.rwmod")))

        ModReloadSelection.activate(
            mapOf(
                "demo.rwmod" to true,
                "disabled.rwmod" to false,
            )
        )
        try {
            assertTrue(ModReloadSelection.resolve(listOf("/units/Demo.rwmod"))!!)
            assertFalse(ModReloadSelection.resolve(listOf("/units/disabled.rwmod"))!!)
            assertFalse(ModReloadSelection.resolve(listOf("/units/not-listed.rwmod"))!!)
        } finally {
            ModReloadSelection.deactivate()
        }

        assertNull(ModReloadSelection.resolve(listOf("/units/demo.rwmod")))
    }
}
