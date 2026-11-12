/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import org.junit.Assume.assumeTrue
import java.awt.Canvas
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Robot
import java.awt.Window
import java.awt.event.InputEvent
import java.lang.reflect.InvocationTargetException
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 只用于测试初次激活；后续弹窗的焦点交接必须由生产代码自行完成。 */
internal fun activateNativeMenuForTest(
    owner: JFrame,
    menu: Window,
    robot: Robot,
    requestMenuFocus: () -> Unit,
) {
    check(!SwingUtilities.isEventDispatchThread())
    val native = NativeMenuActivationWindows.INSTANCE
    val menuHandle = activationOnEdt {
        assertTrue(owner.isShowing && menu.isShowing, "The test windows must be visible before activation")
        Native.getWindowPointer(menu)
    }
    fun requestFocusIfNeeded() = activationOnEdt {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val focused = manager.focusOwner
        if (manager.focusedWindow !== menu || focused !is Canvas ||
            SwingUtilities.getWindowAncestor(focused) !== menu) requestMenuFocus()
    }
    activationOnEdt {
        menu.toFront()
        native.SetForegroundWindow(menuHandle)
    }
    if (awaitActivationForeground(menuHandle, 400_000_000L)) {
        requestFocusIfNeeded()
        return
    }

    // Windows 可能拒绝后台进程的激活请求。仅在初始 setup 临时露出自有标题栏，
    // 点击前再用真实 Win32 光标坐标核验目标，不能给遮挡它的外部应用发送点击。
    val previousTopmost = activationOnEdt {
        fun ownedTree(window: Window): List<Window> =
            listOf(window) + window.ownedWindows.flatMap(::ownedTree)
        ownedTree(owner).map { it to it.isAlwaysOnTop }
    }
    try {
        activationOnEdt {
            owner.isAlwaysOnTop = true
            owner.toFront()
        }
        robot.waitForIdle()
        val caption = activationOnEdt {
            assertTrue(owner.isShowing && owner.isDisplayable)
            assertTrue(owner.insets.top > 4 && owner.width > 160,
                "The activation fixture must expose a decorated owner title bar")
            val awtCaptionPoint = Point(owner.locationOnScreen).apply {
                translate(owner.width / 2, owner.insets.top / 2)
            }
            val ownerHandle = Native.getWindowPointer(owner)
            val nativeBounds = NativeMenuActivationRect()
            assertTrue(native.GetWindowRect(ownerHandle, nativeBounds), "Could not inspect the owner HWND bounds")
            val clientOrigin = NativeMenuActivationPoint()
            assertTrue(native.ClientToScreen(ownerHandle, clientOrigin), "Could not inspect the owner client origin")
            val nativeCaptionHeight = clientOrigin.y - nativeBounds.top
            assertTrue(nativeCaptionHeight > 4,
                "The native owner must expose a title bar: rect=$nativeBounds, clientOrigin=$clientOrigin")
            val physical = Point(
                nativeBounds.left + (nativeBounds.right - nativeBounds.left) / 2,
                nativeBounds.top + nativeCaptionHeight / 2,
            )
            val configuration = owner.graphicsConfiguration
            val screen = configuration.bounds
            val transform = configuration.defaultTransform
            // WRobotPeer.toDeviceSpaceAbs 使用相对于显示器原点的缩放，而不是全局坐标缩放。
            // 从 HWND 的真实物理标题栏反算，避免强制 Java uiScale 下的 AWT insets 偏差。
            val logical = Point(
                screen.x + ((physical.x - screen.x) / transform.scaleX).roundToInt(),
                screen.y + ((physical.y - screen.y) / transform.scaleY).roundToInt(),
            )
            NativeMenuActivationCaption(logical, physical,
                "awtCaptionPoint=$awtCaptionPoint, ownerBounds=${owner.bounds}, " +
                    "ownerLocationOnScreen=${owner.locationOnScreen}, ownerInsets=${owner.insets}, " +
                    "screenBounds=$screen, transform=$transform, uiScale=${System.getProperty("sun.java2d.uiScale")}, " +
                    "nativeOwnerBounds=$nativeBounds, nativeClientOrigin=$clientOrigin, " +
                    "robotLogicalPoint=$logical, requestedPhysicalPoint=$physical")
        }
        println("[NativeMenuActivation] ${caption.diagnostics}")
        robot.mouseMove(caption.logical.x, caption.logical.y)
        robot.waitForIdle()
        activationOnEdt {
            val cursor = NativeMenuActivationPoint()
            assertTrue(native.GetCursorPos(cursor), "Could not inspect the cursor before a test-window click")
            val hit = native.WindowFromPoint(NativeMenuActivationPoint.ByValue(cursor.x, cursor.y))
            val root = native.GetAncestor(hit, 2) // GA_ROOT; GA_ROOTOWNER would also accept a covering dialog.
            val ownerHandle = Native.getWindowPointer(owner)
            val diagnostics = "${caption.diagnostics}, actualCursor=$cursor, hit=$hit, root=$root, expectedOwner=$ownerHandle"
            println("[NativeMenuActivation] $diagnostics")
            if (root != ownerHandle) {
                val coveringTestWindow = Window.getWindows().firstOrNull {
                    it.isDisplayable && Native.getWindowPointer(it) == root
                }
                assertTrue(coveringTestWindow == null,
                    "Another test window covered the activation title bar: $coveringTestWindow; $diagnostics")
                assumeTrue("An external window covers the test title bar; no mouse button input was sent; $diagnostics", false)
            }
        }
        try {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        } finally {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
        robot.waitForIdle()
    } finally {
        activationOnEdt {
            // Parent setters can affect owned windows, so restore in parent-before-child order.
            previousTopmost.forEach { (window, topmost) ->
                if (window.isDisplayable && window.isAlwaysOnTop != topmost) window.isAlwaysOnTop = topmost
            }
            previousTopmost.forEach { (window, topmost) ->
                if (window.isDisplayable) assertEquals(topmost, window.isAlwaysOnTop,
                    "Activation must restore the test window's original topmost state")
            }
        }
    }
    requestFocusIfNeeded()
    if (!awaitActivationForeground(menuHandle, 3_000_000_000L)) {
        activationOnEdt {
            val foreground = native.GetForegroundWindow()
            val foregroundTestWindow = Window.getWindows().firstOrNull {
                it.isDisplayable && Native.getWindowPointer(it) == foreground
            }
            assertTrue(foregroundTestWindow == null,
                "The test activated its own owner but did not hand foreground to its menu: $foregroundTestWindow")
            assumeTrue("Windows did not activate the test menu; no keyboard input was sent", false)
        }
    }
}

private fun awaitActivationForeground(window: Pointer?, timeoutNanos: Long): Boolean {
    val until = System.nanoTime() + timeoutNanos
    while (System.nanoTime() < until) {
        if (NativeMenuActivationWindows.INSTANCE.GetForegroundWindow() == window) return true
        Thread.sleep(20)
    }
    return false
}

private fun <T> activationOnEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    var result: T? = null
    try {
        SwingUtilities.invokeAndWait { result = action() }
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

internal open class NativeMenuActivationPoint : Structure() {
    @JvmField var x: Int = 0
    @JvmField var y: Int = 0
    override fun getFieldOrder(): List<String> = listOf("x", "y")
    override fun toString(): String = "($x,$y)"

    class ByValue(x: Int = 0, y: Int = 0) : NativeMenuActivationPoint(), Structure.ByValue {
        init {
            this.x = x
            this.y = y
        }
    }
}

internal class NativeMenuActivationRect : Structure() {
    @JvmField var left: Int = 0
    @JvmField var top: Int = 0
    @JvmField var right: Int = 0
    @JvmField var bottom: Int = 0
    override fun getFieldOrder(): List<String> = listOf("left", "top", "right", "bottom")
    override fun toString(): String = "[$left,$top,$right,$bottom]"
}

private data class NativeMenuActivationCaption(val logical: Point, val physical: Point, val diagnostics: String)

private interface NativeMenuActivationWindows : StdCallLibrary {
    fun GetForegroundWindow(): Pointer?
    fun SetForegroundWindow(window: Pointer?): Boolean
    fun GetWindowRect(window: Pointer?, rect: NativeMenuActivationRect): Boolean
    fun ClientToScreen(window: Pointer?, point: NativeMenuActivationPoint): Boolean
    fun GetCursorPos(point: NativeMenuActivationPoint): Boolean
    fun WindowFromPoint(point: NativeMenuActivationPoint.ByValue): Pointer?
    fun GetAncestor(window: Pointer?, flags: Int): Pointer?

    companion object {
        val INSTANCE: NativeMenuActivationWindows by lazy {
            Native.load("user32", NativeMenuActivationWindows::class.java)
        }
    }
}
