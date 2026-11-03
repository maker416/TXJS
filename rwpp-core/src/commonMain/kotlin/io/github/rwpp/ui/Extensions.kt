/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.i18n.readI18n

/** 扩展目前仅展示美术包管理；保留原有扩展面板关闭通知。 */
@Composable
fun ExtensionView(onExit: () -> Unit) {
    DisposableEffect(Unit) {
        onDispose { CloseUIPanelEvent("extension").broadcastIn() }
    }
    ThemesView(onExit = onExit, title = readI18n("extension.extension"))
}
