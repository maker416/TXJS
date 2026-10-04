/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl.inject

import com.corrodinggames.rts.game.units.ce
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.game.ui.AssistHudLayout
import io.github.rwpp.game.ui.InGameAssistRules
import io.github.rwpp.game.ui.QuickSelectGroup
import io.github.rwpp.game.units.GameUnit
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.inject.Accessor
import io.github.rwpp.inject.SetInterfaceOn
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.InterruptResult

private typealias AssistHud = com.corrodinggames.rts.gameFramework.f.a

/** 借用原版 HUD 的布局坐标、点击区域登记和 release 事件；不挂额外 View/Compose 层。 */
@InjectClass(AssistHud::class)
object InGameHudInject {
    private val settings by lazy { appKoin.get<Settings>() }
    private val game by lazy { appKoin.get<Game>() }
    private var labelLanguage: String? = null
    private var labels = emptyList<String>()
    private val labelKeys = QuickSelectGroup.entries.map { it.labelKey } +
        listOf("buildingRange", "unitRange", "selectedRange", "rangeNever", "rangeLand", "rangeAir", "rangeAll")
    private var lastSecond = Long.MIN_VALUE
    private var lastPing: Int? = null
    private var clockLabel = ""

    @Inject("e", InjectMode.InsertAfter, "(F)V")
    fun drawHud(delta: Float) {
        renderHud()
    }

    private fun renderHud() {
        if (KeepConnectedReload.active) return
        if (!settings.displayTimeInGame && !settings.enableQuickSelectMenu && !settings.showExtraButton) return
        val engine = GameEngine.t()
        val render = engine.bP
        if (engine.bp == null || render.u) return
        if (settings.displayTimeInGame && !engine.bY.g()) {
            val milliseconds = engine.bv.toLong()
            val ping = if (game.gameRoom.isConnecting) game.gameRoom.localPlayer.pingNumber else null
            val second = milliseconds / 1000
            if (second != lastSecond || ping != lastPing) {
                clockLabel = InGameAssistRules.clockText(milliseconds, ping)
                lastSecond = second
                lastPing = ping
            }
            engine.bL.a(clockLabel, engine.cC / 2f, render.aE.textSize + 7f, render.aE)
        }
        if (!settings.enableQuickSelectMenu && !settings.showExtraButton) return
        if (labelLanguage != settings.language) {
            labels = labelKeys.map { readI18n("inGameAssist.$it") }
            labelLanguage = settings.language
        }
        val viewport = engine.ci - engine.cn
        val layout = AssistHudLayout.fit(viewport, engine.cj, engine.cg, 5) ?: return
        val canSelect = !game.gameRoom.localPlayer.isSpectator && !engine.bY.g()
        if (settings.enableQuickSelectMenu && canSelect) {
            for (group in QuickSelectGroup.entries) {
                if (button(layout.x(group.ordinal), layout.top, layout.width, layout.height, labels[group.ordinal])) {
                    select(group)
                }
            }
        }
        if (settings.showExtraButton) {
            val extra = AssistHudLayout.fit(viewport, engine.cj, engine.cg, 3, 110f) ?: return
            val y = if (settings.enableQuickSelectMenu && canSelect) layout.top - extra.height - extra.gap else extra.top
            if (y < extra.height + 16) return
            val selected = game.world.selectedUnits
            if (button(extra.x(0), y, extra.width, extra.height, labels[5], settings.showBuildingAttackRange)) {
                settings.showBuildingAttackRange = !settings.showBuildingAttackRange
            }
            val rangeIndex = Settings.unitAttackRangeTypes.indexOf(settings.showAttackRangeUnit).coerceAtLeast(0)
            if (button(extra.x(1), y, extra.width, extra.height, "${labels[6]} ${labels[8 + rangeIndex]}", rangeIndex != 0)) {
                settings.showAttackRangeUnit = Settings.unitAttackRangeTypes[(rangeIndex + 1) % Settings.unitAttackRangeTypes.size]
            }
            if (selected.isNotEmpty() && button(extra.x(2), y, extra.width, extra.height, labels[7],
                    selected.all { it.attackRangeHighlighted })) {
                val enabled = !selected.all { it.attackRangeHighlighted }
                selected.forEach { it.attackRangeHighlighted = enabled }
            }
        }
    }

    private fun button(x: Int, y: Int, width: Int, height: Int, label: String, active: Boolean = false): Boolean {
        val render = GameEngine.t().bP
        val input = render as AssistInputAccess
        val background = if (active) 0xB0408050.toInt() else 0xA0323232.toInt()
        // 绘制与命中测试为原版同一调用，按下时登记区域，抬起消费 U，避免点穿成移动命令。
        val clicked = render.a(x, y, width, height, label, false, background, render.aC, false, null)
        if (!clicked || !input.released || input.dragging || render.ac != null) return false
        input.released = false
        return true
    }

    private fun select(group: QuickSelectGroup) {
        val engine = GameEngine.t()
        val render = engine.bP
        render.h()
        // 不投 Game.post：此处已在原版 HUD 回调的引擎线程，不能排队到下一帧。
        for (obj in game.world.getAllObject()) {
            val unit = obj as? ce ?: continue
            val api = unit as GameUnit
            if (unit.bZ !== engine.bp || api.isDead || !(unit.cP == null && unit.co >= 1f && com.corrodinggames.rts.gameFramework.f.i.a(unit))) continue
            if (group.matches(api.type.movementType, api.type.isBuilder)) render.c(unit)
        }
    }
}

@InjectClass(com.corrodinggames.rts.game.units.a.f::class)
object GuardSizeInject {
    private val settings by lazy { appKoin.get<Settings>() }
    @Inject("l", InjectMode.InsertBefore, "()F")
    fun size(): Any = if (settings.enableLargerKeys) InterruptResult(1f) else Unit
}

@InjectClass(com.corrodinggames.rts.game.units.a.i::class)
object PatrolSizeInject {
    private val settings by lazy { appKoin.get<Settings>() }
    @Inject("l", InjectMode.InsertBefore, "()F")
    fun size(): Any = if (settings.enableLargerKeys) InterruptResult(1f) else Unit
}


@SetInterfaceOn([com.corrodinggames.rts.gameFramework.f.i::class])
interface AssistInputAccess {
    @Accessor("U")
    var released: Boolean
    @Accessor("T")
    val dragging: Boolean
}
