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
import javax.swing.Timer

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
    private var layoutRetries = 0
    private var paintPending = false
    private var focusRequested = false
    private var focusUpdatePending = false
    private val layoutRetryTimer = Timer(LAYOUT_RETRY_MS) { event ->
        (event.source as Timer).stop()
        update(forcePaint = true)
    }.apply {
        isRepeats = false
    }
    private val reassertTimers = REASSERT_DELAYS_MS.map { delay ->
        Timer(delay) { update(forcePaint = true) }.apply { isRepeats = false }
    }

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
            // 宿主激活时布局也可能尚未就绪；保留请求，待菜单真正显示后再交还键盘。
            if (menuRequested && hostVisible()) {
                focusRequested = true
                update()
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
        focusRequested = true
        layoutRetries = 0
        update(forcePaint = true)
    }

    fun focusMenu() {
        check(SwingUtilities.isEventDispatchThread())
        if (!isMenuVisible) return
        // 已聚焦的窗口再次 requestFocus 会把焦点从内部 Canvas 临时移到 Dialog，
        // Compose 随之清空节点焦点，而窗口焦点未变时不会重新触发节点恢复。
        if (!overlay.isFocused) overlay.requestFocus()
        if (!contentComponent.isFocusOwner) contentComponent.requestFocusInWindow()
    }

    fun showGame() {
        check(SwingUtilities.isEventDispatchThread())
        if (disposed) return
        menuRequested = false
        update()
    }

    private fun hostVisible(): Boolean = window.isDisplayable && window.isVisible &&
        (window !is Frame || window.extendedState and Frame.ICONIFIED == 0)

    private fun hostLayoutReady(): Boolean =
        displayHost.isShowing && displayHost.width > 0 && displayHost.height > 0

    private fun scheduleUpdate() {
        if (disposed) return
        layoutRetries = 0
        if (updatePending) return
        updatePending = true
        SwingUtilities.invokeLater {
            updatePending = false
            if (!disposed) update(forcePaint = true)
        }
    }

    private fun update(forcePaint: Boolean = false) {
        if (disposed) return
        if (!menuRequested || !hostVisible()) {
            cancelRecovery()
            publishVisible(false)
            return
        }
        // Win32 样式修改与 AWT 布局事件不保证同时完成。瞬时无效时保留已有窗口，
        // 尚未显示的菜单则等待有效视口；不能依赖过渡结束后必定还有下一次组件事件。
        if (!hostLayoutReady()) {
            scheduleLayoutRetry()
            return
        }
        layoutRetryTimer.stop()
        val bounds = Rectangle(displayHost.locationOnScreen, displayHost.size)
        prepareNativeMenu(bounds)
        val boundsChanged = overlay.bounds != bounds
        if (boundsChanged) overlay.bounds = bounds
        val wasVisible = overlay.isVisible
        publishVisible(true)
        if (boundsChanged || !wasVisible || forcePaint) {
            // 原生路径验证实际 SkiaLayer；未挂载的兼容 ComposePanel 不应初始化第二个场景。
            contentComponent.invalidate()
            overlay.invalidate()
            overlay.validate()
            schedulePaint()
        }
        scheduleFocus()
    }

    private fun publishVisible(visible: Boolean) {
        if (overlay.isVisible != visible) overlay.isVisible = visible
        if (publishedVisible != visible) {
            publishedVisible = visible
            onVisibilityChanged(visible)
        }
    }

    private fun scheduleLayoutRetry() {
        if (layoutRetryTimer.isRunning || layoutRetries > MAX_LAYOUT_RETRIES) return
        if (layoutRetries == MAX_LAYOUT_RETRIES) {
            log.warn("[UI] Launcher menu layout retry exhausted (host={}x{}, showing={})",
                displayHost.width, displayHost.height, displayHost.isShowing)
            layoutRetries++
            return
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
            // 当前布局修改完成后补绘一帧，静止界面也不必等到鼠标输入才恢复。
            overlay.validate()
            contentComponent.repaint()
            overlay.repaint()
        }
    }

    private fun scheduleFocus() {
        if (!focusRequested || focusUpdatePending) return
        focusUpdatePending = true
        SwingUtilities.invokeLater {
            focusUpdatePending = false
            if (disposed || !focusRequested || !isMenuVisible || !hostLayoutReady()) return@invokeLater
            val focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
            if (focusedWindow === window || focusedWindow === overlay) {
                focusRequested = false
                focusMenu()
            } else if (focusedWindow != null) {
                // 不抢子弹窗、启动页或其他前台窗口。宿主下次获焦时可重新请求。
                focusRequested = false
            }
        }
    }

    /** 全屏、DPI 或启动窗口建立后补同步；不改变菜单模式，也不产生新的焦点请求。 */
    fun refresh() {
        check(SwingUtilities.isEventDispatchThread())
        if (disposed) return
        layoutRetries = 0
        update(forcePaint = true)
        if (menuRequested && hostVisible()) reassertTimers.forEach(Timer::restart)
    }

    private fun cancelRecovery() {
        layoutRetryTimer.stop()
        reassertTimers.forEach(Timer::stop)
        layoutRetries = 0
        focusRequested = false
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
        val focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
        if (focusedWindow === overlay) focusRequested = true
        overlay.removeWindowListener(overlayListener)
        overlay.dispose()
        nativeLayer = null
        overlay = createSwingOverlay()
        overlay.addWindowListener(overlayListener)
        update(forcePaint = true)
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
        cancelRecovery()
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
        private const val MAX_LAYOUT_RETRIES = 20
        private const val LAYOUT_RETRY_MS = 80
        private val REASSERT_DELAYS_MS = intArrayOf(120, 320)

        @OptIn(ExperimentalComposeUiApi::class)
        fun createMenuPanel(liveBackground: Boolean): ComposePanel =
            if (liveBackground) ComposePanel(renderSettings = RenderSettings.SwingGraphics()) else ComposePanel()

        fun isSupported(window: Window): Boolean =
            window.graphicsConfiguration.device.isWindowTranslucencySupported(PERPIXEL_TRANSLUCENT)
    }
}
