/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.platform

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.mozilla.geckoview.GeckoView

/** Real packaged Gecko/extension integration; no account service or public forum is changed. */
class GeckoBrowserTestActivity : ComponentActivity() {
    lateinit var browserState: EmbeddedBrowserState
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browserState = EmbeddedBrowserState(intent.getStringExtra("bootstrap")!!)
        setContent { EmbeddedBrowser(browserState, Modifier.fillMaxSize()) }
    }
}

@RunWith(AndroidJUnit4::class)
class GeckoBrowserIntegrationTest {
    private class Fixture(private val mode: String = "login") : AutoCloseable {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val received = AtomicReference<String?>()
        val cookie = AtomicReference<String?>()
        val uploadBody = AtomicReference<String?>()
        val uploaded = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val clicked = CountDownLatch(1)
        val base = "http://127.0.0.1:${server.localPort}"
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "gecko-fixture").apply { isDaemon = true } }
        init {
            executor.execute {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    executor.execute {
                        socket.use {
                            it.soTimeout = 5000
                            val input = it.getInputStream().bufferedReader()
                            val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = input.readLine()
                                if (line.isNullOrEmpty()) break
                                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                            }
                            if (path.startsWith("/done?ticket=")) received.set(path.substringAfter("ticket="))
                            if (path == "/protected.rwmod") cookie.set(headers["cookie"])
                            if (path == "/ready") ready.countDown()
                            if (path == "/clicked") clicked.countDown()
                            if (path == "/upload") {
                                val body = CharArray(headers["content-length"]!!.toInt())
                                var offset = 0
                                while (offset < body.size) {
                                    val count = input.read(body, offset, body.size - offset)
                                    if (count < 0) break
                                    offset += count
                                }
                                uploadBody.set(String(body, 0, offset))
                                uploaded.countDown()
                            }
                            val script = when (mode) {
                                "download" -> "window.rwForumClient=function(){document.cookie='gecko_download=authenticated; path=/';location.replace('/protected.rwmod');};"
                                "upload" -> "window.rwForumClient=function(){document.getElementById('select').disabled=false;fetch('/ready');};"
                                else -> "window.rwForumClient=function(ticket){location.replace('/done?ticket='+String(ticket));};"
                            }
                            val html = if (path == "/sso/client") {
                                """<html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body style="margin:0">
                                <button id="select" disabled style="width:200px;height:70px" onclick="fetch('/clicked');document.getElementById('file').click()">Select</button>
                                <form action="/upload" method="post" enctype="multipart/form-data"><input id="file" name="mod" type="file" accept=".rwmod" style="display:none" onchange="this.form.submit()"></form>
                                <script>$script</script></body></html>"""
                            } else "<html><body>Complete</body></html>"
                            val download = path == "/protected.rwmod"
                            val bytes = if (download) byteArrayOf(1, 2, 3, 4) else html.toByteArray()
                            it.getOutputStream().apply {
                                val contentType = if (download) "application/octet-stream\r\nContent-Disposition: attachment; filename=protected.rwmod" else "text/html; charset=utf-8"
                                write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes)
                                flush()
                            }
                        }
                    }
                }
            }
        }
        override fun close() { server.close(); executor.shutdownNow() }
    }

    private fun checkHandoff(ticket: String?) {
        Fixture().use { fixture ->
            val bootstrap = "${fixture.base}/sso/client"
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val intent = Intent(context, GeckoBrowserTestActivity::class.java).putExtra("bootstrap", bootstrap)
            ActivityScenario.launch<GeckoBrowserTestActivity>(intent).use { scenario ->
                scenario.onActivity { it.browserState.submitClientLogin(bootstrap, ticket) }
                val deadline = System.nanoTime() + 30_000_000_000L
                var error: String? = null
                while (fixture.received.get() == null && System.nanoTime() < deadline) {
                    scenario.onActivity { error = it.browserState.clientLoginError ?: it.browserState.error }
                    if (error != null) break
                    Thread.sleep(100)
                }
                assertNull("Gecko initialization/navigation error", error)
                assertEquals("Bundled extension must call the page with the exact handoff", ticket ?: "null", fixture.received.get())
            }
        }
    }

    @Test fun packagedExtensionSubmitsTicketAndSessionCanReopen() {
        checkHandoff("a".repeat(64))
        checkHandoff("b".repeat(64))
    }
    @Test fun packagedExtensionSubmitsGuestNull() { checkHandoff(null) }

    @Test fun authenticatedDownloadUsesGeckoResponseStream() = runBlocking {
        Fixture("download").use { fixture ->
            val bootstrap = "${fixture.base}/sso/client"
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val intent = Intent(context, GeckoBrowserTestActivity::class.java).putExtra("bootstrap", bootstrap)
            ActivityScenario.launch<GeckoBrowserTestActivity>(intent).use { scenario ->
                scenario.onActivity { it.browserState.submitClientLogin(bootstrap, null) }
                var download: io.github.rwpp.net.browser.BrowserModDownload? = null
                val deadline = System.nanoTime() + 30_000_000_000L
                while (download == null && System.nanoTime() < deadline) {
                    scenario.onActivity { download = it.browserState.modDownload }
                    Thread.sleep(100)
                }
                val response = requireNotNull(download) { "Gecko must offer the authenticated attachment" }
                assertEquals("protected.rwmod", response.fileName)
                assertTrue(fixture.cookie.get().orEmpty().contains("gecko_download=authenticated"))
                val target = File.createTempFile("gecko-response-", ".part", context.cacheDir)
                try {
                    response.transfer.download(target) { _, _ -> }
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4), target.readBytes())
                } finally { target.delete() }
            }
        }
    }

    @Test fun nativeFilePromptUploadsPrivateSnapshot() {
        Fixture("upload").use { fixture ->
            val bootstrap = "${fixture.base}/sso/client"
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val intent = Intent(context, GeckoBrowserTestActivity::class.java).putExtra("bootstrap", bootstrap)
            ActivityScenario.launch<GeckoBrowserTestActivity>(intent).use { scenario ->
                scenario.onActivity { it.browserState.submitClientLogin(bootstrap, null) }
                val deadline = System.nanoTime() + 30_000_000_000L
                assertTrue("Wait for the actual page's handoff callback", fixture.ready.await(30, TimeUnit.SECONDS))
                // The HTML file chooser requires an actual user gesture.
                Thread.sleep(500)
                var x = 0f
                var y = 0f
                scenario.onActivity { activity ->
                    fun find(view: View): GeckoView? = if (view is GeckoView) view else
                        (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
                    val gecko = requireNotNull(find(activity.window.decorView))
                    val location = IntArray(2)
                    gecko.getLocationOnScreen(location)
                    x = location[0] + 60 * activity.resources.displayMetrics.density
                    y = location[1] + 30 * activity.resources.displayMetrics.density
                }
                val time = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
                instrumentation.sendPointerSync(MotionEvent.obtain(time, time + 100, MotionEvent.ACTION_UP, x, y, 0))
                assertTrue("Touch must reach the HTML select button", fixture.clicked.await(5, TimeUnit.SECONDS))
                var prompt: BrowserFileUploadRequest? = null
                while (prompt == null && System.nanoTime() < deadline) {
                    scenario.onActivity { prompt = it.browserState.fileUpload }
                    Thread.sleep(100)
                }
                val request = requireNotNull(prompt) { "A trusted file input click must reach the Gecko file prompt" }
                val bytes = "GECKO-UPLOAD-BYTES".toByteArray()
                val snapshot = request.cache.prepareStream("fixture.rwmod", { bytes.inputStream() })
                scenario.onActivity { request.selectFiles(listOf(snapshot)) }
                assertTrue("Gecko must POST the selected snapshot", fixture.uploaded.await(10, TimeUnit.SECONDS))
                assertTrue(fixture.uploadBody.get().orEmpty().contains("filename=\"fixture.rwmod\""))
                assertTrue(fixture.uploadBody.get().orEmpty().contains("GECKO-UPLOAD-BYTES"))
            }
        }
    }
}
