/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import io.github.rwpp.game.mod.heap.ModHeapEstimator
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.*
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse

class HeapWindowTest {
    @Test
    fun filteringUsesLiteralTextAndMemorySortIsNumeric() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        SwingUtilities.invokeAndWait {
            val window = HeapWindow()
            try {
                val estimate = ModHeapEstimator.estimateSources("pack", listOf(
                    "small.ini" to "[core]\nname=Unit[1]",
                    "large.ini" to "[core]\nname=Heavy\nif=select(self.hp > 10, memory.x, memory.y)",
                ))
                window.showEstimate(File("pack.rwmod"), estimate)
                val components = descendants(window.contentPane)
                val table = components.filterIsInstance<JTable>().single()
                val filter = components.filterIsInstance<JTextField>().single { it.toolTipText == null }
                assertEquals("Heavy", table.getValueAt(0, 0))
                filter.text = "[1]"
                assertEquals(1, window.visibleUnitCount)
                assertEquals("Unit[1]", table.getValueAt(0, 0))
                filter.text = "LARGE.INI"
                assertEquals(1, window.visibleUnitCount)
                assertEquals("Heavy", table.getValueAt(0, 0))
            } finally { window.dispose() }
        }
    }

    @Test
    fun invalidArchiveDoesNotPreventOtherInputsFromCompleting() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val directory = createTempDirectory("heap-ui-batch").toFile()
        var window: HeapWindow? = null
        try {
            val bad = File(directory, "bad.rwmod").apply { writeText("not a zip") }
            val good = File(directory, "good.ini").apply { writeText("[core]\nname=Scout") }
            SwingUtilities.invokeAndWait { window = HeapWindow().apply { analyze(listOf(bad, good)) } }
            val deadline = System.nanoTime() + 10_000_000_000L
            var finished = false
            while (!finished && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait {
                    finished = descendants(window!!.contentPane).filterIsInstance<JLabel>()
                        .any { it.text == "分析完成：1 个成功，1 个失败" }
                }
                if (!finished) Thread.sleep(25)
            }
            assertTrue(finished, "批量分析未及时完成")
            SwingUtilities.invokeAndWait {
                val list = descendants(window!!.contentPane).filterIsInstance<JList<*>>().single()
                assertEquals(2, list.model.size)
                assertTrue(list.model.getElementAt(0).toString().contains("失败"))
                list.selectedIndex = 1
                assertEquals(1, window!!.visibleUnitCount)
                val report = descendants(window!!.contentPane).filterIsInstance<JTextArea>().first()
                assertTrue(report.text.contains("Scout"))
            }
        } finally {
            SwingUtilities.invokeAndWait { window?.dispose() }
            directory.deleteRecursively()
        }
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
