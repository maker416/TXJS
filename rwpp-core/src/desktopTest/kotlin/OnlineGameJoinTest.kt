/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.net.games.OnlineGame
import io.github.rwpp.net.games.OnlineGameServer
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.*

class OnlineGameJoinTest {
    private val game = OnlineGame("game-id", "Published", available = true,
        servers = listOf(OnlineGameServer("node-1", "Server", "127.0.0.1:5123", available = true, online = true)))

    @Test fun withdrawnGameAndOfflineNodeCannotPrepareAppearanceOrConnect(): Unit = runBlocking {
        var prepared = 0
        val prepare: suspend (OnlineGame, Boolean) -> Long = { _, _ -> prepared++; 7 }
        for (fresh in listOf(null, game.copy(available = false), game.copy(appId = "another-game"),
            game.copy(servers = game.servers.map { it.copy(available = false) }),
            game.copy(servers = game.servers.map { it.copy(online = false) }), game.copy(servers = emptyList()))) {
            assertFailsWith<IOException> { prepareOnlineGameJoin(game, "node-1", true, { fresh }, prepare) }
        }
        assertEquals(0, prepared)
    }

    @Test fun changedApprovedEndpointRequiresReviewBeforePreparingOrConnecting(): Unit = runBlocking {
        val fresh = game.copy(servers = listOf(game.servers.single().copy(endpoint = "127.0.0.1:6000")))
        var received: OnlineGame? = null
        val error = assertFailsWith<OnlineGameChangedException> {
            prepareOnlineGameJoin(game, "node-1", true, { fresh }) { _, _ -> error("Must not prepare changed details") }
        }
        assertSame(fresh, error.game)
        val request = prepareOnlineGameJoin(fresh, "node-1", true, { fresh }) { current, art ->
            assertTrue(art)
            received = current
            42
        }
        assertSame(fresh, received)
        assertEquals("127.0.0.1:6000", request.endpoint)
        assertEquals(42L, request.themeToken)
        assertEquals("node-1", request.serverId)
    }

    @Test fun explicitDefaultChoiceAndPreparationFailureDoNotCreatePendingJoin(): Unit = runBlocking {
        val request = prepareOnlineGameJoin(game, "node-1", false, { game }) { _, art ->
            assertFalse(art)
            11
        }
        assertEquals(11L, request.themeToken)
        assertFailsWith<IOException> {
            prepareOnlineGameJoin(game, "node-1", true, { game }) { _, _ -> throw IOException("Cannot install pack") }
        }
    }

    @Test fun changedArtVersionCannotBeAppliedWithoutRenewedConsent(): Unit = runBlocking {
        val binding = io.github.rwpp.net.games.OnlineGameArtBinding("pack", "1.0", "a".repeat(64))
        val old = game.copy(artBinding = binding)
        val fresh = game.copy(artBinding = binding.copy(version = "2.0", catalogHash = "b".repeat(64)))
        val error = assertFailsWith<OnlineGameChangedException> {
            prepareOnlineGameJoin(old, "node-1", true, { fresh }) { _, _ -> error("Consent required") }
        }
        assertSame(fresh, error.game)
        // The explicit default choice does not download or apply either version.
        val request = prepareOnlineGameJoin(old, "node-1", false, { fresh }) { _, art -> assertFalse(art); 12 }
        assertEquals(12L, request.themeToken)
    }

    @Test fun mandatoryExpansionUsesDirectAndCannotChooseDefaultArt(): Unit = runBlocking {
        val expansion = io.github.rwpp.expansion.ExpansionBinding("1.0", "a".repeat(64), 4096,
            io.github.rwpp.expansion.ExpansionArtBinding("22222222-2222-2222-2222-222222222222", "art-1", "b".repeat(64)))
        val mandatory = game.copy(expansionBinding = expansion)
        val request = prepareOnlineGameJoin(mandatory, "node-1", true, { mandatory }) { _, _ -> error("Must not separately prepare the theme") }
        assertNull(request.themeToken)
        assertEquals(expansion, request.expansionBinding)
        assertFailsWith<IOException> {
            prepareOnlineGameJoin(mandatory, "node-1", false, { mandatory }) { _, _ -> error("Default bypass") }
        }
        assertFailsWith<OnlineGameChangedException> {
            prepareOnlineGameJoin(mandatory, "node-1", true, { mandatory.copy(expansionBinding = expansion.copy(version = "2.0")) }) { _, _ -> 1 }
        }
    }
}
