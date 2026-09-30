/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

@file:JvmName("BackScopeKt")

package io.github.rwpp.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** 动画退出中的页面、被叠加页遮住的页面不能接收返回事件。 */
internal val LocalBackHandlerEnabled = staticCompositionLocalOf<() -> Boolean> { { true } }

@Composable
internal fun BackHandlerScope(enabled: () -> Boolean, content: @Composable () -> Unit) {
    val parentEnabled = LocalBackHandlerEnabled.current
    CompositionLocalProvider(LocalBackHandlerEnabled provides { parentEnabled() && enabled() }) {
        content()
    }
}

@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)

