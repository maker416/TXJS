/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModReloadSelection
import io.github.rwpp.game.mod.SelectableMod
import io.github.rwpp.game.mod.requireModSelectionApplied
import io.github.rwpp.game.mod.snapshotModSelection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModSelectionTest {
    @Test
    fun enabledImportedFileMissingFromEngineScanIsReportedInsteadOfSilentlyUnchecked() {
        val imported = engineMod("units/imported.rwmod", enabled = true)
        assertFailsWith<IllegalStateException> {
            requireModSelectionApplied(emptyList(), snapshotModSelection(listOf(imported)))
        }
    }

    @Test
    fun pendingSelectionSurvivesEngineStateResetAndDoesNotChangeLoadedUnits() {
        val engine = engineMod("builtin_mods/mega_builders", enabled = false)
        val pending = SelectableMod(engine)
        pending.isEnabled = true
        assertFalse(engine.isEnabled)
        engine.isEnabled = false // 扫描/加载中的禁用字段不能撤销页面选择。
        val selection = snapshotModSelection(listOf(pending))
        assertTrue(selection.getValue(File(engine.path).canonicalPath))
        pending.isEnabled = false // 点击重载之后的操作不能修改已提交的快照。
        assertTrue(selection.getValue(File(engine.path).canonicalPath))
    }

    @Test
    fun sameFilenameInDifferentDirectoriesKeepsIndependentSelections() {
        val selected = engineMod("selected/demo.rwmod", enabled = true)
        val disabled = engineMod("other/demo.rwmod", enabled = false)
        val selection = snapshotModSelection(listOf(selected, disabled))
        assertTrue(selection.getValue(File(selected.path).canonicalPath))
        assertFalse(selection.getValue(File(disabled.path).canonicalPath))
        requireModSelectionApplied(listOf(selected, disabled), selection)
    }

    @Test
    fun reloadCannotPretendDisabledModWasLoadedByRewritingSwitchAfterParsing() {
        val engine = engineMod("builtin_mods/mega_builders", enabled = false)
        val pending = SelectableMod(engine).apply { isEnabled = true }
        val selection = snapshotModSelection(listOf(pending))
        assertFailsWith<IllegalStateException> {
            requireModSelectionApplied(listOf(engine), selection)
        }
        assertFalse(engine.isEnabled)
        assertTrue(pending.isEnabled)
        engine.isEnabled = true
        requireModSelectionApplied(listOf(engine), selection)
    }

    @Test
    fun assetSelectionSurvivesScanWithoutEnablingExternalModWithSameName() {
        val builtin = engineMod("builtin_mods/mega_builders", enabled = false)
        val external = engineMod("/storage/emulated/0/builtin_mods/mega_builders", enabled = false)
        val pending = SelectableMod(builtin).apply { isEnabled = true }
        val selection = snapshotModSelection(listOf(pending, external))

        ModReloadSelection.activate(selection)
        try {
            builtin.isEnabled = ModReloadSelection.resolve(listOf(builtin.path))!!
            external.isEnabled = ModReloadSelection.resolve(listOf(external.path))!!
            assertTrue(builtin.isEnabled)
            assertFalse(external.isEnabled)
            requireModSelectionApplied(listOf(builtin, external), selection)
        } finally {
            ModReloadSelection.deactivate()
        }

        pending.isEnabled = false
        ModReloadSelection.activate(snapshotModSelection(listOf(pending, external)))
        try {
            assertFalse(ModReloadSelection.resolve(listOf(builtin.path))!!)
        } finally {
            ModReloadSelection.deactivate()
        }
    }

    private fun engineMod(path: String, enabled: Boolean): Mod = object : Mod {
        override val id = 1
        override val name = File(path).name
        override val description = ""
        override val minVersion = ""
        override val errorMessage: String? = null
        override var isEnabled = enabled
        override val path = path
        override fun getRamUsed() = "0"
        override fun getSize() = 0L
        override fun getBytes() = byteArrayOf()
    }
}
