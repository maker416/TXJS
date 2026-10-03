/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.net.account.*
import kotlinx.coroutines.runBlocking
import net.peanuuutz.tomlkt.Toml
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ForumSsoClientTest {
    @Test fun proofHasS256ChallengeAndIndependentRandomVerifiers() {
        val proof = ForumProof.create()
        assertEquals(43, proof.verifier.length)
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(proof.verifier.toByteArray())), proof.challenge)
        assertNotEquals(proof.verifier, ForumProof.create().verifier)
    }

    @Test fun nativeHandoffPreservesCsrfAndKeepsBearerOffForumRequests() = runBlocking {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return when (request.path) {
                    "/sso/client/config" -> MockResponse().setBody("""{"protocol":1,"target_app_code":"forum"}""")
                    "/sso/client" -> MockResponse().addHeader("X-CSRF-Token", "csrf-proof").addHeader("Set-Cookie", "native_session=separate; Path=/; HttpOnly").setBody("bootstrap")
                    "/api/v1/sso/forum/tickets" -> MockResponse().setBody("""{"ticket":"${"a".repeat(64)}","expires_in":60}""")
                    "/sso/client/prepare" -> MockResponse().setBody("""{"handoff":"${"b".repeat(64)}","logout_key":"${"c".repeat(64)}","expires_in":60}""")
                    "/sso/client/revoke" -> MockResponse().setBody("""{"ok":true}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val http = OkHttpClient()
            val forum = ForumSsoClient(server.url("/").toString(), http)
            val config = forum.config()
            val proof = ForumProof.create()
            val ticket = AccountApiClient(server.url("/").toString(), "source-app", http).issueForumTicket("source-token", config.targetAppCode, proof.challenge)
            val prepared = forum.prepare(ticket.ticket, proof.verifier)
            forum.revoke(prepared.logoutKey)
            val uasRequest = requests.single { it.path == "/api/v1/sso/forum/tickets" }
            assertEquals("Bearer source-token", uasRequest.getHeader("Authorization"))
            assertTrue(uasRequest.body.readUtf8().contains(proof.challenge))
            val forumRequests = requests.filter { it.path!!.startsWith("/sso/client") }
            assertTrue(forumRequests.all { it.getHeader("Authorization") == null && it.getHeader("X-App-Secret") == null })
            forumRequests.filter { it.method == "POST" }.forEach {
                assertEquals("csrf-proof", it.getHeader("X-CSRF-Token"))
                assertEquals("native_session=separate", it.getHeader("Cookie"))
                assertFalse(it.body.clone().readUtf8().contains("source-token"))
            }
        } finally { server.shutdown() }
    }

    @Test fun redirectsCannotForwardTicketToAnotherSite() = runBlocking {
        val server = MockWebServer(); val other = MockWebServer()
        server.start(); other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/stolen")))
            assertFailsWith<IllegalStateException> { ForumSsoClient(server.url("/").toString(), OkHttpClient()).config() }
            assertEquals(0, other.requestCount)
        } finally { server.shutdown(); other.shutdown() }
    }

    @Test fun publicCleartextAndUrlCredentialsAreRejected() {
        assertFailsWith<IllegalArgumentException> { ForumSsoClient("http://example.com", OkHttpClient()) }
        assertFailsWith<IllegalArgumentException> { ForumSsoUrls.base("https://user:password@example.com") }
        assertFailsWith<IllegalArgumentException> { ForumSsoUrls.base("https://example.com/?ticket=secret") }
        assertFailsWith<IllegalArgumentException> { ForumSsoUrls.base("https://example.com/#secret") }
        ForumSsoUrls.requireSecure("http://localhost:8080")
        ForumSsoUrls.requireSecure("https://example.com/forum")
    }

    @Test fun revocationQueueSurvivesAccountConfigurationPersistence() {
        val prefs = AccountPreferences(forumRevocations = listOf(ForumClientRevocation("https://forum.example.com", "a".repeat(64))))
        val encoded = Toml.encodeToString(AccountPreferences.serializer(), prefs)
        assertEquals(prefs.forumRevocations, Toml.decodeFromString(AccountPreferences.serializer(), encoded).forumRevocations)
    }
}
