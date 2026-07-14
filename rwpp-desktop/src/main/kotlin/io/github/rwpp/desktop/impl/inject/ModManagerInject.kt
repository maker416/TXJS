/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl.inject

import com.corrodinggames.rts.gameFramework.i.a
import com.corrodinggames.rts.gameFramework.i.b
import io.github.rwpp.game.mod.ModReloadSelection
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode

/**
 * `i.a.a(boolean, boolean)` 会先调用 `k()` 扫描目录，再开始解析启用模组的单位。
 * 必须在 `k()` 返回后应用状态，本次扫描中新建的模组才能在单位解析前被禁用。
 */
@InjectClass(a::class)
object ModManagerInject {
    @Inject("k", InjectMode.InsertAfter)
    @Suppress("UNCHECKED_CAST")
    fun a.applyReloadSelectionAfterScan() {
        val mods = a::class.java.getDeclaredField("e").run {
            isAccessible = true
            get(this@applyReloadSelectionAfterScan) as ArrayList<b>
        }
        mods.forEach { mod ->
            val candidates = buildList {
                runCatching { mod.h() }.getOrNull()?.let { add(it) }
                runCatching { b::class.java.getField("e").get(mod) as? String }
                    .getOrNull()
                    ?.let { add(it) }
                runCatching { b::class.java.getField("o").get(mod) as? String }
                    .getOrNull()
                    ?.let { add(it) }
            }
            ModReloadSelection.resolve(candidates)?.let { enabled ->
                mod.f = !enabled
            }
        }
    }
}
