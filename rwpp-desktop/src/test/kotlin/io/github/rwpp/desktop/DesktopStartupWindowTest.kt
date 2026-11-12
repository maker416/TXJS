/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.awt.BorderLayout
import java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** 原生窗口回归：图片解码尚未完成时，启动页必须已能绘制、播放动画和接收 EDT 事件。 */
class DesktopStartupWindowTest {
    @Test fun loadingAnimationRemainsResponsiveWhileTitleIsDecoding() = verify(animations = true)

    @Test fun staticLoadingCanStillUpdateProgressWhileTitleIsDecoding() = verify(animations = false)

    private fun verify(animations: Boolean) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        assumeFalse(GraphicsEnvironment.isHeadless())
        assumeTrue(GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            .isWindowTranslucencySupported(PERPIXEL_TRANSLUCENT))
        val previousLayers = System.getProperty("compose.layers.type")
        val previousApi = System.getProperty("skiko.renderApi")
        System.setProperty("compose.layers.type", "WINDOW")
        System.setProperty("skiko.renderApi", "OPENGL")
        assertTrue(DesktopWindowLayers.install())
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = animations) } }) }.koin

        val decoderEntered = CountDownLatch(1)
        val releaseDecoder = CountDownLatch(1)
        val decoderExited = CountDownLatch(1)
        val titlePainted = CountDownLatch(1)
        val firstFrame = CountDownLatch(1)
        val decoderOnEdt = AtomicBoolean()
        val startupFrames = AtomicInteger()
        val menuFrames = AtomicInteger()
        val menuCompositions = AtomicInteger()
        val drawnMessage = AtomicReference<String>()
        var message by mutableStateOf("Preparing launcher")
        val diagnostics = DesktopStartupDiagnostics.start()
        var owner: JFrame? = null
        var startupOverlay: DesktopLauncherOverlay? = null
        var menuOverlay: DesktopLauncherOverlay? = null
        var ownedWindows = emptyList<Window>()
        lateinit var startupPanel: ComposePanel
        lateinit var menuPanel: ComposePanel
        lateinit var canvas: DesktopGameCanvas
        lateinit var host: JPanel
        lateinit var startupWindow: JDialog
        lateinit var menuWindow: JDialog
        try {
            onEdt {
                canvas = DesktopGameCanvas().apply { background = java.awt.Color.BLACK }
                host = JPanel(BorderLayout()).apply { add(canvas, BorderLayout.CENTER) }
                val frame = JFrame("RWJS startup window regression").apply {
                    add(host)
                    setSize(800, 600)
                    setLocationRelativeTo(null)
                }
                owner = frame
                val menuContent: @Composable () -> Unit = {
                        DisposableEffect(Unit) {
                            menuCompositions.incrementAndGet()
                            onDispose { menuCompositions.decrementAndGet() }
                        }
                        Box(Modifier.fillMaxSize().background(Color(0xFF173A21)).drawWithContent {
                            drawContent()
                            menuFrames.incrementAndGet()
                        })
                }
                menuPanel = DesktopLauncherOverlay.createMenuPanel(liveBackground = true).apply {
                    isOpaque = false
                    setContent(menuContent)
                }
                menuOverlay = DesktopLauncherOverlay(menuPanel, host, frame, initiallyVisible = false, nativeContent = menuContent)
                // Native/fullscreen refresh may arrive before the owner is shown; it must retain the startup gate.
                menuOverlay!!.refresh()
                assertFalse(menuOverlay!!.isNativeRendering)
                menuWindow = SwingUtilities.getWindowAncestor(menuPanel) as JDialog
                startupPanel = ComposePanel().apply {
                    isOpaque = true
                    setContent {
                        val currentMessage = message
                        Box(Modifier.fillMaxSize()) {
                            DesktopStartupView(currentMessage, onFrameRendered = {
                                drawnMessage.set(currentMessage)
                                startupFrames.incrementAndGet()
                                diagnostics.onFrameRendered(continuousFramesExpected = animations)
                                firstFrame.countDown()
                            }, titleLoader = {
                                decoderOnEdt.set(SwingUtilities.isEventDispatchThread())
                                decoderEntered.countDown()
                                try {
                                    check(releaseDecoder.await(20, TimeUnit.SECONDS)) { "Timed out releasing the test title decoder" }
                                    object : Painter() {
                                        override val intrinsicSize = Size(240f, 80f)
                                        override fun DrawScope.onDraw() {
                                            drawRect(Color(0xFFA7D989))
                                            titlePainted.countDown()
                                        }
                                    }
                                } finally {
                                    decoderExited.countDown()
                                }
                            })
                        }
                    }
                }
                startupOverlay = DesktopLauncherOverlay(startupPanel, host, frame, opaque = true)
                startupWindow = SwingUtilities.getWindowAncestor(startupPanel) as JDialog
                frame.isVisible = true
                startupOverlay!!.showMenu()
                startupOverlay!!.refresh()
                menuOverlay!!.refresh()
            }

            assertTrue(decoderEntered.await(8, TimeUnit.SECONDS), "Title decoding did not start")
            assertFalse(decoderOnEdt.get(), "Title decoding ran on EDT")
            assertTrue(firstFrame.await(8, TimeUnit.SECONDS), "Title decoding prevented the first loading frame")
            assertEquals(1L, decoderExited.count, "The decoder must still be blocked while checking loading animation")
            onEdt {
                assertEquals(255, startupWindow.background.alpha, "Startup must use an opaque native window")
                assertTrue(startupWindow.rootPane.isOpaque)
                assertTrue(startupWindow.contentPane.isOpaque)
                assertTrue(startupOverlay!!.isMenuVisible)
                assertFalse(menuOverlay!!.isMenuVisible, "The transparent menu was shown before startup finished")
                assertFalse(menuOverlay!!.isNativeRendering, "Refreshing a hidden menu initialized its native content")
                assertFalse(menuWindow.isVisible)
                assertEquals(0, menuFrames.get(), "A hidden transparent menu rendered before handoff")
                assertEquals(0, menuCompositions.get(), "A hidden transparent menu composed before handoff")
                assertTrue(canvas.isDisplayable)
                assertFalse(canvas.startupInputEnabled)
            }
            // Include refresh's 120ms/320ms follow-ups while the title decoder is still deliberately blocked.
            repeat(8) {
                Thread.sleep(50)
                onEdt {
                    assertTrue(startupOverlay!!.isMenuVisible)
                    assertFalse(menuOverlay!!.isMenuVisible)
                    assertFalse(menuOverlay!!.isNativeRendering)
                    assertFalse(menuWindow.isVisible)
                    assertEquals(0, menuCompositions.get(), "A delayed refresh bypassed the hidden menu gate")
                    assertEquals(0, menuFrames.get())
                }
            }

            // 复现 WindowsDisplay$2/$3：LWJGL 先重置 focusable，之后才在 EDT 请求焦点。
            // 仅设置 isFocusable=false 无法拦住这条路径；加载期应在请求入口直接返回。
            val loadingFocusAccepted = AtomicBoolean(true)
            val loadingFocusOwner = AtomicBoolean(true)
            val loadingHeartbeat = CountDownLatch(1)
            SwingUtilities.invokeLater {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
                canvas.isFocusable = false
                canvas.isFocusable = true
                canvas.requestFocus()
                loadingFocusAccepted.set(canvas.requestFocusInWindow())
                SwingUtilities.invokeLater {
                    loadingFocusOwner.set(canvas.isFocusOwner)
                    loadingHeartbeat.countDown()
                }
            }
            assertTrue(loadingHeartbeat.await(2, TimeUnit.SECONDS), "A startup Canvas focus request blocked the next EDT heartbeat")
            assertFalse(loadingFocusAccepted.get(), "Resetting focusable bypassed the startup input gate")
            assertFalse(loadingFocusOwner.get(), "The game Canvas took focus while startup was still loading")
            onEdt { assertFalse(canvas.isFocusOwner) }

            if (animations) {
                val firstDrawCount = startupFrames.get()
                await("continuous loading animation while decoding") { startupFrames.get() > firstDrawCount + 1 }
                assertEquals(1L, decoderExited.count)
            }
            val updatedMessage = "Loading units 12 / 40"
            onEdt { message = updatedMessage }
            await("updated progress while decoding") { drawnMessage.get() == updatedMessage }
            assertEquals(1L, decoderExited.count)

            releaseDecoder.countDown()
            assertTrue(decoderExited.await(3, TimeUnit.SECONDS), "The title decoder did not finish after release")
            assertTrue(titlePainted.await(5, TimeUnit.SECONDS), "The asynchronously decoded title was not rendered")
            onEdt {
                startupWindow.toFront()
                startupOverlay!!.focusMenu()
            }
            await("startup keyboard focus before handoff") {
                onEdtValue { KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow === startupWindow }
            }
            onEdt {
                menuOverlay!!.showMenu()
                menuWindow = SwingUtilities.getWindowAncestor(menuOverlay!!.contentComponent) as JDialog
            }
            await("first transparent menu frame") { menuFrames.get() > 0 }
            onEdt {
                val startupHadFocus = startupWindow.isFocused
                assertTrue(startupHadFocus, "Preparing the menu stole keyboard focus from the startup window")
                startupOverlay!!.dispose()
                // Match Main's first-menu-frame handoff: only transfer input if the startup layer held it.
                if (startupHadFocus) menuOverlay!!.focusMenu()
                assertFalse(startupWindow.isDisplayable)
                assertTrue(menuOverlay!!.isMenuVisible)
                assertTrue(menuOverlay!!.isNativeRendering, "The handoff must exercise the native Direct3D menu")
                assertTrue(menuWindow.isShowing)
                assertEquals(1, menuCompositions.get(), "Only one menu scene may compose during the handoff")
                assertTrue(canvas.isDisplayable, "Startup handoff destroyed the game Canvas peer")
                assertSame(host, canvas.parent, "Startup handoff moved the game Canvas")
            }
            await("menu keyboard focus after disposing the focused startup window") {
                onEdtValue {
                    val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    val focusOwner = manager.focusOwner
                    val content = menuOverlay!!.contentComponent
                    manager.focusedWindow === menuWindow && focusOwner != null &&
                        (focusOwner === content || SwingUtilities.isDescendingFrom(focusOwner, content))
                }
            }
            onEdt {
                assertFalse(canvas.isFocusOwner, "The loading-to-menu handoff focused the game Canvas")
                assertFalse(canvas.startupInputEnabled)
                assertSame(host, canvas.parent)
                assertTrue(canvas.isDisplayable)
                assertEquals("OPENGL", System.getProperty("skiko.renderApi"), "The menu changed the global renderer preference")
            }

            val nativeFocusReturned = CountDownLatch(1)
            onEdt {
                canvas.startupInputEnabled = true
                menuOverlay!!.showGame()
                owner!!.toFront()
                owner!!.requestFocus()
            }
            SwingUtilities.invokeLater {
                if (!canvas.requestFocusInWindow()) canvas.requestFocus()
                SwingUtilities.invokeLater { nativeFocusReturned.countDown() }
            }
            assertTrue(nativeFocusReturned.await(2, TimeUnit.SECONDS), "Canvas focus requests did not return after enabling game input")
            // Windows 可能拒绝后台测试进程抢前台；宿主已获焦时，Canvas 必须恢复正常获焦。
            val focusDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var canvasFocused = false
            while (!canvasFocused && System.nanoTime() < focusDeadline) {
                onEdt { canvasFocused = canvas.isFocusOwner }
                if (!canvasFocused) Thread.sleep(20)
            }
            onEdt {
                assertTrue(canvas.startupInputEnabled)
                assertTrue(canvas.isDisplayable)
                assertFalse(menuWindow.isVisible)
                if (owner!!.isFocused) {
                    assertTrue(canvasFocused, "The focused game window did not return focus to its Canvas")
                    assertSame(canvas, KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner,
                        "Canvas focus did not remain stable after the queued native request")
                }
            }
        } finally {
            releaseDecoder.countDown()
            diagnostics.close()
            try {
                onEdt {
                    owner?.let { frame ->
                        ownedWindows = descendantWindows(frame) + frame
                        startupOverlay?.dispose()
                        menuOverlay?.dispose()
                        ownedWindows.forEach(Window::dispose)
                    }
                }
                assertTrue(diagnostics.awaitTermination(2_000), "Startup diagnostics kept running after closing its windows")
                if (decoderEntered.count == 0L) {
                    assertTrue(decoderExited.await(3, TimeUnit.SECONDS), "A title decoding task remained blocked after teardown")
                }
                onEdt { assertTrue(ownedWindows.none { it.isVisible || it.isDisplayable }, "A test-owned native window survived teardown") }
            } finally {
                stopKoin()
                if (previousLayers == null) System.clearProperty("compose.layers.type")
                else System.setProperty("compose.layers.type", previousLayers)
                if (previousApi == null) System.clearProperty("skiko.renderApi")
                else System.setProperty("skiko.renderApi", previousApi)
            }
        }
    }

    private fun descendantWindows(owner: Window): List<Window> =
        owner.ownedWindows.flatMap { listOf(it) + descendantWindows(it) }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }

    private fun <T> onEdtValue(action: () -> T): T {
        val result = AtomicReference<T>()
        onEdt { result.set(action()) }
        return result.get()
    }

    private fun await(stage: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("Startup window did not reach the expected state: $stage")
    }
}
