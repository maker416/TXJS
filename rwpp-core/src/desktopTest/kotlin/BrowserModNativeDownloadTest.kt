/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.foundation.layout.fillMaxSize
import com.sun.net.httpserver.HttpServer
import io.github.rwpp.net.browser.BrowserModFiles
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cef.CefApp
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 需要真实桌面窗口与 Chromium runtime，按需运行：RWJS_BROWSER_NATIVE_TEST=1。
 * JAVA_TOOL_OPTIONS 需包含桌面应用同样的 --add-opens=java.base/java.net=ALL-UNNAMED
 * 和 --add-opens=java.desktop/sun.awt=ALL-UNNAMED。
 */
class BrowserModNativeDownloadTest {
    @Test fun nativeDownloadWaitsForConsentAndUsesRequestedDestination() {
        assumeTrue(System.getenv("RWJS_BROWSER_NATIVE_TEST") == "1")
        val directory = Files.createTempDirectory("browser-native-mod-").toFile()
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("mod-info.txt"))
                zip.write("[mod]\ntitle: Native browser test\n".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/source.rwmod") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.responseHeaders.add("Content-Disposition", "attachment; filename=installed.rwmod")
                exchange.sendResponseHeaders(200, archive.size.toLong())
                exchange.responseBody.use { it.write(archive) }
                exchange.close()
            }
            start()
        }
        val state = EmbeddedBrowserState("http://127.0.0.1:${server.address.port}/source.rwmod")
        var frame: JFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                val panel = ComposePanel().apply { setContent { EmbeddedBrowser(state, Modifier.fillMaxSize()) } }
                frame = JFrame("Browser mod download test").apply {
                    contentPane.add(panel)
                    setSize(640, 480)
                    isVisible = true
                }
            }
            runBlocking {
                withTimeout(60_000) {
                    while (state.modDownload == null && state.error == null) delay(50)
                }
                val download = checkNotNull(state.modDownload) { state.error.orEmpty() }
                assertEquals("installed.rwmod", download.fileName)
                delay(300)
                assertTrue(directory.listFiles()!!.isEmpty(), "No file may be saved before consent")
                val partial = BrowserModFiles.createPartial(directory)
                try {
                    withTimeout(15_000) { download.transfer.download(partial) { _, _ -> } }
                    val installed = BrowserModFiles.install(partial, directory, download.fileName)
                    assertContentEquals(archive, installed.readBytes())
                } finally { partial.delete() }
            }
        } finally {
            state.modDownload?.transfer?.cancel()
            SwingUtilities.invokeAndWait { frame?.dispose() }
            if (CefApp.getState() == CefApp.CefAppState.INITIALIZED) CefApp.getInstance().dispose()
            server.stop(0)
            directory.deleteRecursively()
        }
    }
}
