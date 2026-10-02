/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportExportTest {
    @Test
    fun csvKeepsCommaQuoteAndChineseInSeparateCells() {
        val measurement = measured(listOf(MeasuredUnitHeap("战车,\"重型\"", "units/a.ini", 1024)))
        val csv = measurement.unitCsv()
        assertTrue(csv.startsWith("单位名称,定义文件,实测独占堆字节\r\n"))
        assertTrue(csv.contains("\"战车,\"\"重型\"\"\",\"units/a.ini\",1024\r\n"))
    }

    @Test
    fun reportSeparatesMeasuredHeapAndSharedObjectsWithoutAndroidExtrapolation() {
        val measurement = measured(listOf(MeasuredUnitHeap("Scout", "units/scout.ini", 1024)))
        val report = measurement.formatReport()
        assertEquals(200_000L, measurement.incrementalHeapBytes)
        assertTrue(report.contains("GC 后堆净增："))
        assertTrue(report.contains("(200000 字节)"))
        assertTrue(report.contains("其中多单位共享对象："))
        assertTrue(report.contains("引擎贴图记账："))
        assertTrue(report.contains("测试 JVM"))
        assertTrue(report.contains("Scout\tunits/scout.ini"))
        assertFalse(report.contains("512 MiB"))
        assertFalse(report.contains("新旧同规模单位表并存"))
    }

    private fun measured(units: List<MeasuredUnitHeap>) = MeasuredModHeap(
        sourceName = "pack",
        baselineHeapBytes = 1_000_000,
        loadedHeapBytes = 1_200_000,
        sampledPeakHeapBytes = 1_500_000,
        heapLimitBytes = 768 * 1024 * 1024,
        definitionHeapBytes = 2048,
        sharedDefinitionHeapBytes = 1024,
        textureAccountedBytes = 32_768,
        soundAccountedBytes = 16_384,
        runtimeDescription = "测试 JVM",
        units = units,
    )
}
