/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import io.github.rwpp.game.mod.heap.ModHeapEstimate
import io.github.rwpp.game.mod.heap.UnitHeap
import kotlin.test.Test
import kotlin.test.assertTrue

class ReportExportTest {
    @Test
    fun csvKeepsCommaQuoteAndChineseInSeparateCells() {
        val estimate = ModHeapEstimate("pack", units = listOf(UnitHeap("战车,\"重型\"", "units/a.ini", 1024, 3, 2, 1)))
        val csv = estimate.unitCsv()
        assertTrue(csv.contains("\"战车,\"\"重型\"\"\",\"units/a.ini\",1024,3,2,1"))
    }
}
