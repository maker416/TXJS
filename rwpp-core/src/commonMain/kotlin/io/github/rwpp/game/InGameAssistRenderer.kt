/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game

import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.base.BaseFactory
import io.github.rwpp.game.base.GamePaint
import io.github.rwpp.game.ui.InGameAssistRules
import io.github.rwpp.game.units.GameUnit
import io.github.rwpp.ui.color.getTeamColor
import androidx.compose.ui.graphics.toArgb

/**
 * 在原版单位绘制回调中运行，沿用引擎相机变换与可见性判断。
 * 只画轮廓圈，不分配整屏位图/GL 纹理，不创建渲染线程或逐单位组件。
 */
@Suppress("DEPRECATION")
object InGameAssistRenderer {
    private val settings by lazy { appKoin.get<Settings>() }
    private val game by lazy { appKoin.get<Game>() }
    private val factory by lazy { appKoin.get<BaseFactory>() }
    private val rangePaints by lazy {
        Array(10) { factory.createPaint(Player.getTeamColor(it).copy(alpha = 0.55f).toArgb(), GamePaint.Style.STROKE) }
    }
    private val neutralPaint by lazy { factory.createPaint(0xA0AAAAAA.toInt(), GamePaint.Style.STROKE) }
    private val targetPaint by lazy { factory.createPaint(0xC8FFFFFF.toInt(), GamePaint.Style.STROKE) }

    /** 调用方必须先按原版 fog / transport / dead 规则过滤。 */
    fun drawUnit(unit: GameUnit, targetVisible: Boolean) {
        val drawRange = settings.showBuildingAttackRange || settings.showAttackRangeUnit != "Never" || unit.attackRangeHighlighted
        if (!drawRange && !settings.showUnitTargetLine) return
        val world = game.world
        val x = unit.x - world.cameraX
        val y = unit.y - world.cameraY
        if (!x.isFinite() || !y.isFinite()) return
        if (drawRange && InGameAssistRules.showRange(unit.type.movementType, settings.showBuildingAttackRange,
                settings.showAttackRangeUnit, unit.attackRangeHighlighted)) {
            val range = unit.maxAttackRange
            if (range.isFinite() && range > 0f) {
                val team = unit.player.team
                val paint = if (team >= 0) rangePaints[team % 10] else neutralPaint
                world.drawCircle(x, y, range, paint)
            }
        }
        if (settings.showUnitTargetLine && targetVisible &&
            (game.gameRoom.isSinglePlayerGame || unit.player.team == game.gameRoom.localPlayer.team)) {
            val target = unit.target
            if (target != null && !target.isDead && target.x.isFinite() && target.y.isFinite()) {
                world.drawLine(x, y, target.x - world.cameraX, target.y - world.cameraY, targetPaint)
            }
        }
    }
}
