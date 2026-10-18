/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import javassist.ClassPool
import javassist.CtNewMethod
import javassist.LoaderClassPath
import javassist.bytecode.Opcode
import org.slf4j.LoggerFactory
import java.awt.Rectangle
import java.lang.invoke.MethodHandles
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.SwingUtilities

/**
 * Compose 1.10.1 的 WINDOW 弹层兼容处理，只在 Windows 桌面入口、创建 ComposePanel 之前安装。
 *
 * 上游按每帧绘制范围移动/缩放原生窗口；缩放动画、阴影与异步缩略图会不断改变该范围，
 * 还会在 Skiko 记录绘制指令时触发窗口 resize。将可聚焦弹窗固定为宿主视口，内容仍照常布局、
 * 动画和接收输入；非聚焦浏览器悬浮控件保留小窗口，避免挡住网页其余区域。
 *
 * 修改发生在类首次定义前，无需 javaagent、额外 JDK 模块或逐帧反射。升级 Compose 时必须重新
 * 验证此兼容处理；字节码形态不匹配时保留原库并记录警告，不生成部分修改的类。
 */
internal object DesktopWindowLayers {
    private const val WINDOW_LAYER = "androidx.compose.ui.scene.WindowComposeSceneLayer"
    private const val COMPOSE_CONTAINER = "androidx.compose.ui.scene.ComposeContainer"
    private const val UPDATE_PENDING = "io.github.rwpp.desktop.viewportUpdatePending"
    private val log = LoggerFactory.getLogger(DesktopWindowLayers::class.java)
    private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return false
        if (!System.getProperty("compose.layers.type", "").equals("WINDOW", ignoreCase = true)) return false

