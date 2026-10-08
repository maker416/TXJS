/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.*

/** 列表和加入详情使用同一名称，地图缺失不能让房间标题变空。 */
internal fun roomDisplayName(room: RoomDescription): String =
    sequenceOf(room.creator, room.roomOwner)
        .map(String::trim)
        .firstOrNull { it.isNotEmpty() && !it.equals("Unnamed", true) && !it.equals("Unknown", true) }
        ?: readI18n("multiplayer.roomList.unnamedRoom")

internal fun roomDisplayMap(room: RoomDescription): String =
    room.mapName.trim().removeSuffix(".tmx")
        .takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
        ?: readI18n("multiplayer.roomList.mapNotProvided")

internal fun roomListStatusI18nKey(room: RoomDescription): String? {
    val key = if (!room.listAvailabilityKnown) {
        "statusUnknown"
    } else when (room.listDegradeReason()) {
        RoomListDegradeReason.Unavailable -> "statusUnavailable"
        RoomListDegradeReason.Full -> "statusFull"
        RoomListDegradeReason.VersionMismatch -> "statusVersionMismatch"
        else -> return null
    }
    return "multiplayer.roomList.$key"
}

/** 独立卡片便于验证不同屏宽；点击继续使用多人页原有的加入详情流程。 */
@Composable
internal fun RoomListCard(
    room: RoomDescription,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isDegraded = !room.isJoinableFromList
    val titleColor = if (room.isLocal) colors.primary else colors.onSurface
    val requiredMods = parseRequiredModNames(room.mods)
    val category = when {
        !room.isModdedRoom -> readI18n("multiplayer.roomList.vanillaDisplay")
        requiredMods.isNotEmpty() -> readI18n(
            "multiplayer.roomList.modCount", I18nType.RWPP, requiredMods.size.toString(),
        )
        else -> readI18n("multiplayer.roomList.moddedDisplay")
    }
    // 列表中的大厅常用几百万作为人数上限；只展示当前人数，完整上限仍保留在详情。
    val players = if ((room.playerMaxCount ?: 0) >= 1000) {
        readI18n("multiplayer.roomList.currentPlayers", I18nType.RWPP, room.playerCurrentCount?.toString() ?: "?")
    } else {
        "${room.playerCurrentCount ?: "?"}/${room.playerMaxCount ?: "?"}"
    }
    val labels = room.labels.filterNot {
        it.equals(MOD_SYNC_ROOM_TYPE, true) || it.equals(DEFAULT_PUBLISH_ROOM_TYPE, true) || it == category
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.94f)),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier
                .alpha(if (isDegraded) 0.7f else 1f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RoomListMapThumbnail(room.mapName)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        roomDisplayName(room),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (room.isUpperCase) FontWeight.Bold else FontWeight.SemiBold,
                        color = titleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RoomListBadge(players, icon = Icons.Default.Person)
                }
                RoomListCardLabelFlow(
                    room = room,
                    category = category,
                    labels = labels,
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    roomDisplayMap(room),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RoomListCardLabelFlow(
    room: RoomDescription,
    category: String,
    labels: List<String>,
    colors: ColorScheme,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.clipToBounds()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoomListBadge(category)
            if (room.hasRoomLabel(MOD_SYNC_ROOM_TYPE)) {
                RoomListBadge(
                    readI18n("multiplayer.roomList.modSyncDisplay"),
                    icon = Icons.Default.Refresh,
                    foreground = colors.primary,
                    background = colors.primary.copy(alpha = 0.10f),
                )
            }
            if (room.requiredPassword) {
                RoomListBadge(readI18n("multiplayer.roomList.accessPassword"), icon = Icons.Default.Lock)
            }
            roomListStatusI18nKey(room)?.let { key ->
                RoomListBadge(
                    readI18n(key),
                    foreground = if (room.listAvailabilityKnown) colors.error else colors.onSurfaceVariant,
                    background = if (room.listAvailabilityKnown) colors.error.copy(alpha = 0.10f)
                        else colors.onSurface.copy(alpha = 0.06f),
                )
            }
            labels.forEach { RoomListBadge(it) }
        }
    }
}

@Composable
private fun RoomListBadge(
    label: String,
    icon: ImageVector? = null,
    foreground: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
) {
    Surface(color = background, shape = RoundedCornerShape(6.dp)) {
        Row(
            modifier = Modifier.widthIn(max = 220.dp).padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = foreground)
            }
            Text(
                label,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelMedium,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
