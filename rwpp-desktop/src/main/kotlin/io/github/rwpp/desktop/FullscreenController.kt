/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import io.github.rwpp.logger
import java.awt.Frame
import java.awt.Rectangle
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * 桌面端全屏/窗口模式的运行时切换。
 *
 * 原版实现只能在启动时通过 `JFrame.isUndecorated` 决定全屏，改动后必须重启进程
 * （`isUndecorated` 不允许在窗口可见后修改，而 `dispose()` 重建窗口会销毁
 * LWJGL 的 OpenGL 子窗口，游戏画面随之丢失）。
 *
 * 这里改为在窗口存活期间直接修改 Win32 窗口样式（去/加 `WS_CAPTION` 等装饰样式）：
 * 窗口句柄与 GL 上下文保持不变，因此对局中切换也不会打断游戏。
 *
 * - 全屏 = 无边框窗口铺满当前显示器（无边框窗口化：切换快、Alt-Tab 友好、
 *   不触发显示模式切换，比独占全屏更稳定）
 * - 窗口 = 恢复标题栏/边框，并还原进入全屏前的窗口尺寸或最大化状态
 *
 * 非 Windows 平台回退到 AWT 全屏独占模式（`GraphicsDevice.setFullScreenWindow`）。
 */
object FullscreenController {
    private const val GWL_STYLE = -16
    private const val WS_MAXIMIZEBOX = 0x00010000L
    private const val WS_MINIMIZEBOX = 0x00020000L
    private const val WS_THICKFRAME = 0x00040000L
    private const val WS_SYSMENU = 0x00080000L
    private const val WS_CAPTION = 0x00C00000L
    private const val DECORATED_STYLES =
        WS_CAPTION or WS_THICKFRAME or WS_SYSMENU or WS_MINIMIZEBOX or WS_MAXIMIZEBOX

    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020

    private val HWND_TOPMOST = Pointer(-1L)
    private val HWND_NOTOPMOST = Pointer(-2L)

    @Volatile
    private var inFullscreen: Boolean = false

    /** 进入全屏前的普通窗口位置尺寸（进入时为最大化状态则为 null，退出时恢复最大化） */
    private var savedBounds: Rectangle? = null

    /** 串行化切换过程，避免快速连点产生中间态 */
    private var applying = false

    val isFullscreen: Boolean get() = inFullscreen

    val isWindowsPlatform: Boolean
        get() = System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

    /** 记录启动时的全屏状态（启动路径如何进入全屏由 Main.kt 决定）。 */
    fun init(initialFullscreen: Boolean) {
        inFullscreen = initialFullscreen
    }

    /**
     * 启动期进入全屏：在窗口已 `pack()`（原生句柄已创建）但尚未可见时调用，
     * 避免先闪现带标题栏的窗口再进入全屏。
     */
    fun enterFullscreenAtStartup(window: JFrame) {
        if (!isWindowsPlatform) return
        inFullscreen = runCatching { applyFullscreenWin32(window, true) }
            .onFailure { logger.error("[Fullscreen] 启动进入全屏失败，将以窗口模式启动", it) }
            .getOrDefault(false)
    }

    /**
     * 切换全屏/窗口模式。可在任意线程调用，实际切换在 EDT 上执行。
     * 主窗口尚未创建（注入配置模式）时为空操作，配置会在下次启动时生效。
     */
    fun setFullscreen(fullscreen: Boolean) {
        if (!isMainWindowInitialized) return
        SwingUtilities.invokeLater {
            if (applying || inFullscreen == fullscreen) return@invokeLater
            applying = true
            try {
                val applied = runCatching {
                    if (isWindowsPlatform) applyFullscreenWin32(mainJFrame, fullscreen)
                    else applyFullscreenAwt(mainJFrame, fullscreen)
                }.onFailure { logger.error("[Fullscreen] 切换全屏状态失败 (target=$fullscreen)", it) }
                    .getOrDefault(false)
                if (applied) {
                    inFullscreen = fullscreen
                    logger.info("[Fullscreen] 已切换为${if (fullscreen) "全屏" else "窗口"}模式")
                }
            } finally {
                applying = false
            }
        }
    }

