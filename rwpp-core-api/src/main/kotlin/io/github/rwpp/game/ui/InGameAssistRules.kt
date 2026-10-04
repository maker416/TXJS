/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.ui

import io.github.rwpp.game.units.MovementType

/** 两端共享的局内辅助规则；绘制与选择均由引擎线程调用。 */
enum class QuickSelectGroup(val labelKey: String) {
    ALL("all"), BUILDINGS("buildings"), SEA("sea"), AIR("air"), LAND("land");

    fun matches(movement: MovementType, isBuilder: Boolean): Boolean {
        val building = movement == MovementType.NONE || movement == MovementType.BUILDING
        if (this == BUILDINGS) return building
        if (building || isBuilder) return false
        return when (this) {
            ALL -> true
            SEA -> movement == MovementType.WATER || movement == MovementType.HOVER ||
                movement == MovementType.OVER_CLIFF_WATER
            AIR -> movement == MovementType.AIR
            LAND -> movement == MovementType.LAND || movement == MovementType.HOVER ||
                movement == MovementType.OVER_CLIFF || movement == MovementType.OVER_CLIFF_WATER
            BUILDINGS -> false
        }
    }
}

object InGameAssistRules {
    fun showRange(movement: MovementType, buildings: Boolean, units: String, highlighted: Boolean): Boolean =
        highlighted || when (movement) {
            MovementType.NONE, MovementType.BUILDING -> buildings
            MovementType.AIR -> units == "Air" || units == "All"
            MovementType.WATER -> units == "All"
            else -> units == "Land" || units == "All"
        }

    /** 只改原版 HP 填充颜色，不影响护盾、能量、建造进度，也不修改共享 Paint。 */
    fun healthBarColor(original: Int, health: Float, maxHealth: Float): Int {
        if (original != 0xC8B72C2C.toInt() && original != 0xC8009600.toInt()) return original
        if (!health.isFinite() || !maxHealth.isFinite() || maxHealth <= 0f) return original
        return when ((health / maxHealth).coerceIn(0f, 1f)) {
            in 0.6f..1f -> original
            in 0.3f..0.6f -> 0xC8ED9121.toInt()
            else -> 0xC8F04444.toInt()
        }
    }

    fun clockText(milliseconds: Long, ping: Int?): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        val minutes = seconds / 60
        val time = "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
        return if (ping != null && ping >= 0) "$time  ${ping}ms" else time
    }
}

/** 屏幕坐标，避开右侧命令栏及底部编队条。过小的窗口直接隐藏，避免重叠。 */
data class AssistHudLayout(val left: Int, val top: Int, val width: Int, val height: Int, val gap: Int) {
    fun x(index: Int): Int = left + index * (width + gap)

    companion object {
        fun fit(viewportWidth: Float, viewportHeight: Float, uiScale: Float, count: Int,
                preferredWidth: Float = 42f): AssistHudLayout? {
            if (!viewportWidth.isFinite() || !viewportHeight.isFinite() || count <= 0) return null
            val scale = uiScale.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.5f, 3f) ?: 1f
            val gap = (4 * scale).toInt().coerceAtLeast(2)
            val width = (preferredWidth * scale).toInt().coerceAtLeast(24)
                .coerceAtMost(((viewportWidth - 16) / count).toInt() - gap)
            val height = (34 * scale).toInt().coerceAtLeast(20)
            val top = (viewportHeight - 78 * scale - height).toInt()
            if (width < 24 || top < height + 16) return null
            return AssistHudLayout(((viewportWidth - count * width - (count - 1) * gap) / 2).toInt(),
                top, width, height, gap)
        }
    }
}
