/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.games.OnlineGameClient
import io.github.rwpp.net.games.isOnlineGameAssetUrl
import io.github.rwpp.net.games.isOnlineGameEndpoint
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.*

class OnlineGameClientTest {
    private val app = "11111111-1111-1111-1111-111111111111"
    private val pack = "22222222-2222-2222-2222-222222222222"
    private fun game(endpoint: String = "127.0.0.1:5123", name: String = "Published game",
                     appId: String = app, binding: Boolean = true): String = """{
        "app_id":"$appId","name":"$name","description":"Reviewed","available":true,
        "servers":[{"server_id":"node-1","name":"Main server","endpoint":"$endpoint","online":true,
          "available":true,"current_players":2,"max_players":10}],
        "art_binding":${if (!binding) "null" else """{"pack_id":"$pack","version":"1.0",
          "catalog_hash":"${"a".repeat(64)}","download_url":"https://assets.example/game.rwtheme",
          "archive_sha256":"${"b".repeat(64)}"}"""}}
        """
    private fun response(data: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"code":"ok","data":$data}""")

    @Test fun directoryAndFreshDetailsArePublicAndPreserveBinding() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"games":[${game()}]}"""))
            server.enqueue(response(game()))
            val client = OnlineGameClient("http://127.0.0.1:${server.port}")
            val listed = client.games().single()
            assertEquals(app, listed.appId)
            assertEquals(pack, listed.artBinding?.packId)
            assertEquals("a".repeat(64), listed.artBinding?.catalogHash)
            assertTrue(listed.servers.single().online)
            assertEquals(listed, client.game(app))
            val requests = List(2) { server.takeRequest() }
            assertEquals("/api/v1/games", requests[0].path)
            assertEquals("/api/v1/games/$app", requests[1].path)
            requests.forEach {
                assertNull(it.getHeader("Authorization"))
                assertNull(it.getHeader("X-App-Key"))
                assertNull(it.getHeader("X-Identity-Secret"))
            }
        }
    }

    @Test fun oldPlatformAndRemovedGameHaveNoFakeEntries() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(404))
            val client = OnlineGameClient("http://127.0.0.1:${server.port}")
            assertTrue(client.games().isEmpty())
            assertNull(client.game(app))
        }
    }

    @Test fun redirectsAndOversizedPayloadsAreRejected() {
        assertFailsWith<IllegalArgumentException> { OnlineGameClient("http://example.com") }
        MockWebServer().use { server -> MockWebServer().use { other ->
            val client = OnlineGameClient("http://127.0.0.1:${server.port}")
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/games")))
            assertFailsWith<IOException> { client.games() }
            assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1)))
            assertFailsWith<IOException> { client.games() }
        } }
    }

    @Test fun malformedOrMismatchingEntriesCannotBecomeJoinTargets() {
        MockWebServer().use { server ->
            val client = OnlineGameClient("http://127.0.0.1:${server.port}")
            for (endpoint in listOf("Qnews", "evil.example:5123", "127.0.0.1:5123/command", "127.0.0.1:65536")) {
                server.enqueue(response("""{"games":[${game(endpoint)}]}"""))
                assertFailsWith<IOException>(endpoint) { client.games() }
            }
            server.enqueue(response("""{"games":[${game()},${game()}]}"""))
            assertFailsWith<IOException> { client.games() }
            server.enqueue(response(game(appId = pack)))
            assertFailsWith<IOException> { client.game(app) }
            server.enqueue(response("""{"games":[${game().replace("https://assets.example/game.rwtheme", "http://assets.example/game.rwtheme")}]}"""))
            assertFailsWith<IOException> { client.games() }
        }
    }

    @Test fun maintenanceAndUnboundGamesRemainVisibleWithoutClaimingAvailability() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"games":[${game(binding = false).replace("\"available\":true", "\"available\":false")}]}"""))
            val result = OnlineGameClient("http://127.0.0.1:${server.port}").games().single()
            assertFalse(result.available)
            assertFalse(result.servers.single().available)
            assertNull(result.artBinding)
        }
    }

    @Test fun literalSocketAndAssetValidationNeverNeedsDns() {
        listOf("127.0.0.1:5123", "203.0.113.4:1", "[2001:db8::1]:5123", "[::1]:65535").forEach {
            assertTrue(isOnlineGameEndpoint(it), it)
        }
        listOf("host.example:5123", "Q12345", "256.1.2.3:5123", "127.0.0.1:0", "127.0.0.1:5123\n",
            "[::1%eth0]:5123", "[not-an-ip]:5123", "127.0.0.1:5123?x=y").forEach {
            assertFalse(isOnlineGameEndpoint(it), it)
        }
        assertTrue(isOnlineGameAssetUrl("https://assets.example/pack.rwtheme?version=1"))
        assertTrue(isOnlineGameAssetUrl("http://127.0.0.1:1234/pack.rwtheme"))
        assertFalse(isOnlineGameAssetUrl("https://user:password@assets.example/pack.rwtheme"))
        assertFalse(isOnlineGameAssetUrl("file:///pack.rwtheme"))
        assertFalse(isOnlineGameAssetUrl("http://192.168.1.2/pack.rwtheme"))
    }

    @Test fun platformUnicodeAndPlayerLimitsAreAcceptedWithoutTruncating() {
        MockWebServer().use { server ->
            val name = "🚀".repeat(128)
            server.enqueue(response("""{"games":[${game(name = name).replace("\"current_players\":2", "\"current_players\":10000")
                .replace("\"max_players\":10", "\"max_players\":10000")}]}"""))
            val result = OnlineGameClient("http://127.0.0.1:${server.port}").games().single()
            assertEquals(name, result.name)
            assertEquals(10000, result.servers.single().maxPlayers)
        }
    }

    @Test fun mandatoryExpansionIsPreservedAndCannotDisagreeWithEmbeddedArt() {
        val art = """{"pack_id":"$pack","version":"1.0","catalog_hash":"${"a".repeat(64)}"}"""
        val expansion = """{"version":"2.0","sha256":"${"c".repeat(64)}","size":4096,"art_binding":$art}"""
        val base = game(binding = false).trim().removeSuffix("}") + """, "art_binding":$art,"expansion_binding":$expansion}"""
        // Build a single art_binding field rather than relying on duplicate JSON-key behavior.
        val valid = base.replace("\"art_binding\":null,", "")
        MockWebServer().use { server ->
            val client = OnlineGameClient("http://127.0.0.1:${server.port}")
            server.enqueue(response(valid))
            assertEquals("2.0", client.game(app)?.expansionBinding?.version)
            server.enqueue(response(valid.replace("\"size\":4096", "\"size\":1073741825")))
            assertFailsWith<IOException> { client.game(app) }
            server.enqueue(response(valid.replace("\"art_binding\":$art,", "\"art_binding\":null,")))
            assertFailsWith<IOException> { client.game(app) }
        }
    }
}
