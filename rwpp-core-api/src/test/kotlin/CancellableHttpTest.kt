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
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class CancellableHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var bodyStarted: CompletableDeferred<Unit>

    @BeforeTest
    fun setup() {
        server = MockWebServer().apply { start() }
        bodyStarted = CompletableDeferred()
        http = OkHttpClient.Builder()
            .readTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
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
    fun roomIdentityCancellationStillClosesCallWhileReadingBody() = assertCancels(
        stalledBody("""{"users":[]}"""),
        awaitBody = true,
    ) {
        RoomIdentityClient(listOf(server.url("/").toString()), http)
            .lookup(listOf("code:Q1"), "alice", "ak_test", "token")
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
