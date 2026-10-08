/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import java.awt.Canvas
import java.awt.CardLayout
import java.awt.Container

/**
 * Compose 使用逻辑尺寸，LWJGL2 的嵌入式 Canvas 则需要物理像素尺寸。
 * 在同一次布局中直接给出最终尺寸，避免 CardLayout 与 componentResized 互相改写尺寸。
 */
internal class NativeCanvasCardLayout(
    private val gameCanvas: Canvas,
    private val dpiScale: () -> Double
) : CardLayout() {
    override fun layoutContainer(parent: Container) {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            val width = (parent.width - insets.left - insets.right - hgap * 2).coerceAtLeast(0)
            val height = (parent.height - insets.top - insets.bottom - vgap * 2).coerceAtLeast(0)
            val scale = dpiScale().takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            var hasVisibleCard = false
            for (component in parent.components) {
                val nativeCanvas = component === gameCanvas
                component.setBounds(
                    insets.left + hgap,
                    insets.top + vgap,
                    if (nativeCanvas) (width * scale).toInt() else width,
                    if (nativeCanvas) (height * scale).toInt() else height
                )
                hasVisibleCard = hasVisibleCard || component.isVisible
            }
            if (!hasVisibleCard && parent.componentCount > 0) {
                parent.getComponent(0).isVisible = true
            }
        }
    }
}
