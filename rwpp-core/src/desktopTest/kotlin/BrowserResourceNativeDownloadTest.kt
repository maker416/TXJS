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
import io.github.rwpp.net.browser.BrowserResourceFiles
import io.github.rwpp.net.browser.BrowserResourceInstallKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cef.CefApp
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
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

/** 单独运行，避免其它 native 测试销毁全局 Chromium：RWJS_BROWSER_NATIVE_TEST=1。 */
class BrowserResourceNativeDownloadTest {
    @Test fun nativeTmxAndZipDownloadsUseTheConfirmedResourceInstaller() {
        assumeTrue(System.getenv("RWJS_BROWSER_NATIVE_TEST") == "1")
        val directory = Files.createTempDirectory("browser-native-resources-").toFile()
        val map = "<map width=\"1\" height=\"1\"/>".toByteArray()
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("nested/map.tmx")); zip.write(map); zip.closeEntry()
            }
        }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/direct.tmx") { exchange ->
                // URL 导航也应变为下载，不让 Chromium 直接渲染 XML。
                exchange.responseHeaders.add("Content-Type", "text/xml")
                exchange.sendResponseHeaders(200, map.size.toLong())
                exchange.responseBody.use { it.write(map) }; exchange.close()
            }
            createContext("/download") { exchange ->
                // 无后缀 URL 依响应名识别 ZIP。
                exchange.responseHeaders.add("Content-Type", "application/zip")
                exchange.responseHeaders.add("Content-Disposition", "attachment; filename=maps.ZIP")
                exchange.sendResponseHeaders(200, archive.size.toLong())
                exchange.responseBody.use { it.write(archive) }; exchange.close()
            }
            start()
        }
        var frame: JFrame? = null
        var activeState: EmbeddedBrowserState? = null
        try {
            for ((path, name, kind) in listOf(
                Triple("direct.tmx", "direct.tmx", BrowserResourceInstallKind.Map),
                Triple("download?id=1", "maps.zip", BrowserResourceInstallKind.MapPack),
            )) {
                val state = EmbeddedBrowserState("http://127.0.0.1:${server.address.port}/$path")
                activeState = state
                SwingUtilities.invokeAndWait {
                    val panel = ComposePanel().apply { setContent { EmbeddedBrowser(state, Modifier.fillMaxSize()) } }
                    frame = JFrame("Browser resource download test").apply {
                        isAutoRequestFocus = false
                        contentPane.add(panel); setSize(640, 480); isVisible = true
                    }
                }
                runBlocking {
                    withTimeout(60_000) { while (state.modDownload == null && state.error == null) delay(50) }
                    val download = checkNotNull(state.modDownload) { state.error.orEmpty() }
                    assertEquals(name, download.fileName)
                    val targetDirectory = File(directory, path.substringBefore('.').substringBefore('?'))
                    delay(200)
                    assertTrue(!targetDirectory.exists(), "Resource files require confirmation")
                    val partial = BrowserResourceFiles.createPartial(targetDirectory)
                    try {
                        withTimeout(15_000) { download.transfer.download(partial) { _, _ -> } }
                        val installed = BrowserResourceFiles.install(partial, targetDirectory, download.fileName, kind)
                        val installedMap = if (kind == BrowserResourceInstallKind.Map) installed.file else File(installed.file, "nested/map.tmx")
                        assertContentEquals(map, installedMap.readBytes())
                    } finally { partial.delete() }
                }
                SwingUtilities.invokeAndWait { frame?.dispose() }
                frame = null
            }
        } finally {
            activeState?.modDownload?.transfer?.cancel()
            SwingUtilities.invokeAndWait { frame?.dispose() }
            if (CefApp.getState() == CefApp.CefAppState.INITIALIZED) CefApp.getInstance().dispose()
            server.stop(0)
            directory.deleteRecursively()
        }
    }
}
