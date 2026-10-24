/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

/** 由平台真实画面切换维护，不由对局事件推测（任务/回放也必须覆盖）。 */
object LauncherVisibility {
    private val state = MutableStateFlow(true)
    val visible: StateFlow<Boolean> = state.asStateFlow()
    fun setVisible(visible: Boolean) { state.value = visible }
}

/** 隐藏后取消当前一轮并挂起；恢复立即采样，不保留周期唤醒。 */
suspend fun StateFlow<Boolean>.pollWhileVisible(intervalMillis: Long, action: suspend () -> Unit) {
    require(intervalMillis > 0)
    collectLatest { visible ->
        if (visible) while (true) {
            currentCoroutineContext().ensureActive()
            action()
            currentCoroutineContext().ensureActive()
            delay(intervalMillis)
        }
    }
}

/** 直接观察 StateFlow，Android 停止组合帧时也能立即取消后台协程。 */
suspend fun StateFlow<Boolean>.whileVisible(action: suspend () -> Unit) {
    collectLatest { visible ->
        if (visible) {
            currentCoroutineContext().ensureActive()
            action()
            currentCoroutineContext().ensureActive()
        }
    }
}
