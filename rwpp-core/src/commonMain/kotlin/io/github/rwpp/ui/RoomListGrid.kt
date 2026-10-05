/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rwpp.net.RoomDescription

private val roomGridHorizontalPadding = 8.dp
private val roomGridSpacing = 10.dp

/** 按可用宽度分列，大字体提高卡片最小宽度，保持名称、人数和标签可读。 */
internal fun roomCardColumnCount(availableWidth: Dp, fontScale: Float = 1f): Int {
    val minimumCardWidth = 300.dp * maxOf(1f, fontScale)
    return ((availableWidth - roomGridHorizontalPadding * 2 + roomGridSpacing) /
        (minimumCardWidth + roomGridSpacing)).toInt().coerceAtLeast(1)
}

/** 按行懒加载，和顶部工具栏、错误提示及收藏服务器共用滚动条，不嵌套滚动容器。 */
internal fun LazyListScope.roomCardRows(
    rooms: List<RoomDescription>,
    columns: Int,
    animate: Boolean = false,
    onRoomClick: (RoomDescription) -> Unit,
) {
    items(
        items = rooms.chunked(columns),
        key = { "room-row:${it.first().uuid}" },
        contentType = { "room-row" },
    ) { row ->
        RoomCardRow(
            rooms = row,
            columns = columns,
            onRoomClick = onRoomClick,
            modifier = if (animate) Modifier.animateItem() else Modifier,
        )
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
