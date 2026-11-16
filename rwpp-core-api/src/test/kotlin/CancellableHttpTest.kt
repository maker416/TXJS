/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.account.AccountApiClient
import io.github.rwpp.net.roomid.RoomIdentityClient
import io.github.rwpp.net.sync.ModSyncClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CancellableHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var bodyStarted: CompletableDeferred<Unit>

    @BeforeTest
    fun setup() {
        server = MockWebServer().apply { start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0) }
        bodyStarted = CompletableDeferred()
        http = OkHttpClient.Builder()
            .readTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // 房间名片先探测能力；只让真实业务正文的阻塞读取触发本测试的取消时机。
                if (chain.request().url.encodedPath == "/api/v1/capabilities") return@addNetworkInterceptor response
                val original = response.body ?: return@addNetworkInterceptor response
                val source = object : ForwardingSource(original.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        // 在实际阻塞读取前通知测试，避免依赖收到首字节后才触发的事件。
                        bodyStarted.complete(Unit)
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = source
                }).build()
            }
            .build()
    }

    @AfterTest
    fun tearDown() {
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    @Test
    fun accountCancellationClosesCallBeforeResponseHeaders() = assertCancels(
        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
    ) {
        AccountApiClient(server.url("/").toString(), "ak_test", http).me("token")
    }

    @Test
    fun accountCancellationStillClosesCallWhileReadingBody() = assertCancels(
        stalledBody("""{"user":{"id":1,"username":"alice"}}"""),
        awaitBody = true,
    ) {
        AccountApiClient(server.url("/").toString(), "ak_test", http).me("token")
    }

    @Test
    fun roomIdentityCancellationStillClosesCallWhileReadingBody() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"room_id_enabled":true,"room_id_public_lookup":true}"""))
        server.enqueue(stalledBody("""{"users":[]}"""))
        // MockWebServer.url() 的反向 DNS 名称可能不是 localhost，显式使用受允许的回环地址。
        val url = server.url("/").newBuilder().host("127.0.0.1").build().toString()
        val job = launch(Dispatchers.IO) {
            RoomIdentityClient(listOf(url), http).lookup(listOf("code:Q1"), "alice", "ak_test", "token")
        }
        val probe = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/v1/capabilities", probe.path)
        assertNull(probe.getHeader("Authorization"))
        assertNull(probe.getHeader("X-App-Key"))
        val lookup = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/v1/roomid/lookup?room_key=code%3AQ1&player_name=alice", lookup.path)
        assertNull(lookup.getHeader("Authorization"))
        assertNull(lookup.getHeader("X-App-Key"))
        withTimeout(2_000) { bodyStarted.await() }
        assertFalse(job.isCompleted)
        withTimeout(2_000) { job.cancelAndJoin() }
    }

    @Test
    fun modManifestCancellationClosesCallBeforeResponseHeaders() = assertCancels(
        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
    ) {
        ModSyncClient(listOf(server.url("/").toString()), http).fetchManifest("code:Q1")
    }

    @Test
    fun blobCancellationStillClosesCallWhileReadingBody() = assertCancels(
        stalledBody("payload"),
        awaitBody = true,
    ) {
        ModSyncClient(listOf(server.url("/").toString()), http).downloadFile("a".repeat(64), 7)
    }

    private fun stalledBody(body: String) = MockResponse().setBody(body).setBodyDelay(3, TimeUnit.SECONDS)

    private fun assertCancels(
        response: MockResponse,
        awaitBody: Boolean = false,
        request: suspend () -> Unit,
    ) = runBlocking {
        server.enqueue(response)
        val job = launch(Dispatchers.IO) { request() }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        if (awaitBody) withTimeout(2_000) { bodyStarted.await() }
        assertFalse(job.isCompleted)
        // 响应仍卡住时必须结束，而不是等正文延迟或 20s 读超时。
        withTimeout(2_000) { job.cancelAndJoin() }
    }
}
