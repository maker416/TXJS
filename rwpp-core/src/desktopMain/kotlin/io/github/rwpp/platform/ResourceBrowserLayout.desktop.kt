/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.SwingDialog
import androidx.compose.ui.graphics.toArgb
import io.github.rwpp.config.ResourceBrowserOrientation
import java.awt.Color
import java.awt.Dialog
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JDialog

/**
 * 承载内置浏览器的宿主菜单窗口。透明/分层的菜单窗口不能接收 JCEF 子窗口的鼠标输入，
 * 因此只要存在宿主，网页一律放进独立的不透明子窗口（见 [BrowserPageWindow]）。
 */
val LocalDesktopBrowserPageOwner = staticCompositionLocalOf<JDialog?> { null }

@Composable
actual fun ResourceBrowserLayout(
    orientation: ResourceBrowserOrientation?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val viewport = if (orientation == ResourceBrowserOrientation.Portrait) {
            val width = minOf(maxWidth, maxHeight * 9f / 16f)
            Modifier.size(width, width * 16f / 9f)
        } else {
            Modifier.fillMaxSize()
        }
        Box(viewport) { content() }
    }
}

/** Windows 的透明 Direct3D 菜单无法显示 heavyweight Chromium 子窗口。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun BrowserPageWindow(visible: Boolean, content: @Composable () -> Unit) {
    val owner = LocalDesktopBrowserPageOwner.current
    if (owner == null) {
        content()
        return
    }

    val viewport = remember(owner) { mutableStateOf(owner.bounds.takeIf { owner.isShowing }) }
    DisposableEffect(owner) {
        val listener = object : ComponentAdapter() {
            private fun sync() { viewport.value = owner.bounds.takeIf { owner.isShowing } }

            override fun componentShown(event: ComponentEvent) = sync()
            override fun componentHidden(event: ComponentEvent) = sync()
            override fun componentMoved(event: ComponentEvent) = sync()
            override fun componentResized(event: ComponentEvent) = sync()
        }
        owner.addComponentListener(listener)
        onDispose { owner.removeComponentListener(listener) }
    }

    val bounds = viewport.value
    val surface = MaterialTheme.colorScheme.surface
    // 网页和它的 Popup/Dialog 共用独立宿主，原生网页取得焦点后关闭球和模态框仍在上方。
    SwingDialog(
        visible = visible && bounds != null && !bounds.isEmpty,
        create = {
            ComposeDialog(owner, Dialog.ModalityType.MODELESS, owner.graphicsConfiguration).apply {
                title = owner.title
                isUndecorated = true
                isResizable = false
                defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
                addWindowListener(object : WindowAdapter() {
                    override fun windowClosing(event: WindowEvent) {
                        owner.dispatchEvent(WindowEvent(owner, WindowEvent.WINDOW_CLOSING))
                    }
                })
            }
        },
        dispose = ComposeDialog::dispose,
        update = { dialog ->
            val background = Color(surface.toArgb())
            if (dialog.background != background) dialog.background = background
            if (bounds != null && !bounds.isEmpty) {
                if (dialog.bounds != bounds) dialog.bounds = bounds
                // 首次显示前建立 peer，让 SwingDialog 在窗口揭开前提交主题背景。
                if (!dialog.isDisplayable) {
                    dialog.addNotify()
                    dialog.validate()
                }
            }
        },
    ) {
        val opened = remember(window) { mutableStateOf(window.isShowing) }
        if (!opened.value) {
            DisposableEffect(window) {
                val listener = object : ComponentAdapter() {
                    override fun componentShown(event: ComponentEvent) { opened.value = true }
                }
                window.addComponentListener(listener)
                if (window.isShowing) opened.value = true
                onDispose { window.removeComponentListener(listener) }
            }
        }
        Box(Modifier.fillMaxSize().background(surface)) {
            // 首次显示后才挂载内容，避免浏览器的 Popup 在宿主没有屏幕坐标时落到原点。
            if (opened.value) content()
        }
    }
}
