/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

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
            val window = HeapWindow(initialGameRoot = File("."), analyzer = { _, _, _ -> error("本测试直接展示实测结果") })
            try {
                val measurement = measured(listOf(
                    MeasuredUnitHeap("Unit[1]", "small.ini", 9),
                    MeasuredUnitHeap("Heavy", "large.ini", 100),
                ))
                window.showMeasurement(File("pack.rwmod"), measurement)
                val components = descendants(window.contentPane)
                val table = components.filterIsInstance<JTable>().single()
                val filter = components.filterIsInstance<JTextField>().single { it.toolTipText == null }
                assertEquals(3, table.columnCount)
                assertEquals("Heavy", table.getValueAt(0, 0))
                assertEquals(100L, table.getValueAt(0, 2))
                assertTrue(components.filterIsInstance<JLabel>().any { it.text.startsWith("单位间共享对象堆：") })
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
            val good = File(directory, "good.rwmod").apply { writeText("测试测量由注入回调提供") }
            SwingUtilities.invokeAndWait {
                window = HeapWindow(initialGameRoot = directory, analyzer = { file, gameRoot, progress ->
                    assertEquals(directory, gameRoot)
                    progress("真实核心加载：${file.name}")
                    if (file == bad) error("真实核心拒绝无效模组")
                    measured(listOf(MeasuredUnitHeap("Scout", file.name, 2048)))
                }).apply { analyze(listOf(bad, good)) }
            }
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

    private fun measured(units: List<MeasuredUnitHeap>) = MeasuredModHeap(
        sourceName = "pack",
        baselineHeapBytes = 1_000_000,
        loadedHeapBytes = 1_200_000,
        sampledPeakHeapBytes = 1_500_000,
        heapLimitBytes = 768 * 1024 * 1024,
        definitionHeapBytes = units.sumOf { it.exclusiveBytes } + 128,
        sharedDefinitionHeapBytes = 128,
        textureAccountedBytes = 32_768,
        soundAccountedBytes = 0,
        runtimeDescription = "测试 JVM",
        units = units,
    )
}
