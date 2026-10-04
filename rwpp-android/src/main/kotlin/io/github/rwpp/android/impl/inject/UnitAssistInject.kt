/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl.inject

import android.graphics.Paint
import android.graphics.RectF
import com.corrodinggames.rts.game.units.ce
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.game.InGameAssistRenderer
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.game.ui.InGameAssistRules
import io.github.rwpp.game.units.GameUnit
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.RedirectMethod

@InjectClass(ce::class)
object UnitAssistInject {
    private val settings by lazy { appKoin.get<Settings>() }
    private val healthPaint by lazy { Paint() }

    // 原版仅在单位渲染阶段调用 a(FZ)V；相机缩放由引擎维护，禁止再手动缩放。
    @Inject("a", InjectMode.InsertAfter, "(FZ)V")
    fun ce.drawAssist(delta: Float, simplified: Boolean) {
        if (KeepConnectedReload.active) return
        if (!settings.showBuildingAttackRange && settings.showAttackRangeUnit == "Never" &&
            !settings.showUnitTargetLine && !(this as GameUnit).attackRangeHighlighted) return
        val engine = GameEngine.t()
        if (bX || cP != null || engine.bp == null || !d(engine.bp)) return
        val target = (this as? com.corrodinggames.rts.game.units.bp)?.T
        InGameAssistRenderer.drawUnit(this as GameUnit, target != null && target.d(engine.bp))
    }

    @RedirectMethod("a", "(FZ)V", "com.corrodinggames.rts.gameFramework.m.fi", "a")
    fun ce.drawHealthBar(rect: RectF, paint: Paint) {
        val original = paint.color
        val color = if (settings.improvedHealthBar)
            InGameAssistRules.healthBarColor(original, cw, cx) else original
        val drawPaint = if (color == original) paint else {
            // 引擎的 Paint 是缓存共享对象，不能在上面直接改颜色。
            healthPaint.set(paint)
            healthPaint.color = color
            healthPaint
        }
        GameEngine.t().bL.a(rect, drawPaint)
    }
}
