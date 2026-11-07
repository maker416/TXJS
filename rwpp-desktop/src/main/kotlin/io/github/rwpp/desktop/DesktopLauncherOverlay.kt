/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Frame
import java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.SwingUtilities
import javax.swing.Timer

private val overlayLogger = LoggerFactory.getLogger("io.github.rwpp.desktop.LauncherOverlay")

/**
 * 菜单位于游戏窗口拥有的透明窗口中，战场继续直接绘制到原生 OpenGL Canvas。
 * AWT 的轻量组件混合会裁掉底下的重量级 Canvas，不能用同窗透明 JPanel 叠加。
 * 此处不复制游戏帧，也不搬动 Canvas，进出对局和全屏切换均保留 GL 上下文。
 */
class DesktopLauncherOverlay(
    private val menuPanel: Component,
    private val displayHost: JComponent,
    private val window: Window,
    private val onVisibilityChanged: (Boolean) -> Unit = {},
) {
    private val overlay = JDialog(window).apply {
        title = "RWJS 启动器"
        isUndecorated = true
        isAlwaysOnTop = false
        setAutoRequestFocus(false)
        defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
        background = Color(0, 0, 0, 0)
        rootPane.isOpaque = false
        (contentPane as JComponent).isOpaque = false
        contentPane.layout = BorderLayout()
        contentPane.add(menuPanel, BorderLayout.CENTER)
    }
    private var menuRequested = true
    private var updatePending = false
    private var disposed = false
    private var publishedVisible = false

    /** 全屏切换/窗口建立期间宿主布局可能瞬时无效（尺寸为 0 或未 showing），此时不能把菜单藏掉 */
    private var layoutRetries = 0
    private var paintPending = false
    private val layoutRetryTimer = Timer(LAYOUT_RETRY_MS) { event ->
        (event.source as Timer).stop()
        update(forcePaint = true)
    }.apply { isRepeats = false }
    private val reassertTimers = REASSERT_DELAYS_MS.map { delay ->
        Timer(delay) { update(forcePaint = true) }.apply { isRepeats = false }
    }

    val isMenuVisible: Boolean
        get() = !disposed && menuRequested && hostVisible() && overlay.isVisible

    private val componentListener = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent) = scheduleUpdate()
        override fun componentResized(e: ComponentEvent) = scheduleUpdate()
        override fun componentShown(e: ComponentEvent) = scheduleUpdate()
        override fun componentHidden(e: ComponentEvent) = update()
    }
    private val hierarchyListener = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) scheduleUpdate()
    }
    private val graphicsConfigurationListener = PropertyChangeListener { scheduleUpdate() }
    private val windowListener = object : WindowAdapter() {
        override fun windowIconified(e: WindowEvent) = update()
        override fun windowDeiconified(e: WindowEvent) = scheduleUpdate()
        override fun windowClosed(e: WindowEvent) = dispose()
        override fun windowGainedFocus(e: WindowEvent) {
            // F11、标题栏或任务栏激活的是宿主；菜单仍显示时把键盘还给菜单。
            // 延后检查实际焦点，避免从菜单原生弹窗手里抢走输入。
            SwingUtilities.invokeLater {
                if (isMenuVisible && KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow === window) {
                    overlay.requestFocus()
                    menuPanel.requestFocusInWindow()
                }
            }
        }
    }
    private val overlayListener = object : WindowAdapter() {
        override fun windowClosing(e: WindowEvent) {
            window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
        }
    }

    init {
        check(SwingUtilities.isEventDispatchThread())
        displayHost.addComponentListener(componentListener)
        displayHost.addHierarchyListener(hierarchyListener)
        window.addComponentListener(componentListener)
        window.addWindowListener(windowListener)
        window.addWindowFocusListener(windowListener)
        window.addPropertyChangeListener("graphicsConfiguration", graphicsConfigurationListener)
        overlay.addWindowListener(overlayListener)
        scheduleUpdate()
    }

    fun showMenu() {
        check(SwingUtilities.isEventDispatchThread())
        if (disposed) return
        menuRequested = true
        layoutRetries = 0
        overlayLogger.info("[UI] launcher overlay showMenu requested")
        update(forcePaint = true)
        SwingUtilities.invokeLater {
            if (isMenuVisible) {
                overlay.requestFocus()
                menuPanel.requestFocusInWindow()
            }
        }
    }

    fun showGame() {
        check(SwingUtilities.isEventDispatchThread())
        if (disposed) return
        menuRequested = false
        overlayLogger.info("[UI] launcher overlay showGame requested")
        update()
    }

    private fun hostVisible(): Boolean = window.isDisplayable && window.isVisible &&
        (window !is Frame || window.extendedState and Frame.ICONIFIED == 0)

    private fun scheduleUpdate() {
        if (disposed || updatePending) return
        layoutRetries = 0
        updatePending = true
        SwingUtilities.invokeLater {
            updatePending = false
            if (!disposed) update(forcePaint = true)
        }
    }

    private fun update(forcePaint: Boolean = false) {
        if (disposed) return
        // 只有明确原因才隐藏菜单：菜单未被请求、宿主窗口不可见/最小化/已销毁。
        // 过渡期不能按瞬时布局判定可见性：全屏切换会先缩回普通窗口，窗口首次建立时
        // 宿主也可能短暂尺寸为 0 或尚未 showing，而过渡结束后不保证再派发一次 AWT 事件。
        // 一旦此刻隐藏，菜单层就再也不会自己回来，只剩底层 Canvas 画面。
        if (!menuRequested || !hostVisible()) {
            cancelTimers()
            layoutRetries = 0
            publishVisible(false)
            return
        }
        if (!hostLayoutReady()) {
            scheduleLayoutRetry()
            return
        }
        layoutRetryTimer.stop()
        layoutRetries = 0
        val bounds = Rectangle(displayHost.locationOnScreen, displayHost.size)
        val boundsChanged = overlay.bounds != bounds
        if (boundsChanged) {
            overlayLogger.info("[UI] launcher overlay bounds {} -> {}", overlay.bounds, bounds)
            overlay.bounds = bounds
        }
        val wasVisible = overlay.isVisible
        publishVisible(true)
        if (boundsChanged || !wasVisible || forcePaint) {
            // invalidate 只是标记失效；必须验证整个透明窗口层级，才能把逻辑尺寸传到
            // ComposePanel.setBounds 和内部场景。不能用 Canvas 的物理像素尺寸替代它。
            menuPanel.invalidate()
            overlay.validate()
            if (boundsChanged) {
                overlayLogger.info("[UI] launcher overlay viewport (host={}x{}, menu={}x{}, valid={}, scale={})",
                    displayHost.width, displayHost.height, menuPanel.width, menuPanel.height, menuPanel.isValid,
                    menuPanel.graphicsConfiguration?.defaultTransform?.scaleX)
            }
            schedulePaint()
        }
    }

    private fun hostLayoutReady(): Boolean =
        displayHost.isShowing && displayHost.width > 0 && displayHost.height > 0

    private fun publishVisible(visible: Boolean) {
        if (overlay.isVisible != visible) {
            overlayLogger.info(
                "[UI] launcher overlay {} (host={}x{} showing={}, windowVisible={})",
                if (visible) "shown" else "hidden",
                displayHost.width, displayHost.height, displayHost.isShowing, window.isVisible,
            )
            overlay.isVisible = visible
        }
        if (publishedVisible != visible) {
            publishedVisible = visible
            onVisibilityChanged(visible)
        }
    }

    private fun scheduleLayoutRetry() {
        if (layoutRetryTimer.isRunning) return
        if (layoutRetries >= MAX_LAYOUT_RETRIES) {
            if (layoutRetries == MAX_LAYOUT_RETRIES) {
                overlayLogger.warn("[UI] launcher overlay layout retry exhausted (host={}x{} showing={}, menu={}x{})",
                    displayHost.width, displayHost.height, displayHost.isShowing, menuPanel.width, menuPanel.height)
                layoutRetries++
            }
            return
        }
        if (layoutRetries == 0) {
            overlayLogger.debug("[UI] launcher overlay waiting for layout (host={}x{} showing={})",
                displayHost.width, displayHost.height, displayHost.isShowing)
        }
        layoutRetries++
        layoutRetryTimer.restart()
    }

    private fun schedulePaint() {
        if (paintPending) return
        paintPending = true
        SwingUtilities.invokeLater {
            paintPending = false
            if (disposed || !isMenuVisible || !hostLayoutReady()) return@invokeLater
            // 排到当前窗口/布局修改之后；静止菜单也需补完整一帧，不依赖点击、波纹或动画。
            overlay.validate()
            menuPanel.repaint()
            overlay.repaint()
            overlayLogger.debug("[UI] launcher overlay repaint (host={}x{}, menu={}x{}, valid={}, scale={})",
                displayHost.width, displayHost.height, menuPanel.width, menuPanel.height, menuPanel.isValid,
                menuPanel.graphicsConfiguration?.defaultTransform?.scaleX)
        }
    }

    private fun cancelTimers() {
        layoutRetryTimer.stop()
        reassertTimers.forEach(Timer::stop)
    }

    /**
     * 原生窗口状态变化（全屏/窗口切换、启动完成、跨屏移动）后主动再断言。
     *
     * Win32 样式修改、窗口 bounds 赋值与 AWT 组件事件的到达顺序不受控，单次同步可能
     * 读到过渡中间态；这里立即同步一次，再在两个短延迟后各补一次，保证布局稳定后
     * 菜单层边界仍与宿主一致。
     */
    fun refresh() {
        check(SwingUtilities.isEventDispatchThread())
        if (disposed) return
        layoutRetries = 0
        update(forcePaint = true)
        if (menuRequested && hostVisible()) reassertTimers.forEach(Timer::restart)
    }

    fun dispose() {
        check(SwingUtilities.isEventDispatchThread())
        if (disposed) return
        disposed = true
        cancelTimers()
        displayHost.removeComponentListener(componentListener)
        displayHost.removeHierarchyListener(hierarchyListener)
        window.removeComponentListener(componentListener)
        window.removeWindowListener(windowListener)
        window.removeWindowFocusListener(windowListener)
        window.removePropertyChangeListener("graphicsConfiguration", graphicsConfigurationListener)
        overlay.removeWindowListener(overlayListener)
        overlay.dispose()
        publishedVisible = false
        onVisibilityChanged(false)
    }

    companion object {
        /** 过渡期布局重试上限与间隔（最长约 1.6s），覆盖全屏切换与首帧布局 */
        private const val MAX_LAYOUT_RETRIES = 20
        private const val LAYOUT_RETRY_MS = 80

        /** refresh 的补同步延迟：覆盖 Win32 样式修改与 AWT 布局的异步到达 */
        private val REASSERT_DELAYS_MS = intArrayOf(120, 320)

        @OptIn(ExperimentalComposeUiApi::class)
        fun createMenuPanel(liveBackground: Boolean): ComposePanel =
            if (liveBackground) ComposePanel(renderSettings = RenderSettings.SwingGraphics()) else ComposePanel()

        fun isSupported(window: Window): Boolean =
            window.graphicsConfiguration.device.isWindowTranslucencySupported(PERPIXEL_TRANSLUCENT)
    }
}