        return try {
            val loader = DesktopWindowLayers::class.java.classLoader
            val pool = ClassPool(false).apply {
                appendSystemPath()
                insertClassPath(LoaderClassPath(loader))
            }
            val layer = pool.get(WINDOW_LAYER)
            try {
                // 校验下面依赖的内部 ABI；在 defineClass 之前完成全部修改和验证。
                check(layer.getDeclaredField("layerWindow").type.name == JDialog::class.java.name)
                check(layer.getDeclaredField("mediator").type.name == "androidx.compose.ui.scene.ComposeSceneMediator")

                layer.getDeclaredMethod("onDrawBoundsChanged").setBody(
                    """
                    {
                        javax.swing.JLayeredPane host = getWindowContainer();
                        float scale = getDensity().getDensity();
                        int width = Math.round(host.getWidth() * scale);
                        int height = Math.round(host.getHeight() * scale);
                        if (getFocusable() || !isBoundsInWindowSet()) {
                            setDrawBounds(new androidx.compose.ui.unit.IntRect(0, 0, width, height));
                        }
                        androidx.compose.ui.unit.IntRect draw = getDrawBounds();
                        androidx.compose.ui.scene.ComposeSceneMediator scene = getMediator();
                        if (scene != null) {
                            androidx.compose.ui.geometry.Rect bounds =
                                new androidx.compose.ui.geometry.Rect(
                                    (float) -draw.getLeft(), (float) -draw.getTop(),
                                    (float) (width - draw.getLeft()), (float) (height - draw.getTop()));
                            if (!bounds.equals(scene.getSceneBoundsInPx())) scene.setSceneBoundsInPx(bounds);
                        }
                        java.awt.Rectangle viewport = getFocusable()
                            ? new java.awt.Rectangle(0, 0, host.getWidth(), host.getHeight())
                            : androidx.compose.ui.awt.Utils_desktopKt.toAwtRectangle(draw, getDensity());
                        io.github.rwpp.desktop.DesktopWindowLayers.syncViewport(
                            layerWindow, host, scene == null ? null : scene.getContentComponent(), viewport);
                    }
                    """.trimIndent(),
                )
                layer.getDeclaredMethod("onWindowContainerSizeChanged").insertAfter("onDrawBoundsChanged();")
                layer.getDeclaredMethod("onWindowContainerPositionChanged").setBody("{ onWindowContainerSizeChanged(); }")

                // 可聚焦弹窗无需再录制一次 Picture 测量动画边界；浏览器悬浮控件仍需小窗口。
                // 所有 native resize 均由 syncViewport 排到当前绘制结束之后。
                layer.addMethod(CtNewMethod.make(
                    """
                    private org.jetbrains.skiko.SkikoRenderDelegate rwjsViewportRenderer(
                            org.jetbrains.skiko.SkikoRenderDelegate delegate) {
                        if (getFocusable()) return delegate;
                        return recordDrawBounds(delegate);
                    }
                    """.trimIndent(), layer,
                ))
                val createLayer = layer.getDeclaredMethod("createSkiaLayerComponent")
                val info = createLayer.methodInfo
                val code = info.codeAttribute
                val iterator = code.iterator()
                var replacements = 0
                while (iterator.hasNext()) {
                    val position = iterator.next()
                    if (iterator.byteAt(position) != Opcode.INVOKEVIRTUAL) continue
                    val method = iterator.u16bitAt(position + 1)
                    if (info.constPool.getMethodrefName(method) != "recordDrawBounds") continue
                    check(info.constPool.getMethodrefType(method) ==
                        "(Lorg/jetbrains/skiko/SkikoRenderDelegate;)Landroidx/compose/ui/skiko/RecordDrawRectRenderDecorator;")
                    val renderer = info.constPool.addMethodrefInfo(
                        info.constPool.addClassInfo(WINDOW_LAYER), "rwjsViewportRenderer",
                        "(Lorg/jetbrains/skiko/SkikoRenderDelegate;)Lorg/jetbrains/skiko/SkikoRenderDelegate;",
                    )
                    iterator.write16bit(renderer, position + 1)
                    replacements++
                }
                check(replacements == 1) { "Unexpected Compose window-layer renderer: $replacements bound recorders" }
                info.rebuildStackMap(pool)

                val container = Class.forName(COMPOSE_CONTAINER, false, loader)
                MethodHandles.privateLookupIn(container, MethodHandles.lookup()).defineClass(layer.toBytecode())
            } finally {
                layer.detach()
            }
            installed = true
            log.info("[UI] Native popup/dialog windows now use stable host viewports")
            true
        } catch (failure: Exception) {
            log.warn("[UI] Could not install stable native window viewports; using the original Compose layers", failure)
            false
        } catch (failure: LinkageError) {
            log.warn("[UI] Compose window layer was already defined or is incompatible; using the original layers", failure)
            false
        }
    }

    /** 由兼容类直接调用；可见窗口的尺寸更新排到当前绘制结束后，并合并重复请求。 */
    @JvmStatic
    fun syncViewport(window: JDialog, host: JComponent, content: JComponent?, viewport: Rectangle) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater {
                if (window.isDisplayable && host.isShowing) syncViewport(window, host, content, viewport)
            }
            return
        }
        val request = ViewportUpdate(host, content, Rectangle(viewport))
        if (!window.isVisible) {
            applyViewport(window, request)
            return
        }
        val root = window.rootPane
        val queued = root.getClientProperty(UPDATE_PENDING) != null
        if (!queued && viewportMatches(window, request)) return
        root.putClientProperty(UPDATE_PENDING, request)
        if (queued) return
        SwingUtilities.invokeLater {
            val latest = root.getClientProperty(UPDATE_PENDING) as? ViewportUpdate
            root.putClientProperty(UPDATE_PENDING, null)
            // 退出/切页可能已销毁弹层，排队的 resize 不得重新创建它的 native peer。
            if (latest != null && window.isDisplayable && latest.host.isShowing) applyViewport(window, latest)
        }
    }

    private data class ViewportUpdate(val host: JComponent, val content: JComponent?, val viewport: Rectangle) {
        fun screenBounds(window: JDialog): Rectangle = Rectangle(viewport).apply {
            val origin = if (host.isShowing) host.locationOnScreen else window.location
            translate(origin.x, origin.y)
        }
    }

    private fun viewportMatches(window: JDialog, update: ViewportUpdate): Boolean =
        window.bounds == update.screenBounds(window) &&
            (update.content == null || update.content.size == update.viewport.size)

    private fun applyViewport(window: JDialog, update: ViewportUpdate) {
        if (update.viewport.width <= 0 || update.viewport.height <= 0) return
        val bounds = update.screenBounds(window)
        if (window.bounds != bounds) window.bounds = bounds
        if (update.content != null && update.content.size != update.viewport.size) update.content.size = update.viewport.size
    }
}
