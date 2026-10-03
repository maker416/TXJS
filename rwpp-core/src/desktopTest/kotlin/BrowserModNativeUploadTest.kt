/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cef.CefApp
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.cef.browser.CefBrowser
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** 使用真实 Chromium 的文件输入与 multipart 上传，按需运行 RWJS_BROWSER_NATIVE_TEST=1。 */
class BrowserModNativeUploadTest {
    @Test fun cancelledChooserCanReopenAndSelectedModReachesWebUpload() {
        assumeTrue(System.getenv("RWJS_BROWSER_NATIVE_TEST") == "1")
        val directory = Files.createTempDirectory("browser-native-upload-").toFile()
        val file = File(directory, "native-test.rwmod").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
        val received = CompletableFuture<ByteArray>()
        val ready = CompletableFuture<Unit>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val html = """<html><body onload="fetch('/ready')"><input id="upload" type="file" accept=".rwmod" style="position:absolute;left:20px;top:20px;width:300px;height:80px" onchange="const data=new FormData();data.append('mod',this.files[0]);fetch('/upload',{method:'POST',body:data})"></body></html>""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, html.size.toLong())
                exchange.responseBody.use { it.write(html) }
                exchange.close()
            }
            createContext("/ready") { exchange ->
                ready.complete(Unit)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            createContext("/upload") { exchange ->
                received.complete(exchange.requestBody.readBytes())
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.close()
                exchange.close()
            }
            start()
        }
        val state = EmbeddedBrowserState("http://127.0.0.1:${server.address.port}/")
        var frame: JFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                frame = JFrame("Browser upload integration test").apply {
                    contentPane.add(ComposePanel().apply { setContent { EmbeddedBrowser(state, Modifier.fillMaxSize()) } })
                    setSize(640, 480)
                    setLocationRelativeTo(null)
                    isVisible = true
                }
            }
            runBlocking {
                withTimeout(60_000) { while (state.isLoading && state.error == null) delay(100) }
                check(state.error == null) { state.error.orEmpty() }
                withTimeout(20_000) { while (!ready.isDone) delay(50) }
                delay(500)
                val controller = checkNotNull(state.controller)
                val browserField = controller.javaClass.getDeclaredField("browser").apply { isAccessible = true }
                val devTools = (browserField.get(controller) as CefBrowser).devToolsClient
                fun clickInput() {
                    // 通过 Chromium 自身的用户手势激活真实文件输入，避免桌面 DPI/焦点干扰测试。
                    val result = devTools.executeDevToolsMethod("Runtime.evaluate",
                        """{"expression":"document.getElementById('upload').click()","userGesture":true}""")
                        .get(10, TimeUnit.SECONDS)
                    check(!result.contains("exceptionDetails")) { result }
                }
                clickInput()
                withTimeout(10_000) { while (state.fileUpload == null) delay(50) }
                SwingUtilities.invokeAndWait { state.fileUpload!!.cancel() }
                assertTrue(!received.isDone)
                delay(300)
                clickInput()
                withTimeout(10_000) { while (state.fileUpload == null) delay(50) }
                val pending = state.fileUpload!!
                val snapshot = pending.cache.prepare(file)
                SwingUtilities.invokeAndWait { pending.selectFiles(listOf(snapshot)) }
                withTimeout(10_000) { while (!received.isDone) delay(50) }
                val body = received.get()
                val text = body.toString(Charsets.ISO_8859_1)
                assertTrue(text.contains("filename=\"native-test.rwmod\""))
                val start = text.indexOf("\r\n\r\n") + 4
                assertContentEquals(file.readBytes(), body.copyOfRange(start, start + file.length().toInt()))
                assertTrue(snapshot.exists(), "Keep snapshot alive while Chromium reads it")
                devTools.close()
            }
        } finally {
            SwingUtilities.invokeAndWait { state.fileUpload?.cancel(); frame?.dispose() }
            if (CefApp.getState() == CefApp.CefAppState.INITIALIZED) CefApp.getInstance().dispose()
            server.stop(0)
            directory.deleteRecursively()
        }
    }
}
