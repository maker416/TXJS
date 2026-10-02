/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.core.ModSyncController
import io.github.rwpp.logger
import io.github.rwpp.net.sync.ModSyncClient
import io.github.rwpp.net.sync.SyncPeerPhase
import io.github.rwpp.net.sync.SyncPeerUpsertRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModSyncPeerCleanupTest {
    @Test
    fun clearWaitsForIssuedSecretThenDeletesWithoutAllowingQueuedOrLaterPuts() = runBlocking {
        logger = LoggerFactory.getLogger("ModSyncPeerCleanupTest")
        val firstPutEntered = CountDownLatch(1)
        val releaseFirstPut = CountDownLatch(1)
        val puts = AtomicInteger(0)
        val deletes = AtomicInteger(0)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                    "PUT" -> {
                        if (puts.incrementAndGet() == 1) {
                            firstPutEntered.countDown()
                            if (!releaseFirstPut.await(5, TimeUnit.SECONDS)) {
                                MockResponse().setResponseCode(504)
                            } else {
                                MockResponse().setResponseCode(201).setBody("""{"peer_secret":"issued-secret"}""")
                            }
                        } else {
                            MockResponse().setResponseCode(200).setBody("{}")
                        }
                    }
                    "DELETE" -> {
                        deletes.incrementAndGet()
                        MockResponse().setResponseCode(204)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val reporter = ModSyncController.PeerProgressReporter(
                ModSyncClient(listOf(server.url("/").toString()), OkHttpClient()),
                roomKey = "code:TEST",
                displayName = "Alice",
            )
            val progress = SyncPeerUpsertRequest(displayName = "Alice", phase = SyncPeerPhase.JOINING)
            try {
                reporter.report(progress, force = true)
                assertTrue(firstPutEntered.await(5, TimeUnit.SECONDS))
                reporter.report(progress.copy(currentModName = "queued"), force = true)

                // UNDISTPATCHED 确保 clear 已同步设置终止标记，再挂起等待持有 mutex 的首个 PUT。
                val cleanup = async(start = CoroutineStart.UNDISPATCHED) { reporter.clear() }
                assertFalse(cleanup.isCompleted)
                assertEquals(0, deletes.get())
                releaseFirstPut.countDown()
                withTimeout(5_000) { cleanup.await() }

                val put = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                val delete = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("PUT", put.method)
                assertNull(put.getHeader("X-Peer-Secret"))
                assertEquals("DELETE", delete.method)
                assertEquals(put.path, delete.path)
                assertEquals("issued-secret", delete.getHeader("X-Peer-Secret"))
                assertEquals("issued-secret", reporter.currentPeerSecret())
                assertEquals(1, puts.get())
                assertEquals(1, deletes.get())

                reporter.report(progress.copy(currentModName = "after clear"), force = true)
                assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
                assertEquals(1, puts.get())
                assertEquals(1, deletes.get())
            } finally {
                releaseFirstPut.countDown()
                reporter.stopHeartbeat()
            }
        }
    }
}