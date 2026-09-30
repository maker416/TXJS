/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import io.github.rwpp.event.EventPriority
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.Listener
import io.github.rwpp.event.events.KeyboardEvent
import javax.swing.SwingUtilities

/** 与 Android 一致：最后注册且仍有效的处理器独占一次返回事件。 */
internal class BackDispatcher(private val schedule: (() -> Unit) -> Unit) {
    class Handler(val enabled: () -> Boolean, val onBack: () -> Unit)

    private val handlers = mutableListOf<Handler>()

    @Synchronized
    fun register(enabled: () -> Boolean, onBack: () -> Unit): Handler =
        Handler(enabled, onBack).also { handlers.add(it) }

    @Synchronized
    fun unregister(handler: Handler) { handlers.remove(handler) }

    val isEmpty: Boolean
        @Synchronized get() = handlers.isEmpty()

    fun dispatch(): Boolean {
        val handler = synchronized(this) { handlers.lastOrNull { it.enabled() } } ?: return false
        schedule {
            // 队列等待期间可能已换页或 dispose，不执行迟到的返回。
            val active = synchronized(this) { handler in handlers && handler.enabled() }
            if (active) handler.onBack()
        }
        return true
    }
}

private object DesktopBackHandlers {
    val dispatcher = BackDispatcher { SwingUtilities.invokeLater(it) }
    private val channel = GlobalEventChannel.filter(KeyboardEvent::class)
    private var listener: Listener<KeyboardEvent>? = null

    fun register(enabled: () -> Boolean, onBack: () -> Unit): BackDispatcher.Handler {
        val handler = dispatcher.register(enabled, onBack)
        if (listener == null) {
            listener = channel.subscribeAlways(priority = EventPriority.HIGH) {
                if (it.keyCode == 0x1B && dispatcher.dispatch()) it.intercept()
            }
        }
        return handler
    }

    fun unregister(handler: BackDispatcher.Handler) {
        dispatcher.unregister(handler)
        if (dispatcher.isEmpty) {
            listener?.let { channel.unregisterListener(it) }
            listener = null
        }
    }
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val currentEnabled = rememberUpdatedState(enabled)
    val currentScope = rememberUpdatedState(LocalBackHandlerEnabled.current)
    val currentOnBack = rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        val handler = DesktopBackHandlers.register(
            enabled = { currentEnabled.value && currentScope.value() },
            onBack = { currentOnBack.value() },
        )
        onDispose { DesktopBackHandlers.unregister(handler) }
    }
}
