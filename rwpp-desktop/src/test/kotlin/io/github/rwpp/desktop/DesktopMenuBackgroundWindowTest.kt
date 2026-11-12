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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.rwpp.logger
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.slf4j.LoggerFactory
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import java.awt.Canvas
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Frame
import java.awt.Graphics
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
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
import javax.swing.JTextField
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
    fun transparentMenuShowsCanvasUpdatesReceivesClicksAndPreservesCanvasPeer() = verifyCanvasAndInput(native = false)

    @Test
    fun nativeTransparentMenuShowsCanvasUpdatesReceivesClicksAndPreservesCanvasPeer() =
        withRenderApi("OPENGL") { verifyCanvasAndInput(native = true) }

    @Test
    fun softwareRenderingChoiceRetainsTheCompatibilityMenu() =
        withRenderApi("SOFTWARE_FAST") { verifyCanvasAndInput(native = true, expectedNative = false) }

    @Test
    fun nativeCreationFailureFallsBackWithoutMovingCanvasOrComposingTwice() =
        verifyCanvasAndInput(native = true, failNative = true)

    @OptIn(ExperimentalSkikoApi::class)
    @Test
    fun rendererChangeReleasesNativeWindowAndRestoresCompatibilityMenu() = withRenderApi("OPENGL") {
        requireNativeTransparency()
        val fixture = createFixture(native = true)
        try {
            await("native menu composition") { fixture.overlay.isNativeRendering && fixture.activeContent.get() == 1 }
            val nativeWindow = onEdtValue { SwingUtilities.getWindowAncestor(fixture.menu) }
            onEdt {
                assertEquals(GraphicsApi.DIRECT3D, (fixture.menu as SkiaLayer).renderApi)
                // Simulate the API notification Skiko sends when the native device falls back.
                (fixture.menu as SkiaLayer).renderApi = GraphicsApi.SOFTWARE_FAST
            }
            await("compatibility recovery after native renderer change") {
                !fixture.overlay.isNativeRendering && fixture.menu.isShowing && fixture.activeContent.get() == 1
            }
            onEdt {
                assertFalse(nativeWindow.isDisplayable, "The unusable native window must be disposed")
                assertSame(fixture.fallbackMenu, fixture.menu)
                assertSame(fixture.host, fixture.canvas.parent)
                assertTrue(fixture.canvas.isDisplayable)
                fixture.switcher.switchTo(DisplayMode.Game)
                fixture.switcher.switchTo(DisplayMode.Menu)
            }
            await("recovered menu can return from game") { fixture.menu.isShowing && fixture.overlay.isMenuVisible }
        } finally {
            fixture.dispose()
        }
    }

    private fun verifyCanvasAndInput(native: Boolean, failNative: Boolean = false, expectedNative: Boolean = native && !failNative) {
        requireNativeTransparency()
        val fixture = createFixture(native = native, failNative = failNative)
        val robot = Robot().apply { autoDelay = 30 }
        try {
            await("menu above canvas") { fixture.overlay.isMenuVisible && fixture.menu.isShowing && fixture.canvas.isShowing }
            val menuWindow = onEdtValue { SwingUtilities.getWindowAncestor(fixture.menu) as JDialog }
            onEdt {
                assertEquals(expectedNative, fixture.overlay.isNativeRendering)
                assertEquals(1, fixture.activeContent.get(), "Only one menu surface may own the App composition")
                if (expectedNative) assertFalse(fixture.fallbackMenu.isDisplayable,
                    "The unused Swing menu must not initialize a second renderer")
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
    fun menuTracksViewportAndOwnerVisibilityWithoutFloatingAboveOtherApplications() = verifyViewport(native = false)

    @Test
    fun nativeMenuTracksViewportAndOwnerVisibilityWithoutFloatingAboveOtherApplications() = verifyViewport(native = true)

    private fun verifyViewport(native: Boolean) {
        requireNativeTransparency()
        val fixture = createFixture(native = native)
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

    @Test
    fun resizeUpdatesComposeViewportAndPaintsNewEdgesWithoutInput() = withRecoveryFixtures { fixture ->
        val robot = Robot()
        onEdt { fixture.frame.setLocation(40, 40) }
        val screen = onEdtValue { fixture.frame.graphicsConfiguration.bounds }
        val maximum = Dimension(screen.width - 100, screen.height - 140)
        val sizes = listOf(Dimension(900, 650), Dimension(620, 430), Dimension(820, 560))
            .map { Dimension(it.width.coerceAtMost(maximum.width), it.height.coerceAtMost(maximum.height)) }
        for (size in sizes) {
            onEdt { fixture.frame.size = size }
            await("Compose viewport after resize to $size") {
                fixture.menu.size == fixture.host.size && fixture.viewport.get() == fixture.host.size
            }
            // Sample before any input can accidentally wake rendering.
            val edge = onEdtValue {
                fixture.host.locationOnScreen.apply { translate(fixture.host.width - 8, fixture.host.height - 8) }
            }
            awaitPixel(robot, edge, EDGE_COLOR)
            onEdt {
                assertSame(fixture.host, fixture.canvas.parent)
                assertTrue(fixture.canvas.isDisplayable)
            }
        }
    }

    @Test
    fun explicitRefreshValidatesContentEvenWhenNativeBoundsAreUnchanged() = withRecoveryFixtures { fixture ->
        val window = onEdtValue { SwingUtilities.getWindowAncestor(fixture.menu) as JDialog }
        val originalBounds = onEdtValue { window.bounds }
        onEdt {
            // In native mode this is the displayed SkiaLayer, not the unmounted compatibility panel.
            fixture.menu.size = Dimension(80, 60)
            fixture.overlay.refresh()
            assertEquals(originalBounds, window.bounds)
            assertEquals(fixture.host.size, fixture.menu.size)
        }
        await("Compose viewport after invalid child layout") { fixture.viewport.get() == fixture.host.size }
        val edge = onEdtValue {
            fixture.host.locationOnScreen.apply { translate(fixture.host.width - 8, fixture.host.height - 8) }
        }
        awaitPixel(Robot(), edge, EDGE_COLOR)
    }

    @Test
    fun transientInvalidLayoutRecoversAndRestoresKeyboardWithoutAnotherComponentEvent() =
        withRecoveryFixtures { fixture ->
            for (notShowing in listOf(false, true)) {
                onEdt {
                    fixture.host.blockSize = !notShowing
                    fixture.host.blockShowing = notShowing
                    fixture.overlay.refresh()
                    assertTrue(fixture.overlay.isMenuVisible, "Transient layout must retain an existing menu")
                    fixture.host.blockSize = false
                    fixture.host.blockShowing = false
                    fixture.overlay.showGame()
                    fixture.frame.toFront()
                    fixture.frame.requestFocus()
                    fixture.canvas.requestFocusInWindow()
                }
                await("Canvas keyboard focus before returning to the menu") { fixture.canvas.isFocusOwner }
                onEdt {
                    fixture.host.blockSize = !notShowing
                    fixture.host.blockShowing = notShowing
                    fixture.overlay.showMenu()
                    assertFalse(fixture.overlay.isMenuVisible, "A hidden menu must wait for a valid viewport")
                }
                // Allow the original showMenu focus request to run while the menu is still hidden.
                Thread.sleep(240)
                onEdt {
                    fixture.host.blockSize = false
                    fixture.host.blockShowing = false
                }
                // These probe flags emit no component, hierarchy or focus events.
                await("automatic menu recovery without a component event") {
                    fixture.overlay.isMenuVisible && fixture.viewport.get() == fixture.host.size
                }
                await("keyboard focus after delayed menu recovery") { fixture.menuOwnsKeyboardFocus() }
            }
        }

    @Test
    fun refreshRestartsAnExhaustedLayoutRetryCycle() = withRecoveryFixtures { fixture ->
        onEdt {
            fixture.overlay.showGame()
            fixture.host.blockSize = true
            fixture.overlay.showMenu()
        }
        Thread.sleep(2100)
        onEdt { fixture.host.blockSize = false }
        Thread.sleep(240)
        onEdt { assertFalse(fixture.overlay.isMenuVisible, "An exhausted retry cycle must stop scheduling work") }
        onEdt {
            fixture.host.blockSize = true
            fixture.overlay.refresh()
        }
        Thread.sleep(200)
        onEdt { fixture.host.blockSize = false }
        await("new retry cycle after explicit refresh") { fixture.overlay.isMenuVisible }
    }

    @Test
    fun lateRecoveryTasksCannotReopenGameModeOrDisposedMenus() = withRecoveryFixtures { fixture ->
        onEdt {
            fixture.host.blockSize = true
            fixture.overlay.refresh()
            fixture.overlay.showGame()
            fixture.host.blockSize = false
        }
        Thread.sleep(500)
        onEdt {
            assertFalse(fixture.overlay.isMenuVisible, "A late refresh must not reopen the menu in game mode")
            assertTrue(fixture.frame.ownedWindows.none { it.isVisible })
            fixture.host.blockSize = true
            fixture.overlay.showMenu()
            fixture.overlay.refresh()
            fixture.overlay.dispose()
            fixture.host.blockSize = false
        }
        Thread.sleep(500)
        onEdt {
            assertFalse(fixture.overlay.isMenuVisible, "A late retry must not reopen a disposed menu")
            assertTrue(fixture.frame.ownedWindows.none { it.isVisible })
            assertEquals(0, fixture.activeContent.get(), "A disposed menu must not recreate its composition")
            assertSame(fixture.host, fixture.canvas.parent)
            assertTrue(fixture.canvas.isDisplayable)
        }
    }

    @Test
    fun refreshPreservesKeyboardFocusInAnOwnedComposeDialog() = withRecoveryFixtures { fixture ->
        val menuWindow = onEdtValue { SwingUtilities.getWindowAncestor(fixture.menu) as JDialog }
        onEdt { fixture.dialogShown.value = true }
        await("owned Compose dialog") { menuWindow.ownedWindows.count { it.isShowing } == 1 }
        val dialogWindow = onEdtValue { menuWindow.ownedWindows.single { it.isShowing } }
        onEdt {
            dialogWindow.toFront()
            dialogWindow.requestFocus()
        }
        await("owned dialog keyboard focus") {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow === dialogWindow
        }
        onEdt { fixture.overlay.refresh() }
        // Observe the immediate update and both delayed refreshes without reasserting dialog focus.
        repeat(12) {
            Thread.sleep(50)
            onEdt {
                assertSame(dialogWindow, KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow,
                    "Refreshing a visible menu must not steal keyboard input from its dialog")
                assertTrue(dialogWindow.isShowing)
            }
        }
        onEdt { fixture.dialogShown.value = false }
        await("closed owned dialog") { menuWindow.ownedWindows.none { it.isShowing } }
    }

    @Test
    fun delayedRecoveryDoesNotStealPendingKeyboardFocusFromOtherWindows() = withRecoveryFixtures { fixture ->
        for (siblingDialog in listOf(false, true)) {
            var focusWindow: Window? = null
            try {
                onEdt { fixture.switcher.switchTo(DisplayMode.Game) }
                await("Canvas keyboard focus before delayed switch") { fixture.canvas.isFocusOwner }
                onEdt {
                    fixture.host.blockSize = true
                    fixture.switcher.switchTo(DisplayMode.Menu)
                    fixture.overlay.refresh()
                    assertFalse(fixture.overlay.isMenuVisible)
                }
                // The menu still has its pending focus request when another test-owned window takes input.
                Thread.sleep(240)
                onEdt {
                    val target = JTextField("Other window keyboard target")
                    focusWindow = if (siblingDialog) JDialog(fixture.frame).apply {
                        title = "RWJS sibling dialog focus regression"
                        defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
                    } else JFrame("RWJS independent window focus regression").apply {
                        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    }
                    focusWindow!!.apply {
                        add(target)
                        setSize(360, 180)
                        setLocationRelativeTo(fixture.frame)
                        isVisible = true
                        toFront()
                        requestFocus()
                    }
                    target.requestFocusInWindow()
                }
                await("other window keyboard focus (sibling=$siblingDialog)") {
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow === focusWindow
                }
                onEdt { fixture.host.blockSize = false }
                await("menu recovery while another window has focus") { fixture.overlay.isMenuVisible }
                // Cover the delayed focus callback and both 120ms/320ms refresh follow-ups.
                repeat(12) {
                    Thread.sleep(50)
                    onEdt {
                        assertSame(focusWindow, KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow,
                            "A pending menu recovery must not take input from another window (sibling=$siblingDialog)")
                        assertTrue(fixture.overlay.isMenuVisible)
                    }
                }
            } finally {
                onEdt {
                    fixture.host.blockSize = false
                    focusWindow?.dispose()
                }
            }
        }
    }

    private fun withRecoveryFixtures(action: (Fixture) -> Unit) {
        requireNativeTransparency()
        withRenderApi("OPENGL") {
            for (native in listOf(false, true)) {
                val fixture = createFixture(native = native)
                try {
                    await("initial menu viewport (native=$native)") {
                        fixture.overlay.isMenuVisible && fixture.viewport.get() == fixture.host.size
                    }
                    onEdt { assertEquals(native, fixture.overlay.isNativeRendering, "Exercise the requested renderer") }
                    action(fixture)
                } finally {
                    fixture.dispose()
                }
            }
        }
    }

    private fun createFixture(native: Boolean = false, failNative: Boolean = false): Fixture {
        val previousLayerType = System.getProperty("compose.layers.type")
        System.setProperty("compose.layers.type", "WINDOW")
        return try {
            assertTrue(DesktopWindowLayers.install())
            createFixtureOnEdt(previousLayerType, native, failNative)
        } catch (failure: Throwable) {
            restoreLayerType(previousLayerType)
            throw failure
        }
    }

    private fun createFixtureOnEdt(previousLayerType: String?, native: Boolean, failNative: Boolean): Fixture = onEdtValue {
        logger = LoggerFactory.getLogger(DesktopMenuBackgroundWindowTest::class.java)
        val canvas = ColorCanvas()
        val layout = NativeCanvasCardLayout(canvas) { 1.0 }
        val host = LayoutProbeHost(layout).apply { add(canvas, DisplayMode.Game.cardName) }
        val clicks = AtomicInteger()
        val activeContent = AtomicInteger()
        val viewport = AtomicReference<Dimension?>()
        val dialogShown = mutableStateOf(false)
        val buttonCenter = AtomicReference<Point?>()
        val content: @Composable () -> Unit = {
                DisposableEffect(Unit) {
                    activeContent.incrementAndGet()
                    onDispose { activeContent.decrementAndGet() }
                }
                val density = LocalDensity.current.density
                MaterialTheme {
                    // Keep a minimally nonzero alpha so Windows routes blank-area input to the menu.
                    Box(Modifier.fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            viewport.set(Dimension((coordinates.size.width / density).roundToInt(),
                                (coordinates.size.height / density).roundToInt()))
                        }
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 1f / 255f))) {
                        Box(Modifier.align(Alignment.BottomEnd).size(18.dp)
                            .background(androidx.compose.ui.graphics.Color(EDGE_COLOR.rgb)))
                        Button(
                            onClick = { clicks.incrementAndGet() },
                            modifier = Modifier.offset(30.dp, 30.dp).size(160.dp, 45.dp)
                                .onGloballyPositioned { coordinates ->
                                    val center = coordinates.boundsInWindow().center
                                    buttonCenter.set(Point((center.x / density).roundToInt(), (center.y / density).roundToInt()))
                                },
                        ) { Text("Start match") }
                    }
                    if (dialogShown.value) Dialog(onDismissRequest = { dialogShown.value = false }) {
                        Box(Modifier.fillMaxSize()) { Text("Owned dialog keyboard target") }
                    }
                }
        }
        val menu = DesktopLauncherOverlay.createMenuPanel(liveBackground = true).apply {
            isOpaque = false
            setContent(content)
        }
        val frame = JFrame("RWJS live menu background regression").apply {
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            add(host)
            setSize(760, 520)
            setLocationRelativeTo(null)
            isVisible = true
        }
        val overlay = if (failNative) {
            DesktopLauncherOverlay(menu, host, frame, nativeContent = content,
                nativeWindowFactory = { throw IllegalStateException("Simulated native renderer initialization failure") })
        } else DesktopLauncherOverlay(menu, host, frame, nativeContent = if (native) content else null)
        val switcher = DisplaySwitcher(menu, canvas, host, layout, frame, launcherOverlay = overlay)
        overlay.showMenu()
        Fixture(frame, host, canvas, menu, buttonCenter, clicks, activeContent, viewport, dialogShown,
            overlay, switcher, previousLayerType)
    }

    /** Changes reported readiness without generating the events that normally wake the overlay. */
    private class LayoutProbeHost(layout: java.awt.LayoutManager) : JPanel(layout) {
        var blockSize = false
        var blockShowing = false
        override fun getWidth(): Int = if (blockSize) 0 else super.getWidth()
        override fun getHeight(): Int = if (blockSize) 0 else super.getHeight()
        override fun getSize(): Dimension = if (blockSize) Dimension() else super.getSize()
        override fun isShowing(): Boolean = !blockShowing && super.isShowing()
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
        val host: LayoutProbeHost,
        val canvas: ColorCanvas,
        val fallbackMenu: ComposePanel,
        val buttonCenter: AtomicReference<Point?>,
        val clicks: AtomicInteger,
        val activeContent: AtomicInteger,
        val viewport: AtomicReference<Dimension?>,
        val dialogShown: MutableState<Boolean>,
        val overlay: DesktopLauncherOverlay,
        val switcher: DisplaySwitcher,
        val previousLayerType: String?,
    ) {
        val menu: Component get() = overlay.contentComponent

        fun menuOwnsKeyboardFocus(): Boolean {
            val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val owner = manager.focusOwner ?: return false
            return manager.focusedWindow === SwingUtilities.getWindowAncestor(menu) &&
                (owner === menu || SwingUtilities.isDescendingFrom(owner, menu))
        }

        fun dispose() {
            try {
                SwingUtilities.invokeAndWait {
                    overlay.dispose()
                    frame.ownedWindows.forEach(Window::dispose)
                    frame.dispose()
                    assertEquals(0, activeContent.get(), "Disposing the owner must dispose its menu composition")
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

    private fun withRenderApi(api: String, action: () -> Unit) {
        val previous = System.getProperty("skiko.renderApi")
        try {
            System.setProperty("skiko.renderApi", api)
            action()
            assertEquals(api, System.getProperty("skiko.renderApi"), "Selecting a native menu must not change the global backend")
        } finally {
            if (previous == null) System.clearProperty("skiko.renderApi") else System.setProperty("skiko.renderApi", previous)
        }
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
            "Native menu pixel at $point: expected $expected, got $actual")
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
        val EDGE_COLOR = Color(224, 32, 192)
        val FIRST_COLOR = Color(32, 96, 144)
        val SECOND_COLOR = Color(64, 128, 48)

        fun restoreLayerType(previous: String?) {
            if (previous == null) System.clearProperty("compose.layers.type")
            else System.setProperty("compose.layers.type", previous)
        }
    }
}
