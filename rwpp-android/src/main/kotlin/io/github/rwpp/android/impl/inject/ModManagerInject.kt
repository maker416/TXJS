/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl.inject

import com.corrodinggames.rts.gameFramework.i.a
import com.corrodinggames.rts.gameFramework.i.b
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.game.mod.ModReloadSelection
import io.github.rwpp.game.mod.PENDING_MOD_STATES_GROUP
import io.github.rwpp.game.mod.PENDING_MOD_STATES_KEY
import io.github.rwpp.game.mod.decodeEnabledStates
import io.github.rwpp.game.mod.resolveModEnabledByFileName
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `i.a.a(boolean, boolean)` 会先调用 `j()` 扫描目录，再开始解析启用模组的单位。
 * 必须在 `j()` 返回后应用状态，本次扫描中新建的模组才能在单位解析前被禁用。
 *
 * 受控重载（modReload/modSaveChange）经 [ModReloadSelection] 传入状态；
 * 此外还回退读取一次性的持久化状态（"重启式特殊加载"在杀进程前写入），
 * 使冷启动扫描也能应用用户离开模组页时的开关，首个扫描消费后即清除。
 */
@InjectClass(a::class)
object ModManagerInject {
    /** 持久化状态每个进程只消费一次。 */
    private val pendingConsumed = AtomicBoolean(false)

    @Inject("j", InjectMode.InsertAfter)
    @Suppress("UNCHECKED_CAST")
    fun a.applyReloadSelectionAfterScan() {
        val mods = e as ArrayList<b>
        val pending = consumePendingEnabledStates()
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
            val enabled = ModReloadSelection.resolve(candidates)
                ?: pending?.let { resolveModEnabledByFileName(candidates, it) }
            enabled?.let { mod.f = !it }
        }
    }

    /**
     * 读取并清除一次性的持久化启用状态；没有则返回 null。
     * 与 [ModReloadSelection] 互补：后者服务进程内受控重载，前者服务跨重启的冷启动。
     */
    private fun consumePendingEnabledStates(): Map<String, Boolean>? {
        if (!pendingConsumed.compareAndSet(false, true)) return null
        val configIO = runCatching { appKoin.get<ConfigIO>() }.getOrNull() ?: return null
        val states = decodeEnabledStates(
            runCatching { configIO.readSingleConfig(PENDING_MOD_STATES_GROUP, PENDING_MOD_STATES_KEY) }.getOrNull()
        )
        // 一次性语义：读后立即清空（写空白串，读取端 ifBlank 视为 null）
        runCatching { configIO.saveSingleConfig(PENDING_MOD_STATES_GROUP, PENDING_MOD_STATES_KEY, "") }
        return states.takeIf { it.isNotEmpty() }
    }
}
