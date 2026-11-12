/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import java.awt.Canvas

/**
 * Windows 的焦点请求会等待拥有原生游戏子窗口的线程处理消息。该线程启动时正在加载资源，
 * LWJGL 的 WindowsDisplay$3 又会从 EDT 请求画布焦点，因而停掉加载动画直到资源加载结束。
 * LWJGL 会自行重置 isFocusable，必须在请求入口门控；进游戏的正常焦点请求随后照常处理。
 */
internal class DesktopGameCanvas : Canvas() {
    @Volatile var startupInputEnabled = false

    override fun requestFocus() {
        if (startupInputEnabled) super.requestFocus()
    }

    override fun requestFocusInWindow(): Boolean = startupInputEnabled && super.requestFocusInWindow()
}
