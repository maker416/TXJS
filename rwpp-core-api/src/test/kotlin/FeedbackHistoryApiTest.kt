/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
import io.github.rwpp.diagnostics.LogArchive
import io.github.rwpp.net.feedback.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipOutputStream
import kotlin.test.*

class FeedbackHistoryApiTest {
    private val id = "12345678-1234-4321-8321-123456789abc"
    private val credentials = FeedbackCredentials("test-public-app-key", "test-account-token")
    private val statusJson = """{"id":"$id","status":"investigating","received_at":"2026-11-15T08:00:00Z","updated_at":"2026-11-15T09:00:00Z","public_reply":"正在排查"}"""
    private val detailJson = """{"id":"$id","title":"进房黑屏","description":"加入房间后黑屏","status":"investigating","received_at":"2026-11-15T08:00:00Z","public_reply":"正在排查"}"""

    @Test fun accountHistoryAndDetailsUseUasHeadersWhileStatusRemainsAnonymous() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[$detailJson],"total":51,"next_offset":50}"""))
            server.enqueue(MockResponse().setBody(detailJson))
            server.enqueue(MockResponse().setBody(statusJson))
            server.startLoopback()
            val client = FeedbackClient()
            val base = server.loopbackUrl()
            val page = client.mine(base, credentials)
            assertEquals(51, page.total)
            assertEquals(50, page.nextOffset)
            assertEquals("进房黑屏", page.items.single().title)
            assertEquals(id, client.detail(base, credentials, id).id)
            assertEquals("正在排查", client.status(base, id).publicReply)
            for (path in listOf("/v1/feedback/mine?offset=0", "/v1/feedback/$id")) {
                val request = server.takeRequest()
                assertEquals(path, request.path)
                assertEquals("Bearer test-account-token", request.getHeader("Authorization"))
                assertEquals("test-public-app-key", request.getHeader("X-App-Key"))
            }
            val status = server.takeRequest()
            assertEquals("/v1/feedback/$id/status", status.path)
            assertNull(status.getHeader("Authorization"))
            assertNull(status.getHeader("X-App-Key"))
        }
    }

    @Test fun authenticatedUploadKeepsOriginalMultipartContract() = runBlocking {
        val file = Files.createTempFile("feedback-account-test-", ".zip").toFile()
        ZipOutputStream(file.outputStream()).close()
        LogArchive(file, listOf("2026-11-15")).use { archive ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"id":"$id","received_at":"2026-11-15T08:00:00Z"}"""))
                server.startLoopback()
                val report = FeedbackReport(id, "标题", "问题描述", "", "desktop", "test", "test", "test", "2026-11-15T08:00:00Z", archive.days)
                assertEquals(id, FeedbackClient().submit(server.loopbackUrl(), report, archive, credentials).id)
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/v1/feedback", request.path)
                assertEquals("Bearer test-account-token", request.getHeader("Authorization"))
                assertEquals("test-public-app-key", request.getHeader("X-App-Key"))
                val body = request.body.readUtf8()
                assertTrue(body.contains("name=\"metadata\""))
                assertTrue(body.contains("filename=\"logs.zip\""))
                assertFalse(body.contains("test-account-token"))
            }
        }
    }

    @Test fun refusesRemotePlaintextAndDoesNotLeakCredentialsThroughProxy() = runBlocking {
        for (base in listOf("http://feedback.example", "http://kubernetes.docker.internal", "http://localhost.example")) {
            assertFailsWith<IllegalArgumentException> { FeedbackClient().mine(base, credentials) }
        }
        MockWebServer().use { destination ->
            MockWebServer().use { proxy ->
                destination.enqueue(MockResponse().setBody("""{"items":[],"total":0,"next_offset":0}"""))
                destination.startLoopback(); proxy.startLoopback()
                val http = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port))).build()
                FeedbackClient(http).mine(destination.loopbackUrl(), credentials)
                assertEquals("Bearer test-account-token", destination.takeRequest().getHeader("Authorization"))
                assertNull(proxy.takeRequest(100, TimeUnit.MILLISECONDS))
            }
        }
    }

    @Test fun uploadRejectsWrongReceiptAndNeverRetriesAuthenticationFailureAsAnonymous() = runBlocking {
        val file = Files.createTempFile("feedback-receipt-test-", ".zip").toFile()
        ZipOutputStream(file.outputStream()).close()
        LogArchive(file, listOf("2026-11-15")).use { archive ->
            MockWebServer().use { server ->
                server.startLoopback()
                val report = FeedbackReport(id, "标题", "问题描述", "", "desktop", "test", "test", "test", "2026-11-15T08:00:00Z", archive.days)
                val client = FeedbackClient()
                server.enqueue(MockResponse().setBody("""{"id":"another-feedback","received_at":"2026-11-15T08:00:00Z"}"""))
                assertFailsWith<FeedbackException> { client.submit(server.loopbackUrl(), report, archive, credentials) }
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                server.enqueue(MockResponse().setResponseCode(401))
                assertEquals(401, assertFailsWith<FeedbackException> { client.submit(server.loopbackUrl(), report, archive, credentials) }.statusCode)
                assertEquals("Bearer test-account-token", server.takeRequest().getHeader("Authorization"))
                assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun redirectsAreNotFollowedAndErrorsKeepStatusForRecoveryUi() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { sink ->
                server.startLoopback(); sink.startLoopback()
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", sink.loopbackUrl("/stolen")))
                val client = FeedbackClient()
                val redirect = assertFailsWith<FeedbackException> { client.mine(server.loopbackUrl(), credentials) }
                assertEquals(302, redirect.statusCode)
                assertNull(sink.takeRequest(100, TimeUnit.MILLISECONDS))
                server.enqueue(MockResponse().setResponseCode(401))
                assertEquals(401, assertFailsWith<FeedbackException> { client.mine(server.loopbackUrl(), credentials) }.statusCode)
                server.enqueue(MockResponse().setResponseCode(404))
                assertEquals(404, assertFailsWith<FeedbackException> { client.status(server.loopbackUrl(), id) }.statusCode)
            }
        }
    }

    @Test fun rejectsMismatchedReceiptsAndOversizedStatusResponses(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.startLoopback()
            val base = server.loopbackUrl()
            server.enqueue(MockResponse().setBody(statusJson.replace(id, "22345678-1234-4321-8321-123456789abc")))
            assertFailsWith<FeedbackException> { FeedbackClient().status(base, id) }
            server.enqueue(MockResponse().setBody(" ".repeat(32 * 1024)))
            assertFailsWith<FeedbackException> { FeedbackClient().status(base, id) }
            assertFailsWith<IllegalArgumentException> { FeedbackClient().status(base, "../admin") }
        }
    }

    // MockWebServer.url() uses reverse DNS: Docker's hosts entry can turn 127.0.0.1 into
    // kubernetes.docker.internal. Bind and address the literal loopback used by the HTTP exception.
    private fun MockWebServer.startLoopback() = start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
    private fun MockWebServer.loopbackUrl(path: String = "/") = "http://127.0.0.1:$port$path"
}
