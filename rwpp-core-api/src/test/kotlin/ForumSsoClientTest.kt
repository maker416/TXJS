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
import java.net.InetAddress
import java.net.Proxy
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
        val server = loopbackServer()
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
        try {
            val http = httpWithLocalDns()
            val forum = ForumSsoClient(server.loopbackUrl(), http)
            val config = forum.config()
            val proof = ForumProof.create()
            // UAS retains its deployed HTTP compatibility; the forum uses the explicit loopback exception.
            val sourceUrl = server.url("/").newBuilder().host("uas.example.test").build().toString()
            val ticket = AccountApiClient(sourceUrl, "source-app", http).issueForumTicket("source-token", config.targetAppCode, proof.challenge)
            val prepared = forum.prepare(ticket.ticket, proof.verifier)
            forum.revoke(prepared.logoutKey)
            val uasRequest = requests.single { it.path == "/api/v1/sso/forum/tickets" }
            assertEquals("Bearer source-token", uasRequest.getHeader("Authorization"))
            assertEquals("uas.example.test:${server.port}", uasRequest.getHeader("Host"))
            assertEquals("http", uasRequest.requestUrl!!.scheme)
            assertTrue(uasRequest.body.readUtf8().contains(proof.challenge))
            val forumRequests = requests.filter { it.path!!.startsWith("/sso/client") }
            assertTrue(forumRequests.all {
                it.getHeader("Authorization") == null && it.getHeader("X-App-Key") == null && it.getHeader("X-App-Secret") == null
            })
            forumRequests.filter { it.method == "POST" }.forEach {
                assertEquals("csrf-proof", it.getHeader("X-CSRF-Token"))
                assertEquals("native_session=separate", it.getHeader("Cookie"))
                assertFalse(it.body.clone().readUtf8().contains("source-token"))
            }
        } finally { server.shutdown() }
    }

    @Test fun redirectsCannotForwardTicketToAnotherSite() = runBlocking {
        val server = loopbackServer(); val other = loopbackServer()
        try {
            val forum = ForumSsoClient(server.loopbackUrl(), httpWithLocalDns())
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.loopbackUrl("/stolen")))
            val configFailure = assertFailsWith<ForumSsoHttpException> { forum.config() }
            assertEquals(302, configFailure.statusCode)
            assertEquals(0, other.requestCount)

            val ticket = "a".repeat(64)
            val verifier = "b".repeat(43)
            server.enqueue(MockResponse().setHeader("X-CSRF-Token", "csrf-proof"))
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", other.loopbackUrl("/stolen")))
            val prepareFailure = assertFailsWith<ForumSsoHttpException> { forum.prepare(ticket, verifier) }
            assertEquals(307, prepareFailure.statusCode)
            assertEquals("/sso/client/config", server.takeRequest().path)
            assertEquals("/sso/client", server.takeRequest().path)
            val prepare = server.takeRequest()
            assertEquals("POST", prepare.method)
            assertEquals("/sso/client/prepare", prepare.path)
            val body = prepare.body.readUtf8()
            assertTrue(body.contains(ticket) && body.contains(verifier))
            assertEquals(0, other.requestCount, "A redirect must never replay the ticket and verifier at another site")
        } finally { server.shutdown(); other.shutdown() }
    }

    @Test fun publicCleartextAndUrlCredentialsAreRejected() {
        assertFailsWith<ForumSsoRequiresHttpsException> { ForumSsoClient("http://example.com", OkHttpClient()) }
        // A hostname resolving to loopback must not bypass the literal-loopback HTTP restriction.
        assertFailsWith<ForumSsoRequiresHttpsException> { ForumSsoClient("http://loopback-alias.example.test", httpWithLocalDns()) }
        assertFailsWith<IllegalArgumentException> { ForumSsoUrls.base("https://user:password@example.com") }
        assertFailsWith<IllegalArgumentException> { ForumSsoUrls.base("https://example.com/?ticket=secret") }
        assertFailsWith<IllegalArgumentException> { ForumSsoUrls.base("https://example.com/#secret") }
        ForumSsoUrls.requireSecure("http://localhost:8080")
        ForumSsoUrls.requireSecure("https://example.com/forum")
    }

    @Test fun httpUasRedirectCannotForwardBearerAndUrlCredentialsRemainInvalid() = runBlocking {
        val uas = loopbackServer(); val other = loopbackServer()
        try {
            val http = httpWithLocalDns()
            val sourceUrl = uas.url("/").newBuilder().host("uas.example.test").build().toString()
            uas.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.loopbackUrl("/stolen")))
            assertFailsWith<AccountApiException> { AccountApiClient(sourceUrl, "source-app", http).issueForumTicket("source-token", "forum", ForumProof.create().challenge) }
            assertEquals(0, other.requestCount)
            assertEquals("Bearer source-token", uas.takeRequest().getHeader("Authorization"))
            for (invalid in listOf("http://user:password@uas.example.test", "http://uas.example.test/?token=secret", "http://uas.example.test/#token")) {
                assertFailsWith<IllegalArgumentException> { AccountApiClient(invalid, "source-app", http).issueForumTicket("source-token", "forum", ForumProof.create().challenge) }
            }
            assertEquals(1, uas.requestCount)
        } finally { uas.shutdown(); other.shutdown() }
    }

    @Test fun revocationQueueSurvivesAccountConfigurationPersistence() {
        val prefs = AccountPreferences(forumRevocations = listOf(ForumClientRevocation("https://forum.example.com", "a".repeat(64))))
        val encoded = Toml.encodeToString(AccountPreferences.serializer(), prefs)
        assertEquals(prefs.forumRevocations, Toml.decodeFromString(AccountPreferences.serializer(), encoded).forumRevocations)
    }

    @Test fun forumHttpFailureRetainsStatusWithoutResponseBody() = runBlocking {
        val server = loopbackServer()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setBody("secret-token secret-key"))
            val client = ForumSsoClient(server.loopbackUrl(), httpWithLocalDns())
            val failure = assertFailsWith<ForumSsoHttpException> { client.config() }
            assertEquals(503, failure.statusCode)
            assertFalse(failure.message.orEmpty().contains("secret"))
            server.enqueue(MockResponse().setHeader("X-CSRF-Token", "csrf"))
            server.enqueue(MockResponse().setResponseCode(419).setBody("secret-token"))
            val csrfFailure = assertFailsWith<ForumSsoHttpException> { client.prepare("a".repeat(64), "b".repeat(43)) }
            assertEquals(419, csrfFailure.statusCode)
        } finally { server.shutdown() }
    }

    // InetAddress/MockWebServer may reverse-resolve loopback to a Docker or machine alias on Windows.
    // Bind and address the literal loopback so production HTTPS validation is exercised consistently.
    private fun loopbackServer() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

    private fun MockWebServer.loopbackUrl(path: String = "/") =
        url(path).newBuilder().host("127.0.0.1").build().toString()

    private fun httpWithLocalDns() = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }).build()
}
