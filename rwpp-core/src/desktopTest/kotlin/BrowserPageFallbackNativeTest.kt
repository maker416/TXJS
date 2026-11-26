/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.compose.ui.graphics.Color
import com.sun.net.httpserver.HttpServer
import io.github.rwpp.widget.LocalDialogInteractive
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 软件渲染回退时菜单宿主是透明（分层）Swing 窗口，JCEF 子窗口在其中收不到鼠标输入。
 * 这里用真实窗口与机器点击验证：只要提供宿主，网页必须落在独立不透明子窗口并可点击。
 */
class BrowserPageFallbackNativeTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun browserPageInTranslucentSwingMenuAcceptsClicks() {
        assumeTrue(System.getenv("RWJS_BROWSER_NATIVE_TEST") == "1")
        assumeFalse(GraphicsEnvironment.isHeadless())
        System.setProperty("compose.layers.type", "WINDOW")
        val windowLayers = Class.forName("io.github.rwpp.desktop.DesktopWindowLayers")
        windowLayers.getMethod("install").invoke(windowLayers.getField("INSTANCE").get(null))
        prepareDesktopKernelForTest()

        val loaded = CountDownLatch(1)
        val clicked = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/page") { exchange ->
            val html = """
                <!doctype html><html><body style="margin:0;background:#123">
                <button style="position:fixed;inset:0;border:0;font-size:32px;background:#2a6"
                    onclick="this.style.background='#c33';fetch('/clicked')">CLICK ME</button>
                <script>window.addEventListener('load', () => fetch('/loaded'));</script>
                </body></html>
            """.trimIndent()
            val bytes = html.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.createContext("/loaded") { exchange ->
            loaded.countDown()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.createContext("/clicked") { exchange ->
            clicked.countDown()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()

        var frame: JFrame? = null
        var overlay: JDialog? = null
        try {
            val state = EmbeddedBrowserState("http://127.0.0.1:${server.address.port}/page")
            SwingUtilities.invokeAndWait {
                val owner = JFrame("browser page fallback regression").apply {
                    setSize(760, 540)
                    setLocationRelativeTo(null)
                    isAlwaysOnTop = true
                    isVisible = true
                    toFront()
                }
                val root = JPanel(BorderLayout()).apply { isOpaque = false }
                val dialog = JDialog(owner, Dialog.ModalityType.MODELESS).apply {
                    title = "RWJS 启动器"
                    isUndecorated = true
                    isResizable = false
                    // 与软件渲染回退一致：逐像素透明(分层)菜单窗口。
                    background = java.awt.Color(0, 0, 0, 0)
                    (contentPane as JPanel).isOpaque = false
                    contentPane.layout = BorderLayout()
                }
                val panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics()).apply {
                    isOpaque = false
                    setContent {
                        MaterialTheme {
                            CompositionLocalProvider(
                                LocalDialogInteractive provides true,
                                LocalDesktopBrowserPageOwner provides dialog,
                            ) {
                                BrowserPageWindow(visible = true) {
                                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                                        EmbeddedBrowser(state, Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                    }
                }
                root.add(panel, BorderLayout.CENTER)
                dialog.contentPane.add(root, BorderLayout.CENTER)
                dialog.setBounds(owner.bounds)
                dialog.isAlwaysOnTop = true
                dialog.isVisible = true
                dialog.toFront()
                frame = owner
                overlay = dialog
            }

            assertTrue(loaded.await(40, TimeUnit.SECONDS), "网页未能加载: ${state.error}")
            Thread.sleep(1500)
            val browserWindow = onEdt {
                JDialog.getWindows().firstOrNull { it.isShowing && it.owner === overlay && it !== overlay }
            } ?: error("网页没有获得独立子窗口")
            val bounds = onEdt { browserWindow.bounds }
            assertTrue(bounds.width > 0 && bounds.height > 0, "网页子窗口为空: $bounds")
            val robot = Robot()
            val point = Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
            robot.mouseMove(point.x, point.y)
            Thread.sleep(250)
            repeat(2) {
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                Thread.sleep(80)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                Thread.sleep(350)
            }
            ImageIO.write(
                robot.createScreenCapture(bounds), "png",
                File("build/reports/browser-page/fallback-click.png").apply { parentFile.mkdirs() },
            )
            assertTrue(clicked.await(10, TimeUnit.SECONDS), "真实点击没有到达网页")
        } finally {
            SwingUtilities.invokeLater { overlay?.dispose(); frame?.dispose() }
            server.stop(0)
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        var result: T? = null
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { try { result = action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
