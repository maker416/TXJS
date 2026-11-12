/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.awt.Canvas
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 真实原生菜单、WINDOW 弹层与 Robot 键盘输入，不依赖 Koin 或游戏引擎。 */
class DesktopNativeMenuInputWindowTest {
    @Test
    fun ownedDialogAndPopupKeepTheirViewportAndRestoreNativeMenuKeyboardInput() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        assumeFalse(GraphicsEnvironment.isHeadless())
        assumeTrue(GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT))

        val previousLayers = System.getProperty("compose.layers.type")
        val previousApi = System.getProperty("skiko.renderApi")
        val ownerRef = AtomicReference<JFrame?>()
        val overlayRef = AtomicReference<DesktopLauncherOverlay?>()
        val canvasRef = AtomicReference<Canvas>()
        val canvasColor = AtomicReference(Color(32, 96, 144))
        val dialogShown = mutableStateOf(false)
        val popupShown = mutableStateOf(false)
        val rootLetters = AtomicInteger()
        val rootEscapes = AtomicInteger()
        val dialogLetters = AtomicInteger()
        val popupLetters = AtomicInteger()
        val rootHasFocus = AtomicBoolean()
        val dialogHasFocus = AtomicBoolean()
        val popupHasFocus = AtomicBoolean()
        val dialogButton = AtomicReference<Point?>()
        val popupButton = AtomicReference<Point?>()
        try {
            System.setProperty("compose.layers.type", "WINDOW")
            // 旧 GPU 偏好仍需得到局部 Direct3D 菜单，不能依靠修改全局属性。
            System.setProperty("skiko.renderApi", "OPENGL")
            assertTrue(DesktopWindowLayers.install())
            lateinit var host: JPanel
            onEdt {
                val canvas = object : Canvas() {
                    override fun paint(graphics: Graphics) {
                        graphics.color = canvasColor.get()
                        graphics.fillRect(0, 0, width, height)
                    }
                    override fun update(graphics: Graphics) = paint(graphics)
                }
                canvasRef.set(canvas)
                host = JPanel(NativeCanvasCardLayout(canvas) { 1.0 }).apply {
                    add(canvas, DisplayMode.Game.cardName)
                }
                val content: @Composable () -> Unit = {
                    val rootFocus = remember { FocusRequester() }
                    val density = LocalDensity.current.density
                    val windowFocused = LocalWindowInfo.current.isWindowFocused
                    MaterialTheme {
                        Column(
                            Modifier.fillMaxSize()
                                // A transparency probe must not add its own tinted background:
                                // the default Dialog scrim composites into the host's painted alpha.
                                .padding(24.dp)
                                .focusRequester(rootFocus)
                                .onFocusChanged { rootHasFocus.set(it.hasFocus) }
                                .onPreviewKeyEvent {
                                    if (it.type != KeyEventType.KeyDown) false
                                    else when (it.key) {
                                        Key.F6 -> { rootLetters.incrementAndGet(); true }
                                        Key.Escape -> { rootEscapes.incrementAndGet(); true }
                                        else -> false
                                    }
                                }.focusable(),
                        ) {
                            Button(
                                onClick = { dialogShown.value = true },
                                modifier = Modifier.onGloballyPositioned {
                                    val center = it.boundsInWindow().center
                                    dialogButton.set(Point((center.x / density).roundToInt(), (center.y / density).roundToInt()))
                                },
                            ) { Text("Open dialog") }
                            Button(
                                onClick = { popupShown.value = true },
                                modifier = Modifier.onGloballyPositioned {
                                    val center = it.boundsInWindow().center
                                    popupButton.set(Point((center.x / density).roundToInt(), (center.y / density).roundToInt()))
                                },
                            ) { Text("Open popup") }
                        }
                        // pack() 可能先组合内容；窗口真正获得焦点后才申请 Compose 节点焦点。
                        LaunchedEffect(windowFocused) { if (windowFocused) rootFocus.requestFocus() }
                        if (dialogShown.value) Dialog(onDismissRequest = { dialogShown.value = false }) {
                            LayerKeyboardContent(Key.F7, dialogLetters, dialogHasFocus, "Dialog keyboard target")
                        }
                        if (popupShown.value) Popup(
                            onDismissRequest = { popupShown.value = false },
                            properties = PopupProperties(focusable = true),
                        ) {
                            LayerKeyboardContent(Key.F8, popupLetters, popupHasFocus, "Popup keyboard target")
                        }
                    }
                }
                val fallback = DesktopLauncherOverlay.createMenuPanel(liveBackground = true).apply {
                    isOpaque = false
                    setContent(content)
                }
                val owner = JFrame("RWJS native menu keyboard regression").apply {
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    add(host)
                    setSize(780, 540)
                    setLocationRelativeTo(null)
                }
                ownerRef.set(owner)
                owner.isVisible = true
                val overlay = DesktopLauncherOverlay(fallback, host, owner, nativeContent = content)
                overlayRef.set(overlay)
                overlay.showMenu()
            }
            val overlay = overlayRef.get()!!
            await("native menu layout and focus") {
                overlay.isNativeRendering && overlay.contentComponent.isShowing &&
                    dialogButton.get() != null && popupButton.get() != null
            }
            val menuWindow = onEdtValue { SwingUtilities.getWindowAncestor(overlay.contentComponent) as JDialog }
            onEdt {
                assertEquals("OPENGL", System.getProperty("skiko.renderApi"))
                assertSame(ownerRef.get(), menuWindow.owner)
                assertFalse(menuWindow.isAlwaysOnTop)
                menuWindow.toFront()
                overlay.focusMenu()
            }
            val robot = Robot().apply { autoDelay = 20 }
            activateNativeMenuForTest(ownerRef.get()!!, menuWindow, robot, overlay::focusMenu)
            awaitKeyboardFocus("native menu keyboard focus", menuWindow) {
                overlay.contentComponent.isFocusOwner && rootHasFocus.get()
            }
            // 功能键不经过中文 IME 的字母转换，仍必须完整经过 AWT → Compose 分发。
            press(robot, KeyEvent.VK_F6, menuWindow)
            await("initial root key callback") { rootLetters.get() == 1 }
            onEdt { repeat(3) { overlay.focusMenu() } }
            repeat(10) {
                Thread.sleep(20)
                onEdt {
                    assertTrue(overlay.contentComponent.isFocusOwner && rootHasFocus.get(),
                        "Repeated menu focus requests must preserve the active Compose keyboard target")
                }
            }
            verifyBackgroundVisible(robot, overlay.contentComponent, menuWindow, canvasColor.get())

            click(robot, overlay.contentComponent, dialogButton)
            await("owned native dialog") { visibleLayers(menuWindow).size == 1 }
            val dialogWindow = onEdtValue { visibleLayers(menuWindow).single() }
            await("dialog viewport") {
                dialogWindow.bounds == Rectangle(overlay.contentComponent.locationOnScreen, overlay.contentComponent.size)
            }
            verifyLayer(menuWindow, overlay.contentComponent, dialogWindow)
            verifyBackgroundVisible(robot, overlay.contentComponent, dialogWindow, canvasColor.get())
            onEdt {
                canvasColor.set(Color(72, 128, 192))
                canvasRef.get().repaint()
            }
            verifyBackgroundVisible(robot, overlay.contentComponent, dialogWindow, canvasColor.get())
            awaitKeyboardFocus("dialog keyboard focus", dialogWindow) {
                dialogHasFocus.get()
            }
            press(robot, KeyEvent.VK_F7, dialogWindow)
            await("dialog receives key") { dialogLetters.get() == 1 }
            onEdt { ownerRef.get()!!.setSize(860, 610) }
            await("dialog follows resized native menu") {
                menuWindow.bounds == Rectangle(host.locationOnScreen, host.size) &&
                    dialogWindow.bounds == Rectangle(overlay.contentComponent.locationOnScreen, overlay.contentComponent.size)
            }
            verifyStableLayer(menuWindow, overlay.contentComponent, dialogWindow)
            press(robot, KeyEvent.VK_ESCAPE, dialogWindow)
            await("Escape closes dialog") { !dialogShown.value && visibleLayers(menuWindow).isEmpty() }
            awaitKeyboardFocus("menu focus restored after dialog", menuWindow) {
                overlay.contentComponent.isFocusOwner && rootHasFocus.get()
            }
            press(robot, KeyEvent.VK_F6, menuWindow)
            await("root receives keyboard after dialog") { rootLetters.get() == 2 }
            assertEquals(0, rootEscapes.get(), "A dialog dismissal must consume its Escape key")

            click(robot, overlay.contentComponent, popupButton)
            await("owned native popup") { visibleLayers(menuWindow).size == 1 }
            val popupWindow = onEdtValue { visibleLayers(menuWindow).single() }
            await("popup viewport") {
                popupWindow.bounds == Rectangle(overlay.contentComponent.locationOnScreen, overlay.contentComponent.size)
            }
            verifyStableLayer(menuWindow, overlay.contentComponent, popupWindow)
            verifyBackgroundVisible(robot, overlay.contentComponent, popupWindow, canvasColor.get())
            onEdt {
                canvasColor.set(Color(184, 80, 40))
                canvasRef.get().repaint()
            }
            verifyBackgroundVisible(robot, overlay.contentComponent, popupWindow, canvasColor.get())
            awaitKeyboardFocus("popup keyboard focus", popupWindow) {
                popupHasFocus.get()
            }
            press(robot, KeyEvent.VK_F8, popupWindow)
            await("popup receives key") { popupLetters.get() == 1 }
            press(robot, KeyEvent.VK_ESCAPE, popupWindow)
            await("Escape closes popup") { !popupShown.value && visibleLayers(menuWindow).isEmpty() }
            awaitKeyboardFocus("menu focus restored after popup", menuWindow) {
                overlay.contentComponent.isFocusOwner && rootHasFocus.get()
            }
            press(robot, KeyEvent.VK_F6, menuWindow)
            await("root receives keyboard after popup") { rootLetters.get() == 3 }
            assertEquals(0, rootEscapes.get(), "A popup dismissal must consume its Escape key")
            press(robot, KeyEvent.VK_ESCAPE, menuWindow)
            await("root Escape callback restored") { rootEscapes.get() == 1 }
            onEdt { assertEquals("OPENGL", System.getProperty("skiko.renderApi")) }
            println("Native menu keyboard chain completed: root=${rootLetters.get()}, " +
                "dialog=${dialogLetters.get()}, popup=${popupLetters.get()}, rootEscape=${rootEscapes.get()}")
        } finally {
            try {
                onEdt {
                    overlayRef.get()?.dispose()
                    ownerRef.get()?.let(::disposeTree)
                }
            } finally {
                restoreProperty("compose.layers.type", previousLayers)
                restoreProperty("skiko.renderApi", previousApi)
            }
        }
    }

    @Composable
    private fun LayerKeyboardContent(key: Key, count: AtomicInteger, focused: AtomicBoolean, label: String) {
        val focus = remember { FocusRequester() }
        Surface {
            Box(Modifier.padding(24.dp).focusRequester(focus).onFocusChanged { focused.set(it.hasFocus) }.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == key) {
                    count.incrementAndGet()
                    true
                } else false
            }.focusable()) { Text(label) }
        }
        // WINDOW layers have their own WindowInfo, which Compose 1.10.1 does not
        // update with native focus events. Request the attached target directly;
        // the assertions below still require actual AWT, Win32 and Robot input.
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    private fun verifyStableLayer(menu: JDialog, content: Component, layer: Window) {
        val until = System.nanoTime() + 250_000_000L
        do {
            verifyLayer(menu, content, layer)
            Thread.sleep(15)
        } while (System.nanoTime() < until)
    }

    private fun verifyLayer(menu: JDialog, content: Component, layer: Window) = onEdt {
        assertSame(menu, layer.owner, "WINDOW layers must belong to the native menu")
        assertFalse(layer.isAlwaysOnTop, "Menu popups must not cover unrelated applications")
        assertEquals(Rectangle(content.locationOnScreen, content.size), layer.bounds,
            "Focusable WINDOW layers must retain the native menu viewport")
    }

    private fun click(robot: Robot, content: Component, center: AtomicReference<Point?>) {
        val point = onEdtValue {
            requireForeground(SwingUtilities.getWindowAncestor(content))
            content.locationOnScreen.apply { translate(center.get()!!.x, center.get()!!.y) }
        }
        robot.mouseMove(point.x, point.y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    private fun press(robot: Robot, key: Int, window: Window) {
        onEdt { requireForeground(window) }
        robot.keyPress(key)
        robot.keyRelease(key)
    }

    private fun verifyBackgroundVisible(robot: Robot, content: Component, foreground: Window, expected: Color) {
        val sample = onEdtValue {
            requireForeground(foreground)
            content.locationOnScreen.apply { translate(content.width - 24, content.height - 24) }
        }
        val until = System.nanoTime() + 3_000_000_000L
        var actual: Color
        do {
            onEdt { requireForeground(foreground) }
            actual = robot.getPixelColor(sample.x, sample.y)
            if (kotlin.math.abs(expected.red - actual.red) <= 2 &&
                kotlin.math.abs(expected.green - actual.green) <= 2 &&
                kotlin.math.abs(expected.blue - actual.blue) <= 2) return
            Thread.sleep(15)
        } while (System.nanoTime() < until)
        assertEquals(expected, actual, "Native menu and its WINDOW layers must reveal the original Canvas")
    }

    private fun awaitKeyboardFocus(message: String, window: Window, composeFocused: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) {
            val ready = onEdtValue {
                val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                val owner = manager.focusOwner
                manager.focusedWindow === window && owner is Canvas &&
                    SwingUtilities.getWindowAncestor(owner) === window && composeFocused()
            }
            // AWT 焦点事件可能先于 Win32 前台切换；两者均就绪后才能发送 Robot 输入。
            if (ready && ForegroundWindows.INSTANCE.GetForegroundWindow() == Native.getWindowPointer(window)) {
                return
            }
            Thread.sleep(15)
        }
        // 外部应用占据前台时跳过 Robot 场景，不能把测试输入发给浏览器或用户正在操作的窗口。
        onEdt { requireForeground(window, message) }
        val focusState = onEdtValue {
            val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            "focusedWindow=${manager.focusedWindow}, focusOwner=${manager.focusOwner}, composeFocused=${composeFocused()}"
        }
        assertTrue(false, "$message: $focusState")
    }

    private fun requireForeground(window: Window, stage: String = "Robot input") {
        val foreground = ForegroundWindows.INSTANCE.GetForegroundWindow()
        val testWindow = Window.getWindows().firstOrNull { it.isDisplayable && Native.getWindowPointer(it) == foreground }
        assertTrue(testWindow == null || testWindow === window,
            "$stage focused another test window: expected=$window, actual=$testWindow")
        assumeTrue("$stage requires its native window in the foreground; foreground test window=$testWindow; no external input was sent",
            foreground == Native.getWindowPointer(window))
    }

    private interface ForegroundWindows : StdCallLibrary {
        fun GetForegroundWindow(): Pointer?

        companion object {
            val INSTANCE: ForegroundWindows by lazy { Native.load("user32", ForegroundWindows::class.java) }
        }
    }

    private fun visibleLayers(menu: Window): List<Window> = menu.ownedWindows.filter { it.isShowing }

    private fun disposeTree(window: Window) {
        window.ownedWindows.forEach(::disposeTree)
        window.dispose()
    }

    private fun restoreProperty(name: String, value: String?) {
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) {
            if (onEdtValue(condition)) return
            Thread.sleep(15)
        }
        assertTrue(onEdtValue(condition), message)
    }

    private fun onEdt(block: () -> Unit) {
        try {
            SwingUtilities.invokeAndWait(block)
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }
    }

    private fun <T> onEdtValue(block: () -> T): T {
        val result = AtomicReference<T>()
        onEdt { result.set(block()) }
        return result.get()
    }
}
