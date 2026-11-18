/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.games.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.*

class GamePortalClientTest {
    private val app = "11111111-1111-1111-1111-111111111111"
    private fun response(data: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"code":"ok","data":$data}""")

    @Test fun officialRequestsBindGameRevisionAndNonceWithoutWebsiteCredentials(): Unit = runBlocking {
        MockWebServer().use { server ->
            val client = GamePortalClient("http://127.0.0.1:${server.port}", "client-key")
            server.enqueue(response("""{"account_id":"200","nickname":"Player"}"""))
            val player = client.player("synthetic-token", app, 7)
            assertEquals("200", player["account_id"]!!.jsonPrimitive.content)
            val nonce = "n".repeat(32)
            server.enqueue(response("""{"code":"${"a".repeat(64)}","expires_at":${System.currentTimeMillis()/1000+60},"game_app_id":"$app"}"""))
            val result = client.authorize("synthetic-token", app, 7, nonce)
            assertEquals(app, result["game_app_id"]!!.jsonPrimitive.content)
            val get = server.takeRequest()
            assertEquals("/api/v1/client/game-portals/$app/player?portal_revision=7", get.path)
            assertEquals("Bearer synthetic-token", get.getHeader("Authorization"))
            assertEquals("client-key", get.getHeader("X-App-Key"))
            val post = server.takeRequest()
            assertEquals("/api/v1/client/game-portals/$app/identity", post.path)
            val body = Json.parseToJsonElement(post.body.readUtf8()).jsonObject
            assertEquals(setOf("nonce", "portal_revision"), body.keys)
            assertEquals(nonce, body["nonce"]!!.jsonPrimitive.content)
            assertEquals(7, body["portal_revision"]!!.jsonPrimitive.int)
        }
    }

    @Test fun unsafeEndpointsAndInvalidNonceAreRejectedBeforeNetwork(): Unit = runBlocking {
        assertFailsWith<GamePortalException> { GamePortalClient("http://public.example/", "key") }
        assertFalse(isOnlineGamePortalUrl("https://user:pass@game.example/"))
        assertFalse(isOnlineGamePortalUrl("javascript:alert(1)"))
        assertFalse(isOnlineGamePortalUrl("http://192.168.1.1/"))
        assertEquals("https://game.example:8443", onlineGamePortalOrigin("https://game.example:8443/play?a=b"))
        assertTrue(isOnlineGamePortalUrl("http://127.0.0.1:18081/portal"))
        assertTrue(isOnlineGamePortalUrl("http://127.20.30.40:18082/portal?lang=zh#queue"))
        assertTrue(isOnlineGamePortalUrl("https://game.example/portal#servers"))
        MockWebServer().use { server ->
            val client = GamePortalClient("http://127.0.0.1:${server.port}", "key")
            for (nonce in listOf("", "x".repeat(31), "x".repeat(129), "汉".repeat(32), "https://evil.example")) {
                assertFailsWith<GamePortalException> { client.authorize("token", app, 1, nonce) }
            }
            assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun credentialRedirectsAndWrongGameCodesFailClosed(): Unit = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            val client = GamePortalClient("http://127.0.0.1:${server.port}", "key")
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal")))
            assertFailsWith<GamePortalException> { client.player("token", app, 1) }
            assertNull(other.takeRequest(50, TimeUnit.MILLISECONDS))
            server.enqueue(response("""{"code":"${"a".repeat(64)}","expires_at":${System.currentTimeMillis()/1000+60},"game_app_id":"another"}"""))
            assertFailsWith<GamePortalException> { client.authorize("token", app, 1, "n".repeat(32)) }
            server.enqueue(response("""{"account_id":"200","nickname":"${"x".repeat(131073)}"}"""))
            assertFailsWith<GamePortalException> { client.player("token", app, 1) }
        } }
    }
}
