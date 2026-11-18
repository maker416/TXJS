/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl.inject

import android.graphics.Paint
import android.graphics.RectF
import com.corrodinggames.rts.game.units.am
import com.corrodinggames.rts.game.units.y
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.desktop.GameEngine
import io.github.rwpp.game.InGameAssistRenderer
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.game.ui.InGameAssistRules
import io.github.rwpp.game.units.GameUnit
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.RedirectMethod

@InjectClass(am::class)
object UnitAssistInject {
    private val settings by lazy { appKoin.get<Settings>() }
    private val healthPaint by lazy { Paint() }

    // 原版仅在单位渲染阶段调用 a(FZ)V；相机缩放由引擎维护，禁止再手动缩放。
    @Inject("a", InjectMode.InsertAfter, "(FZ)V")
    fun am.drawAssist(delta: Float, simplified: Boolean) {
        if (KeepConnectedReload.active) return
        val config = settings
        val unit = this as GameUnit
        val range = config.showBuildingAttackRange || config.showAttackRangeUnit != "Never" || unit.attackRangeHighlighted
        drawVisibleAssists(
            range, config.showUnitTargetLine,
            visibleViewer = { if (bV || cN != null) null else GameEngine.B().bs?.takeIf { d(it) } },
            targetVisible = { viewer -> (this as? y)?.R?.d(viewer) == true },
            draw = { targetVisible -> InGameAssistRenderer.drawUnit(unit, targetVisible) },
        )
    }

    @RedirectMethod("a", "(FZ)V", "com.corrodinggames.rts.gameFramework.m.y", "a")
    fun am.drawHealthBar(rect: RectF, paint: Paint) {
        val drawPaint = if (!settings.improvedHealthBar) paint else {
            val original = paint.e()
            val color = InGameAssistRules.healthBarColor(original, cu, cv)
            // 引擎的 Paint 是缓存共享对象，不能在上面直接改颜色。
            if (color == original) paint else healthPaint.apply { a(paint); b(color) }
        }
        GameEngine.B().bO.a(rect, drawPaint)
    }
}

// d(viewer) 可能查迷雾格。只在这次绘制需要时查询，不跨单位或帧缓存可见性。
internal inline fun <V : Any> drawVisibleAssists(
    range: Boolean, targetLine: Boolean,
    visibleViewer: () -> V?, targetVisible: (V) -> Boolean, draw: (Boolean) -> Unit,
) {
    if (!range && !targetLine) return
    val viewer = visibleViewer() ?: return
    val visibleTarget = targetLine && targetVisible(viewer)
    if (range || visibleTarget) draw(visibleTarget)
}
