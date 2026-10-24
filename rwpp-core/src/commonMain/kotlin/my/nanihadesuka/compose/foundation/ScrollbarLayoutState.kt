/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package my.nanihadesuka.compose.foundation

import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rwpp.widget.animateUiColorAsState
import io.github.rwpp.widget.animateUiDpAsState
import io.github.rwpp.widget.animateUiFloatAsState
import kotlinx.coroutines.delay
import my.nanihadesuka.compose.ScrollbarSelectionActionable


@Composable
internal fun rememberScrollbarLayoutState(
    thumbIsInAction: Boolean,
    thumbIsSelected: Boolean,
    settings: ScrollbarLayoutSettings,
): ScrollbarLayoutState {
    val settingsUpdated by rememberUpdatedState(settings)
    val thumbIsInActionUpdated by rememberUpdatedState(thumbIsInAction)

    val isInActionSelectable = remember { mutableStateOf(thumbIsInAction) }

    LaunchedEffect(thumbIsInAction) {
        if (thumbIsInAction) {
            isInActionSelectable.value = true
        } else {
            delay(timeMillis = settingsUpdated.durationAnimationMillis.toLong() + settingsUpdated.hideDelayMillis.toLong())
            isInActionSelectable.value = false
        }
    }

    val activeDraggableModifier = remember {
        derivedStateOf {
            when (settingsUpdated.selectionActionable) {
                ScrollbarSelectionActionable.Always -> true
                ScrollbarSelectionActionable.WhenVisible -> isInActionSelectable.value
            }
        }
    }

    val thumbColor = animateUiColorAsState(
        targetValue = if (thumbIsSelected) settingsUpdated.thumbSelectedColor else settingsUpdated.thumbUnselectedColor,
        animationSpec = tween(durationMillis = 50),
        label = "scrollbar thumb color value"
    )

    val currentDurationMillis = remember {
        derivedStateOf {
            val reductionRatio: Int = if (thumbIsInActionUpdated) 4 else 1
            settingsUpdated.durationAnimationMillis / reductionRatio
        }
    }

    val hideAlpha = animateUiFloatAsState(
        targetValue = if (thumbIsInActionUpdated) 1f else 0f,
        animationSpec = tween(
            durationMillis = currentDurationMillis.value,
            delayMillis = if (thumbIsInActionUpdated) 0 else settingsUpdated.hideDelayMillis,
            easing = settingsUpdated.hideEasingAnimation
        ),
        label = "scrollbar alpha value"
    )

    val hideDisplacement = animateUiDpAsState(
        targetValue = if (thumbIsInActionUpdated) 0.dp else settingsUpdated.hideDisplacement,
        animationSpec = tween(
            durationMillis = currentDurationMillis.value,
            delayMillis = if (thumbIsInActionUpdated) 0 else settingsUpdated.hideDelayMillis,
            easing = settingsUpdated.hideEasingAnimation
        ),
        label = "scrollbar displacement value"
    )

    return remember(activeDraggableModifier, thumbColor, hideDisplacement, hideAlpha) {
        ScrollbarLayoutState(
            activeDraggableModifier = activeDraggableModifier,
            thumbColor = thumbColor,
            hideDisplacement = hideDisplacement,
            hideAlpha = hideAlpha
        )
    }

}

internal data class ScrollbarLayoutState(
    val activeDraggableModifier: State<Boolean>,
    val thumbColor: State<Color>,
    val hideAlpha: State<Float>,
    val hideDisplacement: State<Dp>
)
