/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/** The timeout is wall time, independent of animation preference/system duration scale. */
@Composable
internal fun rememberTimedUiProgress(key: Any, durationMillis: Int, onTimeout: () -> Unit): State<Float> {
    val enabled = uiAnimationsEnabled()
    val started = remember(key) { TimeSource.Monotonic.markNow() }
    val progress = remember(key) { Animatable(1f) }
    val timeout by rememberUpdatedState(onTimeout)
    LaunchedEffect(key) {
        delay(durationMillis.toLong())
        timeout()
    }
    LaunchedEffect(key, enabled) {
        if (enabled) {
            val remaining = (durationMillis - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(0).toInt()
            progress.snapTo(remaining.toFloat() / durationMillis)
            progress.animateTo(0f, tween(remaining, easing = LinearEasing))
        }
    }
    return if (enabled) progress.asState() else rememberUpdatedState(1f)
}
