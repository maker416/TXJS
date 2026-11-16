/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.swing.SkiaSwingLayer
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** 使用真实菜单窗口与定向 AWT 输入，不移动系统鼠标或向其他应用发送输入。 */
class DesktopMenuInputWarmupWindowTest {
    @Test
    fun nativeWarmupHandsOffOnceAndLeavesTheNextClickUsable() = verifySuccessfulHandoff(native = true)

    @Test
    fun softwareWarmupHandsOffOnceAndLeavesTheNextClickUsable() = verifySuccessfulHandoff(native = false)

    @OptIn(ExperimentalSkikoApi::class)
    private fun verifySuccessfulHandoff(native: Boolean) = withFixture(native = native) { fixture ->
        assertTrue(fixture.finished.await(15, TimeUnit.SECONDS), "Menu input warmup did not finish")
        assertEquals(true, fixture.result.get(), "The real renderer did not complete its input warmup")
        assertTrue(fixture.warmupPainted.count == 0L, "Warmup must draw before handing off")
        await("the menu after warmup") { fixture.menuFrames.get() > 0 }
        onEdt {
            assertEquals(native, fixture.overlay.isNativeRendering)
            if (native) assertEquals(GraphicsApi.DIRECT3D, (fixture.overlay.contentComponent as SkiaLayer).renderApi)
            assertEquals(1, fixture.callbacks.get(), "Warmup must complete only once")
            assertEquals(1, fixture.menuCompositions.get(), "The final menu must enter composition only once")
            assertEquals(0, fixture.clicks.get(), "Warmup input escaped into the final menu")
            assertFalse(fixture.menuPressed.get(), "Warmup left a pressed state in the final menu")
            assertSame(fixture.host, fixture.canvas.parent)
            assertTrue(fixture.canvas.isDisplayable, "Warmup destroyed the game Canvas peer")
        }

        val input = onEdtValue { inputComponent(fixture.overlay.contentComponent) }
        assertTrue(input != null && input.isShowing, "The displayed menu has no input component")
        onEdt { sendMouse(input, MouseEvent.MOUSE_ENTERED, false) }
        onEdt { sendMouse(input, MouseEvent.MOUSE_MOVED, false) }
        onEdt { sendMouse(input, MouseEvent.MOUSE_PRESSED, true) }
        await("a fresh press after warmup") { fixture.menuPressed.get() }
        onEdt { sendMouse(input, MouseEvent.MOUSE_RELEASED, false) }
        await("the first real click after warmup") { fixture.clicks.get() == 1 && !fixture.menuPressed.get() }
        onEdt {
            fixture.overlay.refresh()
            assertEquals(1, fixture.callbacks.get())
            assertEquals(1, fixture.menuCompositions.get())
            assertEquals(1, fixture.clicks.get(), "A release must not create duplicate clicks")
        }
    }

    @Test
    fun unavailableInputRendererLetsTheMenuOpen() = withFixture(invalidInput = true) { fixture ->
        assertTrue(fixture.finished.await(10, TimeUnit.SECONDS), "Missing renderer kept startup waiting")
        assertEquals(false, fixture.result.get())
        await("the menu after a renderer failure") { fixture.menuFrames.get() > 0 }
        assertEquals(1, fixture.callbacks.get())
        assertEquals(1, fixture.menuCompositions.get())
    }

    @Test
    fun timeoutLetsTheMenuOpen() = withFixture(timeoutMillis = 1L) { fixture ->
        assertTrue(fixture.finished.await(10, TimeUnit.SECONDS), "Warmup timeout kept startup waiting")
        assertEquals(false, fixture.result.get())
        await("the menu after warmup timeout") { fixture.menuFrames.get() > 0 }
        assertEquals(1, fixture.callbacks.get())
        assertEquals(1, fixture.menuCompositions.get())
    }

    @Test
    fun disposingTheWindowCancelsWarmupWithoutOpeningTheMenu() = withFixture(cancelAfterLookup = true) { fixture ->
        assertTrue(fixture.inputLookedUp.await(10, TimeUnit.SECONDS), "Warmup never reached its renderer")
        await("window disposal during warmup") { fixture.disposed.get() }
        assertFalse(fixture.finished.await(700, TimeUnit.MILLISECONDS), "Disposed warmup published a late completion")
        assertEquals(0, fixture.callbacks.get())
        assertEquals(0, fixture.menuCompositions.get(), "Disposal composed the menu after cancellation")
        onEdt { assertFalse(fixture.frame.isDisplayable) }
    }

