/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dialog
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
    private val opaque: Boolean = false,
    initiallyVisible: Boolean = true,
    private val nativeContent: (@Composable () -> Unit)? = null,
    private val nativeWindowFactory: (Window) -> ComposeDialog = { owner ->
        ComposeDialog(owner, Dialog.ModalityType.MODELESS, owner.graphicsConfiguration)
    },
    private val onVisibilityChanged: (Boolean) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(DesktopLauncherOverlay::class.java)
    private var overlay = createSwingOverlay()
    private var nativeLayer: SkiaLayer? = null
    private var nativeAttempted = false

    private fun createSwingOverlay() = JDialog(window).apply {
        title = "RWJS 启动器"
        isUndecorated = true
        isAlwaysOnTop = false
        setAutoRequestFocus(false)
        defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
        background = if (opaque) Color.BLACK else Color(0, 0, 0, 0)
        rootPane.isOpaque = opaque
        (contentPane as JComponent).isOpaque = opaque
        contentPane.layout = BorderLayout()
        contentPane.add(menuPanel, BorderLayout.CENTER)
    }
    private var menuRequested = initiallyVisible
    private var updatePending = false
    private var disposed = false
    private var publishedVisible = false

    val isMenuVisible: Boolean
        get() = !disposed && menuRequested && hostVisible() && overlay.isVisible

    /** 原生菜单持有自己的 Compose 场景；未挂载的兼容面板不会重复组合。 */
    val contentComponent: Component
        get() = nativeLayer ?: menuPanel

    val isNativeRendering: Boolean
        get() = nativeLayer != null

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
                    focusMenu()
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
            if (isMenuVisible) focusMenu()
        }
    }

    fun focusMenu() {
        check(SwingUtilities.isEventDispatchThread())
        if (!isMenuVisible) return
        overlay.requestFocus()
        contentComponent.requestFocusInWindow()
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
            prepareNativeMenu(bounds)
            if (overlay.bounds != bounds) overlay.bounds = bounds
        }
        if (overlay.isVisible != visible) overlay.isVisible = visible
        if (publishedVisible != visible) {
            publishedVisible = visible
            onVisibilityChanged(visible)
        }
    }

    @OptIn(ExperimentalSkikoApi::class)
    private fun prepareNativeMenu(bounds: Rectangle) {
        if (nativeAttempted || opaque || nativeContent == null) return
        nativeAttempted = true
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        var candidate: ComposeDialog? = null
        try {
            val dialog = nativeWindowFactory(window)
            candidate = dialog
            check(!dialog.isDisplayable) { "Native menu must be configured before its peer is created" }
            dialog.title = "RWJS 启动器"
            dialog.isUndecorated = true
            dialog.isResizable = false
            dialog.isAlwaysOnTop = false
            dialog.setAutoRequestFocus(false)
            dialog.defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE

            // 先创建场景，再遍历公开 AWT 子组件；此时尚未创建原生设备。
            val requestedApi = dialog.renderApi
            if (requestedApi == GraphicsApi.SOFTWARE_COMPAT || requestedApi == GraphicsApi.SOFTWARE_FAST) {
                log.info("[UI] Software renderer selected; using the Swing launcher menu")
                dialog.dispose()
                return
            }
            val layer = checkNotNull(findSkiaLayer(dialog)) { "Native menu has no SkiaLayer" }
            layer.renderApi = GraphicsApi.DIRECT3D
            var selectingInitialRenderer = false
            layer.onStateChanged(SkiaLayer.PropertyKind.Renderer) {
                // init() 会重放原有回退队列；仅在首个设备工厂执行前选择一次 D3D。
                // 后续设备故障继续走 Skiko 的正常回退，不能重新强制选中已失败的后端。
                if (selectingInitialRenderer) {
                    selectingInitialRenderer = false
                    if (it.renderApi != GraphicsApi.DIRECT3D) it.renderApi = GraphicsApi.DIRECT3D
                }
            }
            dialog.isTransparent = true
            dialog.setContent { nativeContent.invoke() }
            selectingInitialRenderer = true
            try {
                dialog.pack()
            } finally {
                selectingInitialRenderer = false
            }
            check(dialog.renderApi == GraphicsApi.DIRECT3D) {
                "Native transparent menu requires Direct3D; initialized ${dialog.renderApi}"
            }
            dialog.bounds = bounds
            dialog.onRenderApiChanged {
                if (dialog.renderApi != GraphicsApi.DIRECT3D) SwingUtilities.invokeLater {
                    if (!disposed && overlay === dialog) {
                        useSwingFallback("Native menu renderer changed to ${dialog.renderApi}")
                    }
                }
            }
            overlay.removeWindowListener(overlayListener)
            overlay.dispose()
            overlay = dialog
            nativeLayer = layer
            dialog.addWindowListener(overlayListener)
            log.info("[UI] Launcher menu uses native transparent Direct3D rendering")
        } catch (failure: Exception) {
            candidate?.dispose()
            log.warn("[UI] Native launcher menu unavailable; using Swing rendering", failure)
        } catch (failure: LinkageError) {
            candidate?.dispose()
            log.warn("[UI] Native launcher renderer unavailable; using Swing rendering", failure)
        }
    }

    private fun useSwingFallback(reason: String) {
        check(SwingUtilities.isEventDispatchThread())
        log.warn("[UI] {}; using Swing rendering", reason)
        overlay.removeWindowListener(overlayListener)
        overlay.dispose()
        nativeLayer = null
        overlay = createSwingOverlay()
        overlay.addWindowListener(overlayListener)
        update()
        focusMenu()
    }

    private fun findSkiaLayer(component: Component): SkiaLayer? {
        if (component is SkiaLayer) return component
        if (component is Container) component.components.forEach { child ->
            findSkiaLayer(child)?.let { return it }
        }
        return null
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
