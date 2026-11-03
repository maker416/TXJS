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
        menuRequested = true
        update()
        SwingUtilities.invokeLater {
            if (isMenuVisible) {
                overlay.requestFocus()
                menuPanel.requestFocusInWindow()
            }
        }
    }

    fun showGame() {
        check(SwingUtilities.isEventDispatchThread())
        menuRequested = false
        update()
    }

    private fun hostVisible(): Boolean = displayHost.isShowing && window.isVisible &&
        (window !is Frame || window.extendedState and Frame.ICONIFIED == 0)

    private fun scheduleUpdate() {
        if (disposed || updatePending) return
        updatePending = true
        SwingUtilities.invokeLater {
            updatePending = false
            if (!disposed) update()
        }
    }

    private fun update() {
        if (disposed) return
        val visible = menuRequested && hostVisible() && displayHost.width > 0 && displayHost.height > 0
        if (visible) {
            val bounds = Rectangle(displayHost.locationOnScreen, displayHost.size)
            if (overlay.bounds != bounds) overlay.bounds = bounds
        }
        if (overlay.isVisible != visible) overlay.isVisible = visible
        if (publishedVisible != visible) {
            publishedVisible = visible
            onVisibilityChanged(visible)
        }
    }

    fun dispose() {
        check(SwingUtilities.isEventDispatchThread())
        if (disposed) return
        disposed = true
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
        @OptIn(ExperimentalComposeUiApi::class)
        fun createMenuPanel(liveBackground: Boolean): ComposePanel =
            if (liveBackground) ComposePanel(renderSettings = RenderSettings.SwingGraphics()) else ComposePanel()

        fun isSupported(window: Window): Boolean =
            window.graphicsConfiguration.device.isWindowTranslucencySupported(PERPIXEL_TRANSLUCENT)
    }
}