    // ---------------- Win32（保句柄，不销毁 GL 上下文） ----------------

    private fun applyFullscreenWin32(window: JFrame, fullscreen: Boolean): Boolean {
        val hwnd = Native.getWindowPointer(window) ?: return false
        val style = getWindowStyle(hwnd)

        if (fullscreen) {
            // 仅普通状态才有值得还原的窗口位置；最大化/最小化状态退出时统一恢复最大化
            savedBounds = if (window.extendedState == Frame.NORMAL) window.bounds else null

            // 先退出最大化（此时样式仍完整），再去除装饰样式
            window.extendedState = Frame.NORMAL
            setWindowStyle(hwnd, style and DECORATED_STYLES.inv())
            frameChanged(hwnd)
            // 使用 AWT 逻辑坐标铺满窗口当前所在的整个显示器（含任务栏区域），DPI 缩放安全
            window.bounds = window.graphicsConfiguration.bounds
            // 顶置一次再取消，把窗口抬到任务栏之上（保持前台时任务栏自动让位）
            User32.INSTANCE.SetWindowPos(
                hwnd, HWND_TOPMOST, 0, 0, 0, 0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE
            )
            User32.INSTANCE.SetWindowPos(
                hwnd, HWND_NOTOPMOST, 0, 0, 0, 0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE
            )
        } else {
            setWindowStyle(hwnd, style or DECORATED_STYLES)
            frameChanged(hwnd)
            val bounds = savedBounds
            savedBounds = null
            if (bounds != null) {
                window.extendedState = Frame.NORMAL
                window.bounds = bounds
            } else {
                window.extendedState = Frame.MAXIMIZED_BOTH
            }
        }

        window.invalidate()
        window.validate()
        window.repaint()
        window.toFront()
        window.requestFocus()
        syncGameCanvasSizeToNative()
        return true
    }

    // ---------------- 非 Windows 回退（AWT 全屏独占） ----------------

    private fun applyFullscreenAwt(window: JFrame, fullscreen: Boolean): Boolean {
        val device = runCatching { window.graphicsConfiguration?.device }.getOrNull() ?: return false
        if (!device.isFullScreenSupported) return false
        device.fullScreenWindow = if (fullscreen) window else null
        window.toFront()
        window.requestFocus()
        return true
    }

    // ---------------- user32 最小 JNA 绑定 ----------------

    private fun getWindowStyle(hwnd: Pointer): Long =
        Pointer.nativeValue(User32.INSTANCE.GetWindowLongPtrW(hwnd, GWL_STYLE))

    private fun setWindowStyle(hwnd: Pointer, style: Long) {
        User32.INSTANCE.SetWindowLongPtrW(hwnd, GWL_STYLE, Pointer(style))
    }

    /** 样式修改后通知系统重算非客户区（标题栏/边框随之出现或消失）。
     *  不带 SWP_SHOWWINDOW：启动期调用时窗口尚未可见，不能提前显示。 */
    private fun frameChanged(hwnd: Pointer) {
        User32.INSTANCE.SetWindowPos(
            hwnd, null, 0, 0, 0, 0,
            SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED
        )
    }

    private interface User32 : StdCallLibrary {
        /** LONG_PTR 与指针同宽（x64 下 64 位），这里统一用 Pointer 承载避免 LLP64 宽度问题 */
        fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Pointer
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Pointer): Pointer
        fun SetWindowPos(hwnd: Pointer, insertAfter: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean

        companion object {
            // lazy：仅在 Windows 上实际调用时才加载 user32
            val INSTANCE: User32 by lazy { Native.load("user32", User32::class.java) }
        }
    }
}
