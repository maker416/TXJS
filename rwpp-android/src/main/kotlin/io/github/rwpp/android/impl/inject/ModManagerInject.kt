/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl.inject

import com.corrodinggames.rts.gameFramework.i.a
import com.corrodinggames.rts.gameFramework.i.b
import io.github.rwpp.android.impl.AndroidModSourceCache
import io.github.rwpp.game.mod.ModReloadSelection
import io.github.rwpp.game.mod.ContentProfiles
import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode

/**
 * `i.a.a(boolean, boolean)` 会先调用 `j()` 扫描目录，再开始解析启用模组的单位。
 * 必须在 `j()` 返回后应用状态，本次扫描中新建的模组才能在单位解析前被禁用。
 *
 * 受控重载（modReload/modSaveChange）经 [ModReloadSelection] 传入状态。
 */
@InjectClass(a::class)
object ModManagerInject {
    @Inject("j", InjectMode.InsertBefore)
    fun a.invalidateChangedModSources() {
        ContentProfiles.recoverBeforeFirstScan { snapshot ->
            GameEngine.t().bN.apply {
                modSettings = snapshot.modSettings; modSettingsVersion = snapshot.version
                lastModCount = snapshot.lastModCount; check(save()) { "Cannot restore ordinary Mod settings" }
            }
        }
        AndroidModSourceCache.instance.beforeScan(this)
        @Suppress("UNCHECKED_CAST")
        val previous = e as List<b>
        // Let native scanning discard entries belonging to a previous profile/version.
        previous.filter { it.e?.startsWith("rwjs-expansion:") == true }.forEach { it.k = false }
        ContentProfiles.expansionSources.forEach { source ->
            val key = "rwjs-expansion:" + (ContentProfiles.current as io.github.rwpp.game.mod.ContentProfile.Expansion).revision + ":" + source.id
            // Register before j() captures its list; k retains sources outside the ordinary directory.
            a(source.file.name, source.id, source.file.canonicalPath, key, true, false).k = true
        }
    }

    @Inject("d", InjectMode.InsertBefore, "()V")
    fun preserveSelectionDuringRecovery(): Any =
        if (io.github.rwpp.game.mod.ModLoadSafety.suppressSelectionSave || ContentProfiles.preserveSelection) io.github.rwpp.inject.InterruptResult.Unit else Unit


    @Inject("j", InjectMode.InsertAfter)
    @Suppress("UNCHECKED_CAST")
    fun a.applyReloadSelectionAfterScan() {
        AndroidModSourceCache.instance.afterScan(this)
        val mods = e as ArrayList<b>
        mods.forEach { mod ->
            val candidates = buildList {
                runCatching { mod.e() }.getOrNull()?.let { add(it) }
                runCatching { b::class.java.getField("e").get(mod) as? String }
                    .getOrNull()
                    ?.let { add(it) }
                runCatching { b::class.java.getField("n").get(mod) as? String }
                    .getOrNull()
                    ?.let { add(it) }
            }
            (ContentProfiles.resolve(candidates) ?: ModReloadSelection.resolve(candidates))?.let { mod.f = !it }
        }
        ContentProfiles.completeStartupScan()
    }
}
