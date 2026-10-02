/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.app.UpdateDownloadCancelledException
import io.github.rwpp.app.UpdateDownloadSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateDownloadSessionTest {
    @Test
    fun cancelDuringBodyReadClosesHttpCallAndPreventsInstallerLaunch() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("i").setHeader("Content-Length", 1000))
            val session = UpdateDownloadSession()
            val readingBody = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val download = executor.submit<Boolean> {
                    try {
                        val call = OkHttpClient().newCall(Request.Builder().url(server.url("/installer")).build())
                        session.execute(call) { response ->
                            val source = response.body!!.source()
                            source.readByte()
                            readingBody.countDown()
                            source.readByteArray()
                        }
                        false
                    } catch (e: IOException) {
                        true
                    }
                }
                assertTrue(readingBody.await(5, TimeUnit.SECONDS))
                session.cancel()
                assertTrue(download.get(5, TimeUnit.SECONDS))
                var launched = false
                assertFalse(session.startInstallation { launched = true })
                assertFalse(launched)
            } finally {
                session.cancel()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun cancelledSessionCannotStartAnotherRequestButFreshSessionCanDownload() {
        MockWebServer().use { server ->
            val client = OkHttpClient()
            val cancelled = UpdateDownloadSession()
            cancelled.cancel()
            val request = Request.Builder().url(server.url("/installer")).build()
            assertFailsWith<UpdateDownloadCancelledException> {
                cancelled.execute(client.newCall(request)) { it.body!!.string() }
            }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("new installer"))
            assertEquals("new installer", UpdateDownloadSession().execute(client.newCall(request)) { it.body!!.string() })
        }
    }

    @Test
    fun cancellationAfterDownloadStillPreventsInstallation() {
        val session = UpdateDownloadSession()
        session.checkActive()
        session.cancel()
        assertFalse(session.startInstallation { error("cancelled updater must not launch or exit") })
    }
}