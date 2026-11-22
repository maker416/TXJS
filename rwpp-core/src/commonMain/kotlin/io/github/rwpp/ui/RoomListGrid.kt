/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rwpp.net.RoomDescription
import kotlinx.coroutines.delay

private val roomGridHorizontalPadding = 8.dp
private val roomGridSpacing = 10.dp

/**
 * 追踪从「加载舞台」切换到「真实房间列表」的一次性波浪入场状态，
 * 避免每 5 秒自动刷新或上下滚动回收重入时反复重播入场动画。
 */
@Stable
internal class RoomListArrivalState {
    var epoch by mutableIntStateOf(0)
        private set
    private val animatedRowKeys = HashSet<String>()

    fun markArrival() {
        epoch++
        animatedRowKeys.clear()
    }

    fun consumeShouldAnimate(rowKey: String, rowIndex: Int): Boolean {
        if (epoch <= 0 || rowIndex > 5) return false
        return animatedRowKeys.add(rowKey)
    }
}

/**
 * 按可用宽度分列。卡片含缩略图 + 单行标签，最小宽度略保守，避免宽屏过早挤成 4 列；
 * 不硬编码列数上限，超宽屏仍可自然增加列数。
 */
internal fun roomCardColumnCount(availableWidth: Dp, fontScale: Float = 1f): Int {
    val minimumCardWidth = 332.dp * maxOf(1f, fontScale)
    return ((availableWidth - roomGridHorizontalPadding * 2 + roomGridSpacing) /
        (minimumCardWidth + roomGridSpacing)).toInt().coerceAtLeast(1)
}

/** 按行懒加载，和顶部工具栏、错误提示及收藏服务器共用滚动条，不嵌套滚动容器。 */
internal fun LazyListScope.roomCardRows(
    rooms: List<RoomDescription>,
    columns: Int,
    animate: Boolean = false,
    arrivalState: RoomListArrivalState? = null,
    onRoomClick: (RoomDescription) -> Unit,
) {
    itemsIndexed(
        items = rooms.chunked(columns),
        key = { _, row -> "room-row:${row.first().uuid}" },
        contentType = { _, _ -> "room-row" },
    ) { rowIndex, row ->
        val rowKey = "room-row:${row.first().uuid}"
        val itemModifier = if (animate) {
            Modifier.animateItem(
                fadeInSpec = tween(260, easing = StandardDecelerateEasing),
                fadeOutSpec = tween(180, easing = EmphasizedAccelerateEasing),
            )
        } else {
            Modifier
        }
        RoomCardRow(
            rooms = row,
            columns = columns,
            onRoomClick = onRoomClick,
            modifier = itemModifier.roomRowArrivalMotion(
                arrivalState = arrivalState,
                rowKey = rowKey,
                rowIndex = rowIndex,
                animate = animate,
            ),
        )
    }
}

@Composable
private fun Modifier.roomRowArrivalMotion(
    arrivalState: RoomListArrivalState?,
    rowKey: String,
    rowIndex: Int,
    animate: Boolean,
): Modifier {
    if (!animate || arrivalState == null) return this
    val epoch = arrivalState.epoch
    val shouldAnimate = remember(epoch, rowKey) {
        arrivalState.consumeShouldAnimate(rowKey, rowIndex)
    }
    if (!shouldAnimate) return this
    val progress = remember(epoch, rowKey) { Animatable(0f) }
    LaunchedEffect(epoch, rowKey) {
        val staggerMs = rowIndex.coerceAtMost(5) * 42L
        if (staggerMs > 0L) delay(staggerMs)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 320, easing = EmphasizedDecelerateEasing),
        )
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 12.dp.toPx()
        val scale = 0.985f + 0.015f * p
        scaleX = scale
        scaleY = scale
    }
}

@Composable
private fun RoomCardRow(
    rooms: List<RoomDescription>,
    columns: Int,
    onRoomClick: (RoomDescription) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .padding(horizontal = roomGridHorizontalPadding, vertical = 5.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(roomGridSpacing),
    ) {
        rooms.forEach { room ->
            key(room.uuid) {
                RoomListCard(
                    room = room,
                    onClick = { onRoomClick(room) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        // 最后一行未填满时保持卡片宽度，避免剩余房间重新拉伸成整行。
        repeat(columns - rooms.size) { Spacer(Modifier.weight(1f)) }
    }
}
