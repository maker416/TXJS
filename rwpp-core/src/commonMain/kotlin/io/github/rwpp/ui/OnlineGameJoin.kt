/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.net.games.OnlineGame
import io.github.rwpp.expansion.ExpansionBinding
import io.github.rwpp.content.PortalExpansionHandle
import java.io.IOException

/** One approved endpoint and one prepared appearance generation, consumed by the existing join flow. */
data class OnlineGameJoinRequest(
    val appId: String, val serverId: String, val endpoint: String, val themeToken: Long?,
    val expansionBinding: ExpansionBinding? = null,
    val preloadedExpansion: PortalExpansionHandle? = null,
)

internal class OnlineGameChangedException(val game: OnlineGame) : IOException("Game details changed")

/** Re-read approval and availability before preparing content; no cached directory entry can grant admission. */
internal suspend fun prepareOnlineGameJoin(
    expected: OnlineGame,
    serverId: String,
    useArt: Boolean,
    refresh: suspend (String) -> OnlineGame?,
    prepare: suspend (OnlineGame, Boolean) -> Long,
): OnlineGameJoinRequest {
    val game = refresh(expected.appId)?.takeIf { it.appId == expected.appId }
        ?: throw IOException("Game unavailable")
    if (!game.available) throw OnlineGameChangedException(game)
    val server = game.servers.singleOrNull { it.serverId == serverId && it.available }
        ?: throw OnlineGameChangedException(game)
    if (server.endpoint != expected.servers.singleOrNull { it.serverId == serverId }?.endpoint ||
        game.expansionBinding != expected.expansionBinding ||
        useArt && game.artBinding != expected.artBinding) throw OnlineGameChangedException(game)
    game.expansionBinding?.let { binding ->
        if (!useArt) throw IOException("The complete game expansion is required")
        return OnlineGameJoinRequest(game.appId, server.serverId, server.endpoint, null, binding)
    }
    return OnlineGameJoinRequest(game.appId, server.serverId, server.endpoint, prepare(game, useArt))
}
