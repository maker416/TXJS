/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.payment.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PaymentClientTest {
    private val id = "11111111-2222-3333-4444-555555555555"
    private val ticket = "a".repeat(64)
    private fun base(server: MockWebServer) = "http://127.0.0.1:${server.port}"
    private fun response(data: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(data)
    private fun order(payer: String = "123", status: String = "pending") =
        """{"id":"$id","app_id":"22222222-2222-2222-2222-222222222222","app_name":"示例开发者应用","subject":"测试道具","payer_account_id":"$payer","point_type_id":1,"points":100,"status":"$status","expires_at":1900000000}"""

    @Test fun pinOnlyGoesToUasAndConfirmationOnlySendsBoundTicket() = runBlocking {
        MockWebServer().use { uas -> MockWebServer().use { platform ->
            uas.enqueue(response("""{"ticket":"$ticket","expires_at":${System.currentTimeMillis() / 1000 + 60}}"""))
            platform.enqueue(response("""{"code":"ok","data":${order()}}"""))
            platform.enqueue(response("""{"code":"ok","data":${order(status = "processing")}}"""))
            val security = PaymentSecurityClient(base(uas), "client-app")
            val client = PaymentClient(base(platform), "client-app")
            assertEquals(100L, client.order("user-token", id, "123").points)
            val authorization = security.authorize("user-token", id, "001234")
            assertEquals("PaymentAuthorization(redacted)", authorization.toString())
            assertEquals("processing", client.confirm("user-token", id, "123", authorization.ticket).status)
            val secretRequest = uas.takeRequest()
            assertEquals("/api/v1/payment-authorizations", secretRequest.path)
            assertEquals("""{"payment_id":"$id","payment_password":"001234"}""", secretRequest.body.readUtf8())
            val preview = platform.takeRequest()
            val confirm = platform.takeRequest()
            assertEquals("/api/v1/client/payments/$id", preview.path)
            assertEquals("""{"ticket":"$ticket"}""", confirm.body.readUtf8())
            listOf(secretRequest, preview, confirm).forEach {
                assertEquals("Bearer user-token", it.getHeader("Authorization"))
                assertEquals("client-app", it.getHeader("X-App-Key"))
                assertNull(it.getHeader("X-App-Secret"))
            }
        } }
    }

    @Test fun passwordSetupUsesServerBoundEmailAndDistinctPurpose() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("""{"has_password":false,"email_bound":true,"email_masked":"u***@example.test","locked_until":0}"""))
            repeat(4) { server.enqueue(response("""{"ok":true}""")) }
            val client = PaymentSecurityClient(base(server), "app")
            assertFalse(client.status("token").hasPassword)
            client.sendCode("token", false)
            client.setPassword("token", "654321", "001234", false)
            client.sendCode("token", true)
            client.setPassword("token", "654321", "006789", true)
            server.takeRequest()
            assertEquals("""{"purpose":"setup"}""", server.takeRequest().body.readUtf8())
            val setup = server.takeRequest()
            assertEquals("/api/v1/users/payment-password/setup", setup.path)
            assertEquals("""{"code":"654321","password":"001234"}""", setup.body.readUtf8())
            assertEquals("""{"purpose":"reset"}""", server.takeRequest().body.readUtf8())
            assertEquals("/api/v1/users/payment-password/reset", server.takeRequest().path)
        }
    }

    @Test fun payerMismatchUnknownStateAndNegativeAmountsFailClosed() = runBlocking {
        MockWebServer().use { server ->
            val client = PaymentClient(base(server), "app")
            for (invalid in listOf(order("other"), order(status = "delivered"), order().replace("\"points\":100", "\"points\":-1"))) {
                server.enqueue(response("""{"data":$invalid}"""))
                assertFailsWith<PaymentApiException> { client.order("token", id, "123") }
            }
        }
    }

    @Test fun closingIsAnUncertainNonterminalOrderState() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("""{"data":${order(status = "closing")}}"""))
            val value = PaymentClient(base(server), "app").order("token", id, "123")
            assertEquals("closing", value.status)
            assertFalse(value.terminal)
        }
    }

    @Test fun publicHttpAndCredentialBearingUrlsAreRejected() {
        for (url in listOf("http://example.com", "https://u:p@example.com", "https://example.com?redirect=evil", "https://example.com#evil")) {
            assertFailsWith<PaymentApiException> { PaymentSecurityClient(url, "app") }
            assertFailsWith<PaymentApiException> { PaymentClient(url, "app") }
        }
    }

    @Test fun pinAndJwtCannotFollowRedirects() = runBlocking {
        MockWebServer().use { official -> MockWebServer().use { attacker ->
            official.enqueue(MockResponse().setResponseCode(307).setHeader("Location", attacker.url("/steal")))
            assertFailsWith<PaymentApiException> {
                PaymentSecurityClient(base(official), "app").authorize("token", id, "001234")
            }
            assertNull(attacker.takeRequest(100, TimeUnit.MILLISECONDS))
        } }
    }

    @Test fun boundedResponsesAndSafeErrorsDoNotExposeUpstreamBody() = runBlocking {
        MockWebServer().use { server ->
            val client = PaymentSecurityClient(base(server), "app")
            server.enqueue(response("x".repeat(128 * 1024 + 1)))
            assertFailsWith<PaymentApiException> { client.status("token") }
            server.enqueue(response("""{"code":"invalid_payment_password","message":"sensitive reflected input"}""").setResponseCode(403))
            val error = assertFailsWith<PaymentApiException> { client.authorize("token", id, "001234") }
            assertEquals("invalid_payment_password", error.message)
            assertEquals(403, error.httpStatus)
        }
    }

    @Test fun closingPaymentCancelsBlockingHttpImmediately() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("""{"has_password":true}""").setBodyDelay(2, TimeUnit.SECONDS))
            val job = launch(Dispatchers.IO) { PaymentSecurityClient(base(server), "app").status("token") }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            withTimeout(1500) { job.cancelAndJoin() }
        }
    }

    @Test fun onlySixAsciiDigitsAndAnExactChatOrderIdAreAccepted() {
        assertTrue(PaymentRules.validPin("001234"))
        for (pin in listOf("１２３４５６", "12345", "1234567", "123 45")) assertFalse(PaymentRules.validPin(pin))
        assertEquals(id, PaymentRules.chatPayment("[RWJSPAY1] $id"))
        for (message in listOf("[RWJSPAY1] $id points=1", "[RWJSPAY1] https://evil.test", "[RWJSPAY1] ../x", "hello [RWJSPAY1] $id")) {
            assertNull(PaymentRules.chatPayment(message))
        }
    }
}
