/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import io.github.rwpp.theme.RoomPlayerInfoRowSpec
import kotlin.math.roundToInt

/** A measured line keeps original cell indices when a logical information row wraps. */
internal data class RoomPlayerInfoLine(val cells: List<Int>, val widths: List<Int>)

/** Fixed widths reserve space first; automatic cells share the remainder by weight. */
internal fun roomPlayerInfoLines(
    width: Int,
    columns: Int,
    spacing: Int,
    fixedWidths: List<Int>,
    weights: List<Float>,
    minimumCellWidth: Int,
): List<RoomPlayerInfoLine> {
    if (fixedWidths.isEmpty()) return emptyList()
    val available = width.coerceAtLeast(0)
    val gap = spacing.coerceIn(0, available)
    val minimums = fixedWidths.map { if (it > 0) it.coerceAtMost(available) else minimumCellWidth.coerceIn(0, available) }
    val groups = mutableListOf<MutableList<Int>>()
    var current = mutableListOf<Int>()
    var used = 0
    minimums.forEachIndexed { index, minimum ->
        val nextWidth = used + (if (current.isEmpty()) 0 else gap) + minimum
        if (current.isNotEmpty() && (current.size >= columns.coerceAtLeast(1) || nextWidth > available)) {
            groups += current
            current = mutableListOf()
            used = 0
        }
        if (current.isNotEmpty()) used += gap
        current += index
        used += minimum
    }
    if (current.isNotEmpty()) groups += current
    return groups.map { indices ->
        val fixed = indices.sumOf { fixedWidths[it].coerceIn(0, available) }
        val flexible = indices.filter { fixedWidths[it] == 0 }
        var remaining = (available - gap * (indices.size - 1) - fixed).coerceAtLeast(0)
        var remainingWeight = flexible.sumOf { weights[it].toDouble() }.toFloat()
        val assigned = mutableMapOf<Int, Int>()
        flexible.forEachIndexed { index, cell ->
            val cellWidth = if (index == flexible.lastIndex) remaining else
                (remaining * weights[cell] / remainingWeight).roundToInt().coerceIn(0, remaining)
            assigned[cell] = cellWidth
            remaining -= cellWidth
            remainingWeight -= weights[cell]
        }
        RoomPlayerInfoLine(indices, indices.map { assigned[it] ?: fixedWidths[it].coerceAtMost(available) })
    }
}

/** Explicit intrinsic measurement lets the grid remain inside the existing intrinsic-height player row. */
@Composable
internal fun RoomPlayerInfoLayout(
    row: RoomPlayerInfoRowSpec,
    columns: Int,
    cellSpacing: Int,
    rowSpacing: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val policy = remember(row, columns, cellSpacing, rowSpacing) {
        RoomPlayerInfoMeasurePolicy(row, columns, cellSpacing, rowSpacing)
    }
    Layout(content = content, modifier = modifier, measurePolicy = policy)
}

private class RoomPlayerInfoMeasurePolicy(
    private val row: RoomPlayerInfoRowSpec,
    private val columns: Int,
    private val cellSpacing: Int,
    private val rowSpacing: Int,
) : MeasurePolicy {
    private fun IntrinsicMeasureScope.lines(width: Int) = roomPlayerInfoLines(
        width = width,
        columns = columns,
        spacing = cellSpacing.dp.roundToPx(),
        fixedWidths = row.cells.map { it.width.dp.roundToPx() },
        weights = row.cells.map { it.weight },
        minimumCellWidth = 24.dp.roundToPx(),
    )

    private fun IntrinsicMeasureScope.lineHeight(line: RoomPlayerInfoLine, measurables: List<IntrinsicMeasurable>): Int =
        maxOf(24.dp.roundToPx(), row.height.dp.roundToPx(), line.cells.indices.maxOfOrNull { index ->
            val cell = line.cells[index]
            maxOf(row.cells[cell].height.dp.roundToPx(), measurables[cell].maxIntrinsicHeight(line.widths[index]))
        } ?: 0)

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else
            maxIntrinsicWidth(measurables, Constraints.Infinity).coerceAtLeast(constraints.minWidth)
        val lines = lines(width)
        val heights = lines.map { lineHeight(it, measurables) }
        val gap = rowSpacing.dp.roundToPx()
        val contentHeight = heights.sum() + gap * (lines.size - 1).coerceAtLeast(0)
        val placeables = lines.mapIndexed { lineIndex, line ->
            line.cells.mapIndexed { index, cell ->
                measurables[cell].measure(Constraints.fixed(line.widths[index], heights[lineIndex]))
            }
        }
        return layout(constraints.constrainWidth(width), constraints.constrainHeight(contentHeight)) {
            var y = 0
            lines.forEachIndexed { lineIndex, line ->
                var x = 0
                line.cells.forEachIndexed { index, _ ->
                    placeables[lineIndex][index].placeRelative(x, y)
                    x += line.widths[index] + cellSpacing.dp.roundToPx()
                }
                y += heights[lineIndex] + gap
            }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        row.cells.maxOfOrNull { maxOf(24.dp.roundToPx(), it.width.dp.roundToPx()) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        row.cells.indices.chunked(columns).maxOfOrNull { cells ->
            cells.sumOf { index -> row.cells[index].width.takeIf { it > 0 }?.dp?.roundToPx()
                ?: maxOf(24.dp.roundToPx(), measurables[index].maxIntrinsicWidth(height)) } +
                cellSpacing.dp.roundToPx() * (cells.size - 1)
        } ?: 0

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width)

    private fun IntrinsicMeasureScope.intrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val lines = lines(width)
        return lines.sumOf { lineHeight(it, measurables) } + rowSpacing.dp.roundToPx() * (lines.size - 1).coerceAtLeast(0)
    }
}
