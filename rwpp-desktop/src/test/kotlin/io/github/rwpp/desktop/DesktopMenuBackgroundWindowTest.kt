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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.rwpp.logger
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.slf4j.LoggerFactory
import java.awt.Canvas
import java.awt.Color
import java.awt.Frame
import java.awt.Graphics
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.awt.event.InputEvent
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
import kotlin.math.abs
import kotlin.math.roundToInt

/** Uses native windows: an offscreen Compose test cannot detect an opaque menu over the engine Canvas. */
class DesktopMenuBackgroundWindowTest {
    @Test
    fun transparentMenuShowsCanvasUpdatesReceivesClicksAndPreservesCanvasPeer() {
        requireNativeTransparency()
        val fixture = createFixture()
        val robot = Robot().apply { autoDelay = 30 }
        try {
            await("menu above canvas") { fixture.overlay.isMenuVisible && fixture.menu.isShowing && fixture.canvas.isShowing }
            val menuWindow = onEdtValue { SwingUtilities.getWindowAncestor(fixture.menu) as JDialog }
            onEdt {
                assertSame(fixture.frame, menuWindow.owner)
                assertTrue(menuWindow.isUndecorated)
                assertFalse(menuWindow.isAlwaysOnTop)
                assertEquals(0, menuWindow.background.alpha)
                assertSame(fixture.host, fixture.canvas.parent)
                assertTrue(fixture.canvas.isDisplayable)
            }

            val sample = onEdtValue {
                fixture.host.locationOnScreen.apply { translate(fixture.host.width * 3 / 4, fixture.host.height * 3 / 4) }
            }
            awaitPixel(robot, sample, FIRST_COLOR)
            onEdt {
                fixture.canvas.paintColor = SECOND_COLOR
                fixture.canvas.repaint()
            }
            awaitPixel(robot, sample, SECOND_COLOR)

            await("Compose button layout") { fixture.buttonCenter.get() != null }
            val buttonPoint = onEdtValue {
                fixture.menu.locationOnScreen.apply {
                    val localCenter = fixture.buttonCenter.get()!!
                    translate(localCenter.x, localCenter.y)
                }
            }
            robot.mouseMove(buttonPoint.x, buttonPoint.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            await("menu button click") { fixture.clicks.get() == 1 }

            onEdt {
                fixture.switcher.switchTo(DisplayMode.Game)
                assertFalse(fixture.overlay.isMenuVisible)
                assertFalse(fixture.menu.isShowing)
                assertFalse(menuWindow.isVisible)
                assertTrue(fixture.canvas.isShowing)
                assertTrue(fixture.canvas.isDisplayable)
                assertSame(fixture.host, fixture.canvas.parent)
                assertTrue(fixture.frame.ownedWindows.none { it.isVisible })
                fixture.switcher.switchTo(DisplayMode.Menu)
            }
            await("return to transparent menu") { fixture.overlay.isMenuVisible && fixture.menu.isShowing }
            onEdt {
                assertSame(menuWindow, SwingUtilities.getWindowAncestor(fixture.menu))
                assertSame(fixture.host, fixture.canvas.parent)
                assertTrue(fixture.canvas.isShowing)
                assertTrue(fixture.canvas.isDisplayable)
            }
            awaitPixel(robot, sample, SECOND_COLOR)
        } finally {
            fixture.dispose()
        }
    }

    @Test
    fun menuTracksViewportAndOwnerVisibilityWithoutFloatingAboveOtherApplications() {
        requireNativeTransparency()
        val fixture = createFixture()
        try {
            await("initial menu") { fixture.menu.isShowing }
            val menuWindow = onEdtValue { SwingUtilities.getWindowAncestor(fixture.menu) as JDialog }
            onEdt {
                fixture.frame.setSize(840, 580)
                fixture.frame.setLocation(fixture.frame.x + 24, fixture.frame.y + 16)
            }
            await("resized menu viewport") {
                menuWindow.bounds == Rectangle(fixture.host.locationOnScreen, fixture.host.size)
            }
            onEdt {
                assertFalse(menuWindow.isAlwaysOnTop)
                fixture.frame.extendedState = Frame.ICONIFIED
            }
            await("minimized menu") { !menuWindow.isShowing }
            onEdt { fixture.frame.extendedState = Frame.NORMAL }
            await("restored menu") { fixture.menu.isShowing && fixture.overlay.isMenuVisible }
            onEdt { fixture.frame.isVisible = false }
            await("hidden owner menu") { !menuWindow.isVisible }
            onEdt { fixture.frame.isVisible = true }
            await("reshown owner menu") { fixture.menu.isShowing }
            onEdt {
                assertEquals(Rectangle(fixture.host.locationOnScreen, fixture.host.size), menuWindow.bounds)
                assertSame(fixture.host, fixture.canvas.parent)
                assertTrue(fixture.canvas.isDisplayable)
            }
        } finally {
            fixture.dispose()
        }
    }

    private fun createFixture(): Fixture {
        val previousLayerType = System.getProperty("compose.layers.type")
        System.setProperty("compose.layers.type", "WINDOW")
        return try {
            assertTrue(DesktopWindowLayers.install())
            createFixtureOnEdt(previousLayerType)
        } catch (failure: Throwable) {
            restoreLayerType(previousLayerType)
            throw failure
        }
    }

    private fun createFixtureOnEdt(previousLayerType: String?): Fixture = onEdtValue {
        logger = LoggerFactory.getLogger(DesktopMenuBackgroundWindowTest::class.java)
        val canvas = ColorCanvas()
        val layout = NativeCanvasCardLayout(canvas) { 1.0 }
        val host = JPanel(layout).apply { add(canvas, DisplayMode.Game.cardName) }
        val clicks = AtomicInteger()
        val buttonCenter = AtomicReference<Point?>()
        val menu = DesktopLauncherOverlay.createMenuPanel(liveBackground = true).apply {
            isOpaque = false
            setContent {
                val density = LocalDensity.current.density
                MaterialTheme {
                    // Keep a minimally nonzero alpha so Windows routes blank-area input to the menu.
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 1f / 255f))) {
                        Button(
                            onClick = { clicks.incrementAndGet() },
                            modifier = Modifier.offset(30.dp, 30.dp).size(160.dp, 45.dp)
                                .onGloballyPositioned { coordinates ->
                                    val center = coordinates.boundsInWindow().center
                                    buttonCenter.set(Point((center.x / density).roundToInt(), (center.y / density).roundToInt()))
                                },
                        ) { Text("Start match") }
                    }
                }
            }
        }
        val frame = JFrame("RWJS live menu background regression").apply {
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            add(host)
            setSize(760, 520)
            setLocationRelativeTo(null)
            isVisible = true
        }
        val overlay = DesktopLauncherOverlay(menu, host, frame)
        val switcher = DisplaySwitcher(menu, canvas, host, layout, frame, launcherOverlay = overlay)
        overlay.showMenu()
        Fixture(frame, host, canvas, menu, buttonCenter, clicks, overlay, switcher, previousLayerType)
    }

    private class ColorCanvas : Canvas() {
        var paintColor: Color = FIRST_COLOR
        override fun paint(graphics: Graphics) {
            graphics.color = paintColor
            graphics.fillRect(0, 0, width, height)
        }
        override fun update(graphics: Graphics) = paint(graphics)
    }

    private data class Fixture(
        val frame: JFrame,
        val host: JPanel,
        val canvas: ColorCanvas,
        val menu: ComposePanel,
        val buttonCenter: AtomicReference<Point?>,
        val clicks: AtomicInteger,
        val overlay: DesktopLauncherOverlay,
        val switcher: DisplaySwitcher,
        val previousLayerType: String?,
    ) {
        fun dispose() {
            try {
                SwingUtilities.invokeAndWait {
                    overlay.dispose()
                    frame.ownedWindows.forEach(Window::dispose)
                    frame.dispose()
                }
            } finally {
                restoreLayerType(previousLayerType)
            }
        }
    }

    private fun requireNativeTransparency() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        assumeFalse(GraphicsEnvironment.isHeadless())
        assumeTrue(GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT))
    }

    private fun awaitPixel(robot: Robot, point: Point, expected: Color) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var actual: Color
        do {
            actual = robot.getPixelColor(point.x, point.y)
            if (colorsMatch(expected, actual)) return
            Thread.sleep(20)
        } while (System.nanoTime() < deadline)
        assertTrue(colorsMatch(expected, actual),
            "Transparent Compose menu must reveal the current native Canvas frame at $point: expected $expected, got $actual")
    }

    private fun colorsMatch(expected: Color, actual: Color): Boolean =
        abs(expected.red - actual.red) <= 2 && abs(expected.green - actual.green) <= 2 && abs(expected.blue - actual.blue) <= 2

    private fun await(stage: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (onEdtValue(condition)) return
            Thread.sleep(20)
        }
        fail("Native menu did not reach expected state: $stage")
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    private fun <T> onEdtValue(action: () -> T): T {
        var result: T? = null
        SwingUtilities.invokeAndWait { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        val FIRST_COLOR = Color(32, 96, 144)
        val SECOND_COLOR = Color(64, 128, 48)

        fun restoreLayerType(previous: String?) {
            if (previous == null) System.clearProperty("compose.layers.type")
            else System.setProperty("compose.layers.type", previous)
        }
    }
}
