/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.appearance.AppearanceClient
import io.github.rwpp.net.appearance.GameTestIdentity
import io.github.rwpp.net.games.GameTestAccess
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import java.io.IOException
import java.util.concurrent.TimeUnit

class AppearanceClientTest {
    private val challenge = "a".repeat(64)
    private val observer = "b".repeat(64)
    private val nonce = "c".repeat(32)
    private fun response(data: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"code":"ok","data":$data}""")

    @Test fun originalAppKeyIsOnlySentOnOfficialClaim() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"server_id":"official"}"""))
            server.enqueue(response("""{"server_id":"official","room_instance_id":"room","connection_id":"connection","client_nonce":"$nonce","expires_at":120}"""))
            server.enqueue(response("""{"ticket":"${"d".repeat(64)}","expires_at":60}"""))
            server.enqueue(response("""{"server_id":"official","room_instance_id":"room","connection_id":"connection","revision":3,"expires_at":15,"members":[{"connection_id":"other","slot":2,"name":"Guest","account_id":"123","expires_at":120}]}"""))
            server.enqueue(response("""{"revision":5,"expires_at":60,"assignments":[{"account_id":"123","slot":"room.player.background","variant_id":"gold"}]}"""))
            val client = AppearanceClient("http://127.0.0.1:${server.port}")
            assertEquals("official", client.lookupServer("127.0.0.1:5123"))
            assertEquals("room", client.inspectChallenge(challenge, observer).roomInstanceId)
            assertEquals("d".repeat(64), client.claim(challenge, nonce, "official", "txjs-app", "uas-token").ticket)
            assertEquals("123", client.members("room", observer).members.single().accountId)
            assertEquals("gold", client.resolve("pack", "e".repeat(64), listOf("123")).assignments.single().variantId)
            val requests = List(5) { server.takeRequest(1, TimeUnit.SECONDS)!! }
            requests.forEachIndexed { index, request ->
                assertEquals(if (index == 2) "Bearer uas-token" else null, request.getHeader("Authorization"))
                assertEquals(if (index == 2) "txjs-app" else null, request.getHeader("X-App-Key"))
            }
            assertEquals("127.0.0.1:5123", requests[0].requestUrl?.queryParameter("endpoint"))
            val claim = requests[2].body.readUtf8()
            assertEquals("""{"challenge_id":"$challenge","client_nonce":"$nonce","server_id":"official"}""", claim)
        }
    }

    @Test fun redirectsCannotReceiveAccountCredentials() {
        MockWebServer().use { official -> MockWebServer().use { other ->
            official.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/steal")))
            val client = AppearanceClient("http://127.0.0.1:${official.port}")
            assertFailsWith<IOException> { client.claim(challenge, nonce, "official", "app", "secret") }
            assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
        } }
    }

    @Test fun testIdentityUsesOnlyTheIssuingPlatformAndSelectedRoom() {
        val game = "11111111-1111-1111-1111-111111111111"
        MockWebServer().use { official -> MockWebServer().use { other ->
            val base = "http://127.0.0.1:${official.port}"
            val client = AppearanceClient(base)
            val who = GameTestIdentity(game, "node", GameTestAccess(observer, System.currentTimeMillis() / 1000 + 60, base), "app", "uas")
            official.enqueue(response("""{"server_id":"node","game_app_id":"$game"}"""))
            official.enqueue(response("""{"ticket":"$challenge","expires_at":60}"""))
            assertEquals("node", client.lookupServerInfo("1.1.1.1:5123", who)?.serverId)
            assertEquals(challenge, client.claim(challenge, nonce, "node", "app", "uas", who).ticket)
            repeat(2) {
                val request = official.takeRequest()
                assertEquals(observer, request.getHeader("X-Game-Test-Access"))
                assertEquals("app", request.getHeader("X-App-Key"))
                assertEquals("Bearer uas", request.getHeader("Authorization"))
            }
            official.enqueue(response("""{"server_id":"other","game_app_id":"$game"}"""))
            assertFailsWith<IOException> { client.lookupServerInfo("1.1.1.1:5123", who) }
            assertFailsWith<IOException> { client.claim(challenge, nonce, "node", "app", "switched-account", who) }
            assertFailsWith<IOException> { client.claim(challenge, nonce, "other", "app", "uas", who) }
            val expired = GameTestIdentity(game, "node", GameTestAccess(observer, 1, base), "app", "uas")
            assertFailsWith<IOException> { client.lookupServerInfo("1.1.1.1:5123", expired) }
            assertEquals(3, official.requestCount)
            assertFailsWith<IOException> {
                AppearanceClient("http://127.0.0.1:${other.port}").lookupServerInfo("1.1.1.1:5123", who)
            }
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun oldServiceAndUnknownPackFallBackWithoutCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(404))
            val client = AppearanceClient("http://127.0.0.1:${server.port}")
            assertNull(client.lookupServer("127.0.0.1:5123"))
            assertEquals(emptyList(), client.resolve("unknown", challenge, listOf("1")).assignments)
        }
    }

    @Test fun responsesAreBoundedAndPublicHttpIsRejected() {
        assertFailsWith<IllegalArgumentException> { AppearanceClient("http://example.com") }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(256 * 1024 + 1)))
            assertFailsWith<IOException> { AppearanceClient("http://127.0.0.1:${server.port}").lookupServer("127.0.0.1:5123") }
        }
    }

    @Test fun fullRoomsAreBatchedAndMixedRevisionsAreDiscarded() {
        MockWebServer().use { server ->
            val client = AppearanceClient("http://127.0.0.1:${server.port}")
            assertEquals(emptyList(), client.resolve("pack", challenge, emptyList()).assignments)
            assertEquals(0, server.requestCount)
            server.enqueue(response("""{"revision":7,"expires_at":120,"assignments":[]}"""))
            server.enqueue(response("""{"revision":7,"expires_at":110,"assignments":[]}"""))
            assertEquals(110, client.resolve("pack", challenge, (1..65).map(Int::toString)).expiresAt)
            val first = server.takeRequest().body.readUtf8()
            val second = server.takeRequest().body.readUtf8()
            assertEquals(64, Regex("\"[0-9]+\"").findAll(first).count())
            assertEquals(1, Regex("\"[0-9]+\"").findAll(second).count())
            server.enqueue(response("""{"revision":7,"expires_at":120,"assignments":[]}"""))
            server.enqueue(response("""{"revision":8,"expires_at":120,"assignments":[]}"""))
            assertFailsWith<IOException> { client.resolve("pack", challenge, (1..65).map(Int::toString)) }
        }
    }

    @Test fun verifiedEndpointSuppliesGameBindingAndResolveUsesThatGameScope() {
        val gameId = "11111111-1111-1111-1111-111111111111"
        val packId = "22222222-2222-2222-2222-222222222222"
        MockWebServer().use { server ->
            server.enqueue(response("""{"server_id":"game-node","game_app_id":"$gameId","art_binding":{"pack_id":"$packId","version":"1.0","catalog_hash":"$challenge"}}"""))
            server.enqueue(response("""{"revision":1,"expires_at":60,"assignments":[]}"""))
            val client = AppearanceClient("http://127.0.0.1:${server.port}")
            val identity = client.lookupServerInfo("127.0.0.1:5123")!!
            assertEquals(gameId, identity.gameAppId)
            assertEquals(packId, identity.artBinding?.packId)
            client.resolve(packId, challenge, listOf("123"), identity.gameAppId)
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals(true, request.body.readUtf8().contains("\"game_app_id\":\"$gameId\""))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Identity-Secret"))
        }
    }
}
