/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomPlayerInfoLayoutTest {
    @Test
    fun fixedCellsAndWeightedCellsShareSpaceWithoutLosingWrappedCells() {
        val lines = roomPlayerInfoLines(400, 3, 10, listOf(96, 0, 0, 1200, 80),
            listOf(1f, 1f, 2f, 1f, 1f), 24)
        assertEquals(listOf(0, 1, 2), lines[0].cells)
        assertEquals(96, lines[0].widths[0])
        assertTrue(kotlin.math.abs(lines[0].widths[2] - lines[0].widths[1] * 2) <= 1)
        assertEquals(listOf(3), lines[1].cells)
        assertEquals(listOf(400), lines[1].widths)
        assertEquals(listOf(4), lines[2].cells)
        assertEquals(listOf(80), lines[2].widths)
    }

    @Test
    fun maximumGridRemainsWithinNarrowAndZeroWidthContainers() {
        for (width in listOf(0, 1, 12, 24, 80, 360, 1920)) {
            for (columns in listOf(1, 4, 12)) {
                val fixed = List(12) { if (it % 3 == 0) 1200 else 0 }
                val lines = roomPlayerInfoLines(width, columns, 24, fixed,
                    List(12) { if (it % 2 == 0) .1f else 10f }, 24)
                assertEquals((0..11).toList(), lines.flatMap { it.cells })
                lines.forEach { line ->
                    assertTrue(line.cells.size <= columns)
                    assertTrue(line.widths.all { it in 0..width })
                    assertTrue(line.widths.sum() + minOf(24, width) * (line.cells.size - 1) <= width)
                }
            }
        }
    }
}
