/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import io.github.rwpp.event.broadcast
import io.github.rwpp.event.events.KeyboardEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** UI 回调不能同步等事件订阅者：订阅者可能需要回到同一条 Main/EDT 线程。 */
internal fun Modifier.launcherKeyboardEvents(scope: CoroutineScope): Modifier = onKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown) {
        scope.launch { KeyboardEvent(event.key.keyCode.toInt()).broadcast() }
        // 返回由统一 BackHandler 异步分派，消费 Esc 防止同时传给下层页面。
        event.key == Key.Escape
    } else false
}

@Composable
fun Modifier.autoClearFocus() = composed {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    this.clickable(
        interactionSource = interactionSource,
        indication = null
    ) {
        keyboardController?.hide()
        focusManager.clearFocus()
    }
}
