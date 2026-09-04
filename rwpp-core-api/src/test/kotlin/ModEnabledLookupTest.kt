/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.ModReloadSelection
import io.github.rwpp.game.mod.decodeEnabledStates
import io.github.rwpp.game.mod.encodeEnabledStates
import io.github.rwpp.game.mod.resolveModEnabledByFileName
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

    @Test
    fun enabledStatesEncodeDecodeRoundTrip() {
        val states = mapOf(
            "安绒宁静.rwmod" to true,
            "SuperWeaponPack.rwmod" to false,
            "a b c.rwmod" to true,
        )
        val decoded = decodeEnabledStates(encodeEnabledStates(states))
        assertEquals(
            mapOf(
                "安绒宁静.rwmod" to true,
                "superweaponpack.rwmod" to false,
                "a b c.rwmod" to true,
            ),
            decoded,
        )
    }

    @Test
    fun enabledStatesDecodeToleratesGarbage() {
        assertEquals(emptyMap(), decodeEnabledStates(null))
        assertEquals(emptyMap(), decodeEnabledStates(""))
        assertEquals(emptyMap(), decodeEnabledStates("   "))
        // 畸形条目被丢弃，合法条目保留
        assertEquals(
            mapOf("ok.rwmod" to true),
            decodeEnabledStates("badentry;=;x=2;;ok.rwmod=1"),
        )
    }

    @Test
    fun enabledStatesEncodeDropsSeparatorInFileName() {
        assertEquals("", encodeEnabledStates(mapOf("a;b.rwmod" to true)))
        assertEquals("", encodeEnabledStates(mapOf("a=b.rwmod" to true)))
    }
}
