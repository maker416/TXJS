/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.graphics.Color
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.rwpp.ui.ResourceBrowserFloatingExit
import io.github.rwpp.widget.LocalDialogInteractive
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cef.CefApp
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.awt.Dialog
import java.awt.Component
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 真实 Chromium 冷启动与 SSO 首屏回归；单独运行 RWJS_BROWSER_NATIVE_TEST=1。 */
class BrowserStartupNativeTest {
    @Test
    fun startupAndForumHandoffKeepPlaceholderUntilContentIsReady() {
        assumeTrue(System.getenv("RWJS_BROWSER_NATIVE_TEST") == "1")
        assumeFalse(GraphicsEnvironment.isHeadless())
        // 与桌面入口一致：浮层必须使用原生窗口，才能覆盖 Chromium heavyweight 子窗口。
        System.setProperty("compose.layers.type", "WINDOW")
        val windowLayers = Class.forName("io.github.rwpp.desktop.DesktopWindowLayers")
        val installed = windowLayers.getMethod("install").invoke(windowLayers.getField("INSTANCE").get(null))
        if (System.getProperty("os.name").startsWith("Windows")) assertTrue(installed == true)
        prepareDesktopKernelForTest()

        val bootstrapRequested = CompletableFuture<Unit>()
        val bootstrapLoaded = CompletableFuture<Unit>()
        val homeLoaded = CompletableFuture<Unit>()
        val clicked = CompletableFuture<Unit>()
        val nextRequested = CompletableFuture<Unit>()
        val recoveryRequested = CompletableFuture<Unit>()
        val releaseBootstrap = CountDownLatch(1)
        val releaseNext = CountDownLatch(1)
        val releaseRecovery = CountDownLatch(1)
        val failRequest = AtomicBoolean(true)
        val executor = Executors.newCachedThreadPool { task ->
            Thread(task, "browser-startup-fixture").apply { isDaemon = true }
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/sso/client") { exchange ->
                bootstrapRequested.complete(Unit)
                if (releaseBootstrap.await(60, TimeUnit.SECONDS)) {
                    respond(exchange, 200, """
                        <!doctype html><html style="background:white"><body style="background:white">
                        <script>
                        window.rwForumClient = function () { location.replace('/home'); };
                        window.addEventListener('load', function () { fetch('/bootstrap-loaded'); });
                        </script></body></html>
                    """.trimIndent())
                } else exchange.close()
            }
            createContext("/bootstrap-loaded") { exchange ->
                bootstrapLoaded.complete(Unit)
                respond(exchange, 200, "")
            }
            createContext("/home") { exchange ->
                respond(exchange, 200, """
                    <!doctype html><html style="background:#249464"><body style="margin:0;background:#249464">
                    <button style="position:fixed;inset:0;border:0;background:#249464"
                        onclick="this.style.background='#3158be';fetch('/clicked')">Ready</button>
                    <script>window.addEventListener('load', function () { fetch('/home-loaded'); });</script>
                    </body></html>
                """.trimIndent())
            }
            createContext("/home-loaded") { exchange ->
                homeLoaded.complete(Unit)
                respond(exchange, 200, "")
            }
            createContext("/clicked") { exchange ->
                clicked.complete(Unit)
                respond(exchange, 200, "")
            }
            createContext("/next") { exchange ->
                nextRequested.complete(Unit)
                if (releaseNext.await(60, TimeUnit.SECONDS)) respond(exchange, 200, solidPage("#7044a8"))
                else exchange.close()
            }
            createContext("/plain") { exchange ->
                respond(exchange, 200, "<!doctype html><html><body><p>Default black text remains readable.</p></body></html>")
            }
            createContext("/failure") { exchange ->
                if (failRequest.get()) respond(exchange, 503, solidPage("white"))
                else {
                    recoveryRequested.complete(Unit)
                    if (releaseRecovery.await(60, TimeUnit.SECONDS)) respond(exchange, 200, solidPage("#249464"))
                    else exchange.close()
                }
            }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val initial = EmbeddedBrowserState("$base/sso/client", "$base/home").apply {
            clientAuthenticating = true
        }
        val selected = mutableStateOf<EmbeddedBrowserState?>(initial)
        val interactive = mutableStateOf(true)
        val pageVisible = mutableStateOf(true)
        val exitVisible = mutableStateOf(false)
        val reports = File("build/reports/browser-startup-native").apply { mkdirs() }
        val placeholder = 0x1b2638
        val gameBackground = 0x982943
        val robot = Robot().apply { autoDelay = 30 }
        lateinit var panel: Component
        var frame: JFrame? = null
        var transparentMenu: ComposeDialog? = null

        fun screenshot(): BufferedImage {
            val viewport = onEdt {
                val location = panel.locationOnScreen
                Rectangle(location.x, location.y, panel.width, panel.height)
            }
            return robot.createScreenCapture(viewport)
        }

        fun save(stage: String, image: BufferedImage) {
            ImageIO.write(image, "png", File(reports, "$stage.png"))
        }

        fun assertPlaceholder(stage: String) {
            // 服务端闸门保持页面未完成，连续采样覆盖 CEF/EDT 的异步显示更新。
            repeat(8) {
                val image = screenshot()
                save(stage, image)
                assertTrue(matches(image, placeholder), "$stage must show the themed placeholder, not a native white page")
                Thread.sleep(35)
            }
        }

        suspend fun awaitColor(stage: String, color: Int, rejectWhite: Boolean = false, ready: () -> Boolean = { true }) {
            var last: BufferedImage? = null
            try {
                awaitCondition(stage) {
                    val image = screenshot().also { last = it }
                    if (rejectWhite && matches(image, 0xffffff)) {
                        save("$stage-white-flash", image)
                        throw AssertionError("$stage exposed a native white page before the target content")
                    }
                    matches(image, color) && ready()
                }
            } finally {
                last?.let { save(stage, it) }
            }
        }

        suspend fun assertPlainPage(stage: String, state: EmbeddedBrowserState) {
            awaitColor(stage, 0xffffff) {
                onEdt { !state.isLoading && state.error == null && state.url == "$base/plain" }
            }
            var last: BufferedImage? = null
            try {
                awaitCondition("$stage default black text") {
                    val image = screenshot().also { last = it }
                    matches(image, 0xffffff) && (8 until minOf(image.height, 65)).any { y ->
                        (8 until minOf(image.width, 350)).any { x ->
                            val pixel = image.getRGB(x, y)
                            listOf(0, 8, 16).all { shift -> (pixel shr shift and 255) <= 30 }
                        }
                    }
                }
            } finally {
                last?.let { save(stage, it) }
            }
        }

        try {
            onEdt {
                val content: @Composable () -> Unit = {
                    MaterialTheme(colorScheme = darkColorScheme(surface = Color(0xff1b2638))) {
                        CompositionLocalProvider(LocalDialogInteractive provides interactive.value) {
                            val browser = selected.value
                            if (browser == null) {
                                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
                            } else {
                                BrowserPageWindow(pageVisible.value) {
                                    key(browser) { EmbeddedBrowser(browser, Modifier.fillMaxSize()) }
                                    if (exitVisible.value) {
                                        ResourceBrowserFloatingExit { selected.value = null }
                                    }
                                }
                            }
                        }
                    }
                }
                frame = JFrame("Browser startup native regression").apply {
                    // 启动器原生菜单与全屏窗口使用无边框宿主，遮罩会完全覆盖浏览器视口。
                    isUndecorated = true
                    contentPane.background = java.awt.Color(gameBackground)
                    setSize(720, 520)
                    setLocationRelativeTo(null)
                    // Robot 读取真实屏幕，测试窗口须稳定置前以免截到其它正在使用的应用。
                    isAlwaysOnTop = true
                    isVisible = true
                    toFront()
                }.also { owner ->
                    if (System.getProperty("os.name").startsWith("Windows")) {
                        transparentMenu = ComposeDialog(owner, Dialog.ModalityType.MODELESS, owner.graphicsConfiguration).apply {
                            isUndecorated = true
                            isTransparent = true
                            setContent {
                                CompositionLocalProvider(LocalDesktopBrowserPageOwner provides this@apply) { content() }
                            }
                            bounds = owner.bounds
                            isVisible = true
                        }
                        panel = checkNotNull(transparentMenu).contentPane
                    } else {
                        panel = ComposePanel().apply { setContent(content) }
                        owner.contentPane.add(panel)
                        owner.validate()
                    }
                }
            }
            runBlocking {
                awaitColor("cold-start", placeholder)
                awaitCondition("cold Chromium request", 60_000) {
                    onEdt { check(initial.error == null) { initial.error.orEmpty() } }
                    bootstrapRequested.isDone
                }
                assertPlaceholder("waiting-for-first-response")

                releaseBootstrap.countDown()
                awaitCondition("white bootstrap document completed") {
                    onEdt { !initial.isLoading && initial.url == "$base/sso/client" } && bootstrapLoaded.isDone
                }
                assertTrue(onEdt { initial.clientAuthenticating })
                assertPlaceholder("loaded-white-bootstrap")

                // 退出动画中的页面仍留在组合内，但原生遮罩必须随交互权立即撤销。
                onEdt { interactive.value = false }
                awaitColor("inactive-bootstrap-without-native-mask", 0xffffff)
                assertTrue(onEdt { initial.clientAuthenticating && !initial.isLoading })
                onEdt { interactive.value = true }
                awaitColor("reactivated-bootstrap-mask", placeholder)
                assertPlaceholder("reactivated-bootstrap-mask")

                onEdt { initial.submitClientLogin("$base/sso/client", null) }
                awaitColor("forum-home", 0x249464, rejectWhite = true) {
                    onEdt { !initial.isLoading && !initial.clientAuthenticating && initial.error == null && initial.url == "$base/home" } &&
                        homeLoaded.isDone
                }
                val clickAt = onEdt {
                    panel.locationOnScreen.apply { translate(panel.width / 2, panel.height / 2) }
                }
                robot.mouseMove(clickAt.x, clickAt.y)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                awaitCondition("native page click") { clicked.isDone }
                awaitColor("interactive-home", 0x3158be)

                transparentMenu?.let { menu ->
                    onEdt { pageVisible.value = false }
                    awaitColor("browser-host-hidden", gameBackground)
                    assertTrue(onEdt { initial.controller != null })
                    onEdt {
                        val resized = Rectangle(menu.bounds).apply {
                            translate(24, 18)
                            grow(30, 20)
                        }
                        frame?.bounds = resized
                        menu.bounds = resized
                        pageVisible.value = true
                    }
                    awaitColor("resized-browser-host", 0x3158be) {
                        onEdt { menu.ownedWindows.any { it.isShowing && it.bounds == menu.bounds } }
                    }
                }

                onEdt { initial.controller!!.loadUrl("$base/next") }
                awaitCondition("subsequent navigation waiting") {
                    nextRequested.isDone && onEdt { initial.isLoading }
                }
                assertTrue(matches(screenshot(), 0x3158be), "Normal navigation keeps the previously displayed page visible")
                releaseNext.countDown()
                awaitCondition("subsequent page completed") {
                    onEdt { !initial.isLoading && initial.url == "$base/next" }
                }
                awaitColor("subsequent-navigation", 0x7044a8)
                onEdt { initial.controller!!.loadUrl("$base/plain") }
                assertPlainPage("plain-after-navigation", initial)

                // A non-focusable native Popup must remain clickable after Chromium receives focus.
                onEdt { exitVisible.value = true }
                val exitOffset = onEdt { Point(panel.width - 28, ((panel.height - 56) * 0.35).toInt() + 28) }
                awaitCondition("native exit button") {
                    val image = screenshot()
                    val pixel = image.getRGB(exitOffset.x, exitOffset.y)
                    val buttonVisible = listOf(0, 8, 16).any { shift -> (pixel shr shift and 255) < 240 }
                    if (buttonVisible) save("floating-exit", image)
                    buttonVisible
                }
                val exitAt = onEdt { panel.locationOnScreen.apply { translate(exitOffset.x, exitOffset.y) } }
                robot.mouseMove(exitAt.x, exitAt.y)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                awaitCondition("previous browser disposal") { onEdt { initial.controller == null } }
                onEdt { exitVisible.value = false }
                awaitColor("closed-browser-host", placeholder)
                val reopened = EmbeddedBrowserState("$base/failure")
                onEdt { selected.value = reopened }
                // 暖 runtime 创建原生网页很快，从重开当下持续检查，防止新 Popup 迟到露白。
                awaitColor("reopened-http-failure", placeholder, rejectWhite = true) {
                    onEdt { reopened.error == "HTTP 503" && !reopened.isLoading }
                }
                assertPlaceholder("reopened-http-failure")
                failRequest.set(false)
                onEdt { reopened.retryLoad() }
                awaitCondition("recovery response waiting") {
                    recoveryRequested.isDone && onEdt { reopened.isLoading }
                }
                assertPlaceholder("retry-waiting-for-response")
                releaseRecovery.countDown()
                awaitColor("recovered-page", 0x249464, rejectWhite = true) {
                    onEdt { reopened.error == null && !reopened.isLoading }
                }
                assertFalse(onEdt { reopened.clientAuthenticating })

                onEdt { selected.value = null }
                awaitCondition("recovered browser disposal") { onEdt { reopened.controller == null } }
                val plain = EmbeddedBrowserState("$base/plain")
                onEdt { selected.value = plain }
                assertPlainPage("plain-after-reopen", plain)
            }
        } finally {
            releaseBootstrap.countDown()
            releaseNext.countDown()
            releaseRecovery.countDown()
            onEdt { selected.value = null; transparentMenu?.dispose(); frame?.dispose() }
            if (CefApp.getState() == CefApp.CefAppState.INITIALIZED) CefApp.getInstance().dispose()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private suspend fun awaitCondition(description: String, timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        try {
            withTimeout(timeoutMillis) { while (!condition()) delay(25) }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $description", error)
        }
    }

    private fun matches(image: BufferedImage, expected: Int): Boolean =
        listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3).all { (x, y) ->
            val actual = image.getRGB(image.width * x / 4, image.height * y / 4)
            listOf(0, 8, 16).all { shift -> abs((actual shr shift and 255) - (expected shr shift and 255)) <= 8 }
        }

    private fun respond(exchange: HttpExchange, status: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun solidPage(color: String): String =
        "<!doctype html><html style=\"background:$color\"><body style=\"margin:0;background:$color\"></body></html>"

    private fun <T> onEdt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        var value: T? = null
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try { value = action() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
