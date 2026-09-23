/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable
import io.github.rwpp.game.ConnectingPlayer
import io.github.rwpp.game.Game
import io.github.rwpp.game.Player
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import org.koin.compose.koinInject

@Composable
actual fun KickPlayerContextMenuAreaMultiplatform(
    player: Player,
    onViewProfile: ((Player) -> Unit)?,
    content: @Composable (() -> Unit),
) {
    val room = koinInject<Game>().gameRoom
    ContextMenuArea(
        items = {
            buildList {
                // 查看名片：所有真人玩家可见（AI 与占位 ConnectingPlayer 除外）
                if (onViewProfile != null && !player.isAI && player != ConnectingPlayer) {
                    add(
                        ContextMenuItem(
                            readI18n("playerCard.viewProfile", I18nType.RWPP)
                        ) {
                            onViewProfile(player)
                        }
                    )
                }
                if ((room.isHost || room.isHostServer) && room.localPlayer != player) {
                    add(
                        ContextMenuItem(
                            readI18n("multiplayer.room.kick")
                        ) {
                            room.kickPlayer(player)
                        }
                    )
                }
            }
        },
        content = content
    )
}