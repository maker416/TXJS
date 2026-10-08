/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import java.awt.Canvas
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeCanvasCardLayoutTest {
    @Test fun repeatedLayoutNeverResetsNativeCanvasToLogicalPixels() = onEdt {
        val canvas = RecordingCanvas()
        val menu = JPanel()
        val layout = NativeCanvasCardLayout(canvas) { 1.5 }
        val host = JPanel(layout).apply {
            add(menu, "menu")
            add(canvas, "game")
            setSize(800, 600)
        }

        repeat(8) { host.doLayout() }

        assertEquals(Dimension(800, 600), menu.size)
        assertEquals(Dimension(1200, 900), canvas.size)
        assertEquals(listOf(Dimension(1200, 900)), canvas.resizes)
        layout.show(host, "game")
        host.doLayout()
        assertTrue(canvas.isVisible)
        assertFalse(menu.isVisible)
        layout.show(host, "menu")
        host.doLayout()
        assertTrue(menu.isVisible)
        assertEquals(listOf(Dimension(1200, 900)), canvas.resizes)
    }

    @Test fun resizeAndMonitorScaleUseContainerSizeOnce() = onEdt {
        var scale = 2.0
        val canvas = RecordingCanvas()
        val menu = JPanel()
        val host = JPanel(NativeCanvasCardLayout(canvas) { scale }).apply {
            border = BorderFactory.createEmptyBorder(10, 20, 30, 40)
            add(menu, "menu")
            add(canvas, "game")
            setSize(860, 640)
        }

        host.doLayout()
        scale = 1.25
        host.doLayout()
        host.setSize(1060, 840)
        host.doLayout()

        assertEquals(Dimension(1000, 800), menu.size)
        assertEquals(Dimension(1250, 1000), canvas.size)
        assertEquals(listOf(Dimension(1600, 1200), Dimension(1000, 750), Dimension(1250, 1000)), canvas.resizes)
        assertEquals(20, canvas.x)
        assertEquals(10, canvas.y)
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private class RecordingCanvas : Canvas() {
        val resizes = mutableListOf<Dimension>()

        override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
            if (this.width != width || this.height != height) resizes += Dimension(width, height)
            super.setBounds(x, y, width, height)
        }
    }
}
