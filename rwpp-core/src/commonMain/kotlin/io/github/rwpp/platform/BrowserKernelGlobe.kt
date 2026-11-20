/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.rwpp.widget.uiAnimationsEnabled
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun BrowserKernelGlobe(scanning: Boolean, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val phase = if (scanning && uiAnimationsEnabled()) {
        rememberInfiniteTransition(label = "Browser component scan").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 3400, easing = LinearEasing)),
            label = "Scan angle",
        )
    } else null

    Canvas(modifier) {
        val globeRadius = size.minDimension * 0.28f
        val orbitRadius = size.minDimension * 0.42f
        val grid = primary.copy(alpha = 0.7f)
        val gridStroke = Stroke(1.dp.toPx(), cap = StrokeCap.Round)
        val globeStroke = Stroke(1.45.dp.toPx(), cap = StrokeCap.Round)

        drawCircle(primary.copy(alpha = 0.08f), globeRadius)
        drawCircle(primary, globeRadius, style = globeStroke)
        drawOval(
            grid,
            topLeft = center - Offset(globeRadius * 0.5f, globeRadius),
            size = Size(globeRadius, globeRadius * 2f),
            style = gridStroke,
        )
        drawLine(grid, center - Offset(globeRadius, 0f), center + Offset(globeRadius, 0f), gridStroke.width)
        val latitudeBounds = Size(globeRadius * 2f, globeRadius * 0.95f)
        val latitudeTopLeft = center - Offset(latitudeBounds.width / 2f, latitudeBounds.height / 2f)
        drawArc(grid, 16f, 148f, false, latitudeTopLeft, latitudeBounds, style = gridStroke)
        drawArc(grid, 196f, 148f, false, latitudeTopLeft, latitudeBounds, style = gridStroke)

        val angle = phase?.value ?: -36f
        drawCircle(primary.copy(alpha = 0.27f), orbitRadius, style = gridStroke)
        drawArc(
            color = primary.copy(alpha = 0.85f),
            startAngle = angle - 78f,
            sweepAngle = 78f,
            useCenter = false,
            topLeft = center - Offset(orbitRadius, orbitRadius),
            size = Size(orbitRadius * 2f, orbitRadius * 2f),
            style = globeStroke,
        )
        val radians = angle * PI / 180.0
        val signal = center + Offset(cos(radians).toFloat(), sin(radians).toFloat()) * orbitRadius
        drawCircle(primary.copy(alpha = 0.18f), radius = 4.5.dp.toPx(), center = signal)
        drawCircle(primary, radius = 2.dp.toPx(), center = signal)
    }
}