    private fun withFixture(
        native: Boolean = true,
        invalidInput: Boolean = false,
        cancelAfterLookup: Boolean = false,
        timeoutMillis: Long = 10_000L,
        verify: (Fixture) -> Unit,
    ) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        assumeFalse(GraphicsEnvironment.isHeadless())
        assumeTrue(GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            .isWindowTranslucencySupported(PERPIXEL_TRANSLUCENT))
        val previousLayers = System.getProperty("compose.layers.type")
        val previousApi = System.getProperty("skiko.renderApi")
        var fixture: Fixture? = null
        try {
            System.setProperty("compose.layers.type", "WINDOW")
            System.setProperty("skiko.renderApi", if (native) "OPENGL" else "SOFTWARE_FAST")
            assertTrue(DesktopWindowLayers.install())
            runCatching { stopKoin() }
            appKoin = startKoin { modules(module { single { Settings(enableAnimations = true) } }) }.koin
            val current = Fixture(native, invalidInput, cancelAfterLookup, timeoutMillis)
            fixture = current
            onEdt { current.open() }
            verify(current)
        } finally {
            try {
                fixture?.let { current -> onEdt { current.dispose() } }
            } finally {
                stopKoin()
                if (previousLayers == null) System.clearProperty("compose.layers.type")
                else System.setProperty("compose.layers.type", previousLayers)
                if (previousApi == null) System.clearProperty("skiko.renderApi")
                else System.setProperty("skiko.renderApi", previousApi)
            }
        }
    }

    private class Fixture(
        private val native: Boolean,
        private val invalidInput: Boolean,
        private val cancelAfterLookup: Boolean,
        private val timeoutMillis: Long,
    ) {
        val finished = CountDownLatch(1)
        val warmupPainted = CountDownLatch(1)
        val inputLookedUp = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val callbacks = AtomicInteger()
        val menuCompositions = AtomicInteger()
        val menuFrames = AtomicInteger()
        val clicks = AtomicInteger()
        val menuPressed = AtomicBoolean()
        val disposed = AtomicBoolean()
        private val disposalQueued = AtomicBoolean()
        private var ready by mutableStateOf(false)
        lateinit var frame: JFrame
        lateinit var overlay: DesktopLauncherOverlay
        lateinit var canvas: Canvas
        lateinit var host: JPanel

        fun open() {
            canvas = Canvas().apply { background = java.awt.Color.BLACK }
            host = JPanel(BorderLayout()).apply { add(canvas, BorderLayout.CENTER) }
            frame = JFrame("RWJS menu input warmup regression").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                add(host)
                setSize(800, 600)
                setLocationRelativeTo(null)
            }
            val content: @Composable () -> Unit = {
                if (!ready) {
                    Box(Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        warmupPainted.countDown()
                    }) {
                        DesktopMenuInputWarmup(
                            message = "Preparing launcher input",
                            inputComponent = {
                                inputLookedUp.countDown()
                                if (cancelAfterLookup && disposalQueued.compareAndSet(false, true)) {
                                    // 同步 dispose 会使当前查找失败；下一轮 EDT 取消的是等待实际绘制的协程。
                                    SwingUtilities.invokeLater { dispose() }
                                }
                                if (invalidInput) JPanel() else overlay.contentComponent
                            },
                            onFinished = {
                                callbacks.incrementAndGet()
                                result.set(it)
                                ready = true
                                finished.countDown()
                            },
                            timeoutMillis = timeoutMillis,
                        )
                    }
                } else {
                    DisposableEffect(Unit) {
                        menuCompositions.incrementAndGet()
                        onDispose { }
                    }
                    val interactions = remember { MutableInteractionSource() }
                    val pressed by interactions.collectIsPressedAsState()
                    // 在组合中读取以订阅状态；只在 SideEffect 内读取不会触发后续重组。
                    val pressedValue = pressed
                    SideEffect { menuPressed.set(pressedValue) }
                    MaterialTheme {
                        Surface(
                            onClick = { clicks.incrementAndGet() },
                            interactionSource = interactions,
                            color = Color(0xFF173A21),
                            modifier = Modifier.fillMaxSize().drawWithContent {
                                drawContent()
                                menuFrames.incrementAndGet()
                            },
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Ready menu") }
                        }
                    }
                }
            }
            val fallback = DesktopLauncherOverlay.createMenuPanel(liveBackground = true).apply {
                isOpaque = false
                setContent(content)
            }
            overlay = DesktopLauncherOverlay(fallback, host, frame, nativeContent = content.takeIf { native })
            frame.isVisible = true
            overlay.showMenu()
        }

        fun dispose() {
            if (!disposed.compareAndSet(false, true)) return
            val windows = descendants(frame) + frame
            overlay.dispose()
            windows.forEach(Window::dispose)
        }

        private fun descendants(owner: Window): List<Window> =
            owner.ownedWindows.flatMap { listOf(it) + descendants(it) }
    }

    @OptIn(ExperimentalSkikoApi::class)
    private fun inputComponent(component: Component): Component? = when (component) {
        is SkiaLayer -> component.canvas
        is SkiaSwingLayer -> component
        is Container -> component.components.firstNotNullOfOrNull(::inputComponent)
        else -> null
    }

    private fun sendMouse(component: Component, id: Int, down: Boolean) {
        val button = if (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED) MouseEvent.BUTTON1 else MouseEvent.NOBUTTON
        component.dispatchEvent(MouseEvent(
            component, id, System.currentTimeMillis(), if (down) InputEvent.BUTTON1_DOWN_MASK else 0,
            component.width / 2, component.height / 2,
            if (button == MouseEvent.BUTTON1) 1 else 0, false, button,
        ))
    }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }

    private fun <T> onEdtValue(action: () -> T): T {
        val result = AtomicReference<T>()
        onEdt { result.set(action()) }
        return result.get()
    }

    private fun await(stage: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("Menu input warmup did not reach the expected state: $stage")
    }
}
