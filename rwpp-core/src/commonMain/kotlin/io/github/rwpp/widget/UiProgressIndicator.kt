/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Unknown progress stays indeterminate for accessibility, even when its visual is static. */
@Composable
fun UiCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
) {
    if (uiAnimationsEnabled()) {
        CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = strokeWidth)
    } else {
        Canvas(modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }.size(40.dp)) {
            val stroke = strokeWidth.toPx()
            drawArc(color, -90f, 270f, false, Offset(stroke / 2, stroke / 2),
                Size((size.width - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)),
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun UiLinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    if (uiAnimationsEnabled()) {
        LinearProgressIndicator(modifier = modifier, color = color, trackColor = trackColor)
    } else {
        Canvas(modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }.size(240.dp, 4.dp)) {
            drawLine(trackColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height)
            drawLine(color, Offset(size.width / 3, size.height / 2), Offset(size.width * 2 / 3, size.height / 2), size.height)
        }
    }
}
