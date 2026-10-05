/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import java.io.File

/** 页面上的待应用选择独立于引擎扫描使用的可变禁用字段，只有重载才写回引擎。 */
class SelectableMod(delegate: Mod) : Mod by delegate {
    override var isEnabled: Boolean = delegate.isEnabled
}

/** 使用原始磁盘路径区分不同目录的同名模组，重载期间不再读取可变引擎状态。 */
fun snapshotModSelection(mods: List<Mod>): Map<String, Boolean> =
    mods.associate { File(it.path).canonicalPath to it.isEnabled }

/** 校验实际解析使用的选择；不能在解析结束后补写开关来伪装加载成功。 */
fun requireModSelectionApplied(mods: List<Mod>, selection: Map<String, Boolean>) {
    val mismatches = mods.filter {
        it.isEnabled != resolveModEnabledByFileName(listOf(it.path), selection)
    }
    val missing = selection.filter { (path, enabled) ->
        enabled && mods.none { resolveModEnabledByFileName(listOf(it.path), mapOf(path to true)) }
    }.keys
    check(mismatches.isEmpty() && missing.isEmpty()) {
        "Mod reload selection was not applied: " +
            (mismatches.map { it.name } + missing.map { File(it).name }).joinToString()
    }
}
