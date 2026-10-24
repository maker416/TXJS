/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget.v2

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import io.github.rwpp.widget.uiAnimationsEnabled
import kotlin.math.cos
import kotlin.math.sin

enum class LinearAnimationType(val animDuration: Int, val circleDelay: Long) {
    CIRCULAR(500, 100L),
    SKIP_AND_REPEAT(250, 250L);
}

@Composable
fun LineSpinFadeLoaderIndicator(
    color: Color = Color.White,
    rectCount: Int = 8,
    linearAnimationType: LinearAnimationType = LinearAnimationType.CIRCULAR,
    penThickness: Float = 25f,
    radius: Float = 55f,
    elementHeight: Float = 20f,
    minAlpha: Float = 0.2f,
    maxAlpha: Float = 1.0f
) {

    val enabled = uiAnimationsEnabled()
    val cycle = linearAnimationType.animDuration * 2
    // One clock replaces rectCount perpetual coroutines and per-frame list allocation.
    val phase = if (enabled) rememberInfiniteTransition(label = "loader").animateFloat(
        initialValue = 0f, targetValue = cycle.toFloat(),
        animationSpec = infiniteRepeatable(tween(cycle, easing = LinearEasing)), label = "loader phase",
    ) else rememberUpdatedState(0f)
    val lines = remember(rectCount, radius, elementHeight) {
        List(rectCount) { index ->
            val angle = Math.toRadians(index * 360.0 / rectCount)
            val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            direction * radius to direction * (radius + elementHeight)
        }
    }
    Canvas(modifier = Modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        lines.forEachIndexed { index, (start, end) ->
            val shifted = ((phase.value - linearAnimationType.circleDelay * (index + 1)) % cycle + cycle) % cycle
            val fraction = (shifted / linearAnimationType.animDuration).let { if (it > 1f) 2f - it else it }
            val alpha = if (enabled) minAlpha + (maxAlpha - minAlpha) * FastOutSlowInEasing.transform(fraction)
                else minAlpha + (maxAlpha - minAlpha) * (index + 1f) / rectCount
            drawLine(color, center + start, center + end, penThickness * alpha, alpha = alpha, cap = StrokeCap.Round)
        }
    }
}
