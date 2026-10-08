/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val ListScrollVisibilityThresholdPx = 8
private const val WheelVisibilityThresholdPx = 36f

/**
 * 列表拖拽/甩动：根据 [LazyListState] 位移判断显隐。
 * 滚轮走 [multiplayerTopBarWheelVisibility]，避免桌面滚轮逐步滚动时状态抖动。
 */
@Composable
internal fun rememberMultiplayerTopBarVisible(listState: LazyListState): MutableState<Boolean> {
    val visible = remember { mutableStateOf(true) }
    var prevIndex by remember { mutableIntStateOf(0) }
    var prevOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.canScrollBackward)
        }.collect { (index, offset, canScrollBackward) ->
            if (!canScrollBackward || (index == 0 && offset == 0)) {
                visible.value = true
            } else {
                val scrolledDown = index > prevIndex ||
                    (index == prevIndex && offset > prevOffset + ListScrollVisibilityThresholdPx)
                val scrolledUp = index < prevIndex ||
                    (index == prevIndex && offset < prevOffset - ListScrollVisibilityThresholdPx)
                when {
                    scrolledDown -> visible.value = false
                    scrolledUp -> visible.value = true
                }
            }
            prevIndex = index
            prevOffset = offset
        }
    }
    return visible
}

@Composable
internal fun rememberAnimatedMultiplayerListTopInset(
    measuredTopBarHeight: Dp,
    topBarVisible: Boolean,
): State<Dp> {
    val target = if (topBarVisible) measuredTopBarHeight else 0.dp
    return animateDpAsState(target, label = "multiplayerListTopInset")
}

@Composable
internal fun rememberAnimatedMultiplayerTopBarOffsetY(
    measuredTopBarHeight: Dp,
    topBarVisible: Boolean,
): State<Dp> {
    val target = if (topBarVisible) 0.dp else -measuredTopBarHeight
    return animateDpAsState(target, label = "multiplayerTopBarOffset")
}

/** 桌面滚轮：仅在列表可以沿输入方向滚动时累计 delta，列表顶部始终保留顶栏。 */
internal fun Modifier.multiplayerTopBarWheelVisibility(
    listState: LazyListState,
    onVisibilityChange: (visible: Boolean) -> Unit,
): Modifier = pointerInput(listState) {
    var wheelAccum = 0f
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val deltaY = event.changes.fold(0f) { acc, change ->
                acc + change.scrollDelta.y
            }
            if (deltaY == 0f) continue
            if (!listState.canScrollBackward) {
                wheelAccum = 0f
                onVisibilityChange(true)
                continue
            }
            if (deltaY > 0f && !listState.canScrollForward) {
                wheelAccum = 0f
                continue
            }
            wheelAccum += deltaY
            when {
                wheelAccum >= WheelVisibilityThresholdPx -> {
                    onVisibilityChange(false)
                    wheelAccum = 0f
                }
                wheelAccum <= -WheelVisibilityThresholdPx -> {
                    onVisibilityChange(true)
                    wheelAccum = 0f
                }
                wheelAccum > 0 && deltaY < 0 || wheelAccum < 0 && deltaY > 0 -> {
                    wheelAccum = deltaY
                }
            }
        }
    }
}
