/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class R2ModSyncTest {
    private val caps = """{"protocol_version":2,"upload_auth_required":true,"upload_enabled":true,"r2_direct_upload":true,"legacy_upload_enabled":true}"""
    private val credentials = ModSyncCredentials("app-key", "token")
    private val hash = "a".repeat(64)

    @BeforeTest fun setup() {
        io.github.rwpp.logger = org.slf4j.LoggerFactory.getLogger(R2ModSyncTest::class.java)
    }

    private fun ticket(url: String, headers: String = """{"Content-Length":"3","If-None-Match":"*"}""") =
        MockResponse().setBody("""{"upload_id":"stage123","url":"$url","headers":$headers}""")

    private fun loopbackServer() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private fun MockWebServer.localUrl(path: String) = url(path).newBuilder().host("127.0.0.1").build().toString()

    @Test fun ticketAndCompletionUseHostCredentialsWhileFileHasNoAccountHeadersOrCookies() = runBlocking {
        loopbackServer().use { relay -> loopbackServer().use { storage ->
            val http = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Cookie", "session=private").build())
            }.build()
            val c = ModSyncClient(listOf(relay.localUrl("/")), http, credentials)
            relay.enqueue(MockResponse().setBody(caps))
            relay.enqueue(ticket(storage.localUrl("/staging/stage123?signature=private")))
            storage.enqueue(MockResponse().setResponseCode(412)) // a replay of an already stored PUT
            relay.enqueue(MockResponse().setResponseCode(502))
            relay.enqueue(MockResponse().setBody("""{"sha256":"$hash","size":3}"""))

            val snapshot = File.createTempFile("r2-mod-", ".tmp")
            try {
                snapshot.writeText("mod")
                c.uploadFile("code:room", hash, "secret", snapshot)
            } finally { snapshot.delete() }
            assertEquals(1, storage.requestCount)
            val upload = storage.takeRequest()
            assertEquals("PUT", upload.method)
            assertEquals("mod", upload.body.readUtf8())
            assertEquals("*", upload.getHeader("If-None-Match"))
            assertEquals("3", upload.getHeader("Content-Length"))
            for (name in listOf("Authorization", "X-App-Key", "X-Secret", "Cookie", "X-Peer-Secret"))
                assertNull(upload.getHeader(name))

            assertEquals("/api/v1/capabilities", relay.takeRequest().path)
            assertEquals(4, relay.requestCount)
            for (suffix in listOf("upload-ticket", "complete", "complete")) {
                val request = relay.takeRequest()
                assertEquals("/api/v1/files/$hash/$suffix", request.requestUrl!!.encodedPath)
                assertEquals("code:room", request.requestUrl!!.queryParameter("key"))
                assertEquals("Bearer token", request.getHeader("Authorization"))
                assertEquals("secret", request.getHeader("X-Secret"))
                if (suffix == "complete") assertEquals("""{"upload_id":"stage123"}""", request.body.readUtf8())
            }
        } }
    }

    @Test fun invalidTicketsNeverSendModBytesAndDeduplicationSkipsPutAndCompletion() = runBlocking {
        loopbackServer().use { relay -> loopbackServer().use { storage ->
            val c = ModSyncClient(listOf(relay.localUrl("/")), OkHttpClient(), credentials)
            relay.enqueue(MockResponse().setBody(caps))
            val url = storage.localUrl("/upload")
            for (bad in listOf(ticket("https://other.invalid/"), ticket(url, """{"If-None-Match":"*","Content-Length":"4"}"""),
                ticket(url, """{"If-None-Match":"*","Content-Length":"3","Authorization":"private"}"""))) {
                relay.enqueue(bad)
                assertFailsWith<ModSyncException> { c.uploadFile("room", hash, "secret", "mod".toByteArray()) }
            }
            relay.enqueue(MockResponse().setBody("""{"already_present":true}"""))
            c.uploadFile("room", hash, "secret", "mod".toByteArray())
            assertEquals(0, storage.requestCount)
            assertEquals(5, relay.requestCount)
        } }
    }

    @Test fun expiredPutGetsANewTicketAndAReplacedManifestStopsCompletion() = runBlocking {
        loopbackServer().use { relay -> loopbackServer().use { storage ->
            val c = ModSyncClient(listOf(relay.localUrl("/")), OkHttpClient(), credentials)
            relay.enqueue(MockResponse().setBody(caps))
            relay.enqueue(ticket(storage.localUrl("/old")))
            storage.enqueue(MockResponse().setResponseCode(403))
            relay.enqueue(ticket(storage.localUrl("/new")))
            storage.enqueue(MockResponse())
            relay.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"room_changed"}"""))
            val error = assertFailsWith<ModSyncException> { c.uploadFile("room", hash, "secret", "mod".toByteArray()) }
            assertEquals(409, error.statusCode)
            assertEquals("/old", storage.takeRequest().path)
            assertEquals("/new", storage.takeRequest().path)
            assertEquals(4, relay.requestCount)
        } }
    }

    @Test fun storageFailureDoesNotUseLegacyUploadEvenWhenAdvertised() = runBlocking {
        loopbackServer().use { relay -> loopbackServer().use { storage ->
            relay.enqueue(MockResponse().setBody(caps))
            relay.enqueue(ticket(storage.localUrl("/staging/first")))
            relay.enqueue(ticket(storage.localUrl("/staging/retry")))
            relay.enqueue(MockResponse())
            repeat(2) { storage.enqueue(MockResponse().setResponseCode(403)) }

            val client = ModSyncClient(listOf(relay.localUrl("/")), OkHttpClient(), credentials)
            val error = assertFailsWith<ModSyncException> {
                client.uploadFile("room", hash, "secret", "mod".toByteArray())
            }
            assertEquals("R2 upload HTTP 403", error.message)
            assertNull(error.statusCode)
            assertEquals(3, relay.requestCount)
            assertEquals("/api/v1/capabilities", relay.takeRequest().path)
            repeat(2) {
                val request = relay.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/api/v1/files/$hash/upload-ticket?key=room", request.path)
            }
            assertEquals(2, storage.requestCount)
        } }
    }

    @Test fun accountChangeStopsStorageRetriesAndCompletion() = runBlocking {
        loopbackServer().use { relay -> loopbackServer().use { storage ->
            val current = AtomicBoolean(true)
            storage.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    current.set(false)
                    return MockResponse().setResponseCode(503)
                }
            }
            relay.enqueue(MockResponse().setBody(caps))
            relay.enqueue(ticket(storage.localUrl("/staging/session")))
            val client = ModSyncClient(listOf(relay.localUrl("/")), OkHttpClient(), credentials, current::get)

            val error = assertFailsWith<ModSyncException> {
                client.uploadFile("room", hash, "secret", "mod".toByteArray())
            }
            assertEquals("account_session_changed", error.message)
            assertEquals(401, error.statusCode)
            assertEquals(1, storage.requestCount)
            assertEquals(2, relay.requestCount)
        } }
    }

    @Test fun downloadResumesFromANewTicketAfterAnExpiredRangeRequest() = runBlocking {
        loopbackServer().use { relay -> loopbackServer().use { storage ->
            for (index in 1..3) relay.enqueue(MockResponse().setResponseCode(307)
                .setHeader("Location", storage.localUrl("/mod?ticket=$index")))
            storage.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/6").setBody("abc"))
            storage.enqueue(MockResponse().setResponseCode(403))
            storage.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def"))
            val c = ModSyncClient(listOf(relay.localUrl("/")), OkHttpClient(), credentials)
            val target = File.createTempFile("r2-range-", ".tmp")
            try {
                val expected = MessageDigest.getInstance("SHA-256").digest("abcdef".toByteArray())
                    .joinToString("") { "%02x".format(it) }
                assertEquals("abcdef", c.downloadFileTo(expected, 6, target).readText())
                for (index in 1..3) {
                    val control = relay.takeRequest()
                    val objectRequest = storage.takeRequest()
                    assertEquals("/api/v1/files/$expected", control.path)
                    assertEquals("/mod?ticket=$index", objectRequest.path)
                    assertEquals(if (index == 1) null else "bytes=3-", objectRequest.getHeader("Range"))
                    assertNull(objectRequest.getHeader("Authorization"))
                    assertEquals("identity", objectRequest.getHeader("Accept-Encoding"))
                }
            } finally { target.delete() }
        } }
    }
}
