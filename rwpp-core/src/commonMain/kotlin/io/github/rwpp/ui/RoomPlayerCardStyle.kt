/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.RoomIdentityController
import io.github.rwpp.coil.AccountAvatar
import io.github.rwpp.core.ListDetectorPlayers
import io.github.rwpp.core.RoomSnapshotStore
import io.github.rwpp.game.ConnectingPlayer
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.Player
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.RoomPlayerCardSpec

/** Header and player rows reserve exactly the same avatar width, independent of loaded assets. */
internal fun RoomPlayerCardSpec.avatarCellWidth(compact: Boolean): Dp =
    if (enabled && avatar.enabled) (minOf(avatar.size, if (compact) 48 else 64) + 12).dp else 0.dp

/** Only the inside of an occupied player row is extended; actions and engine cells keep their owners. */
@Composable
internal fun RowScope.RoomPlayerCardBody(
    card: RoomPlayerCardSpec,
    player: Player,
    room: GameRoom,
    compact: Boolean,
    cells: @Composable RowScope.() -> Unit,
) {
    if (!card.enabled || player == ConnectingPlayer) {
        // Connecting placeholders keep the column alignment without displaying invented player data.
        if (card.enabled) Spacer(Modifier.width(card.avatarCellWidth(compact)))
        cells()
        return
    }
    val slot = player.spawnPoint + 1
    val content = card.contentFor(slot, player == room.localPlayer, player.isRoomHost, player.isAI, player.isSpectator)
    if (card.avatar.enabled) {
        val avatar = card.avatar
        val size = minOf(avatar.size, if (compact) 48 else 64).dp
        val shape = RoundedCornerShape(avatar.corner.dp)
        val tint = roomThemeColor(avatar.tint, MaterialTheme.colorScheme.primary)
        Box(
            Modifier.width(card.avatarCellWidth(compact)).fillMaxHeight().padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            val theme = ArtThemeController.activeTheme
            val file = remember(theme, content.avatarImage) { roomThemeAsset(content.avatarImage) }
            var loaded by remember(theme, file) { mutableStateOf(false) }
            val accountAvatar = roomPlayerAccountAvatar(player, room)
            Box(
                Modifier.size(size).testTag("room-player-avatar-$slot").clip(shape)
                    .background(roomThemeColor(avatar.backgroundColor, MaterialTheme.colorScheme.primaryContainer))
                    .roomThemeBorder(avatar.borderWidth, roomThemeColor(avatar.borderColor, tint.copy(alpha = .6f)), shape),
                contentAlignment = Alignment.Center,
            ) {
                if (!loaded) {
                    Icon(
                        if (player.isAI) RoomRobotAvatarIcon else Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(size * .65f).testTag("room-player-avatar-fallback-$slot"),
                        tint = tint,
                    )
                }
                if (file != null) key(theme, file) {
                    AsyncImage(file, null, Modifier.fillMaxSize().testTag("room-player-default-avatar-$slot"), contentScale = ContentScale.Fit,
                        onSuccess = { loaded = true }, onError = { loaded = false })
                }
                // Account images always draw over the pack's default, without a theme tint.
                // Loading, missing or corrupt account images leave the default visible below.
                if (accountAvatar != null) key(accountAvatar) {
                    AsyncImage(accountAvatar, null,
                        Modifier.fillMaxSize().testTag("room-player-account-avatar-$slot"),
                        contentScale = ContentScale.Crop)
                }
            }
        }
    }
    Column(Modifier.weight(1f)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max)
            .heightIn(min = if (compact) 32.dp else 36.dp), content = cells)
        content.rows.forEachIndexed { rowIndex, row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)
                    .height(IntrinsicSize.Max).testTag("room-player-info-$slot-$rowIndex"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.cells.forEachIndexed { cellIndex, cell ->
                    val shape = RoundedCornerShape(cell.corner.dp)
                    val color = roomThemeColor(cell.contentColor, MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        Modifier.weight(1f).fillMaxHeight().heightIn(min = 24.dp)
                            .testTag("room-player-info-$slot-$rowIndex-$cellIndex")
                            .clip(shape).background(roomThemeColor(cell.backgroundColor, Color.Transparent))
                            .roomThemeBorder(cell.borderWidth, roomThemeColor(cell.borderColor, color.copy(alpha = .4f)), shape)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val theme = ArtThemeController.activeTheme
                        val icon = remember(theme, cell.iconImage) { roomThemeAsset(cell.iconImage) }
                        if (icon != null) key(theme, icon) {
                            AsyncImage(icon, null, Modifier.size(16.dp), contentScale = ContentScale.Fit)
                        }
                        if (cell.text.isNotEmpty()) Text(
                            cell.text, color = color, style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** Self identity is local; other players require a unique, verified room membership binding. */
@Composable
private fun roomPlayerAccountAvatar(player: Player, room: GameRoom): AccountAvatar? {
    if (player.isAI || player == ConnectingPlayer || ListDetectorPlayers.isDetector(player.name)) return null
    if (!AccountSession.loggedIn) return null
    if (player == room.localPlayer) {
        val user = AccountSession.user ?: return null
        return if (user.hasAvatar) AccountAvatar(user.id, true, AccountSession.avatarVersion) else null
    }
    val snapshot by RoomSnapshotStore.snapshot.collectAsState()
    val name = player.name.trim()
    if (snapshot.players.count { it.name.trim() == name } != 1 || snapshot.players.none { it === player }) return null
    val user = RoomIdentityController.playerAccountFor(player, room) ?: return null
    return if (user.hasAvatar) AccountAvatar(user.id, true,
        RoomIdentityController.playerAvatarVersionFor(player, room)) else null
}

/** Small native vector avoids a platform emoji font or an extra icon library for the fallback. */
private val RoomRobotAvatarIcon = ImageVector.Builder("RoomRobotAvatar", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        moveTo(11f, 2f); lineTo(13f, 2f); lineTo(13f, 6f); lineTo(11f, 6f); close()
        moveTo(6f, 6f); lineTo(18f, 6f)
        curveTo(19.1f, 6f, 20f, 6.9f, 20f, 8f)
        lineTo(20f, 19f); curveTo(20f, 20.1f, 19.1f, 21f, 18f, 21f)
        lineTo(6f, 21f); curveTo(4.9f, 21f, 4f, 20.1f, 4f, 19f)
        lineTo(4f, 8f); curveTo(4f, 6.9f, 4.9f, 6f, 6f, 6f); close()
        moveTo(1f, 10f); lineTo(3f, 10f); lineTo(3f, 17f); lineTo(1f, 17f); close()
        moveTo(21f, 10f); lineTo(23f, 10f); lineTo(23f, 17f); lineTo(21f, 17f); close()
        moveTo(7f, 10f); lineTo(10f, 10f); lineTo(10f, 14f); lineTo(7f, 14f); close()
        moveTo(14f, 10f); lineTo(17f, 10f); lineTo(17f, 14f); lineTo(14f, 14f); close()
        moveTo(9f, 17f); lineTo(15f, 17f); lineTo(15f, 18f); lineTo(9f, 18f); close()
    }
}.build()
