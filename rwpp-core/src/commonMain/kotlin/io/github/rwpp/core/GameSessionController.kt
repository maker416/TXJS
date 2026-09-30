/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rwpp.platform.checkUiThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 会话开始/结束的单一协调入口。开始请求独占等待退房和 setup 的整个过程，重复请求直接忽略。
 * 退房 action 必须等待必要清理完成（含 DisconnectEvent 订阅者），不能只排队发送事件。
 * 引擎调用仍在调用方原有线程执行；控制器只把生命周期状态提交到 Main。
 */
object GameSessionController {
    enum class SessionPhase { IDLE, STARTING, IN_ROOM, CLOSING }

    private val lifecycle = SessionLifecycle(Dispatchers.Main.immediate)
    val sessionPhase: SessionPhase get() = lifecycle.sessionPhase

    /** 返回 null 表示已有开始请求；action 只会在旧退房完整结束后执行。 */
    suspend fun <T> beginSession(action: suspend () -> T): T? = lifecycle.beginSession(action)

    fun onRoomOpened() {
        checkUiThread()
        lifecycle.onRoomOpened()
    }

    fun onExternalSessionEnd() {
        checkUiThread()
        lifecycle.onExternalSessionEnd()
    }

    fun scheduleClose(action: suspend () -> Unit) {
        checkUiThread()
        lifecycle.scheduleClose(action)
    }
}

/** 可注入 Main dispatcher，供回归测试确定性地控制退出与开始的交错。 */
internal class SessionLifecycle(private val mainDispatcher: CoroutineDispatcher) {
    var sessionPhase by mutableStateOf(GameSessionController.SessionPhase.IDLE)
        private set

    private val startMutex = Mutex()
    private var closeJob: Deferred<Unit>? = null
    // 必要退房清理不随 App 重建取消；action 不应依赖已销毁组合的 frame clock。
    private val closeScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    suspend fun <T> beginSession(action: suspend () -> T): T? {
        if (!startMutex.tryLock()) return null
        try {
            withContext(mainDispatcher) {
                while (true) {
                    val closing = closeJob ?: break
                    // await 会传播取消/失败：未完成的清理不能被当成成功屏障。
                    closing.await()
                    if (closeJob === closing) {
                        closeJob = null
                        break
                    }
                }
                sessionPhase = GameSessionController.SessionPhase.STARTING
            }
            return action()
        } finally {
            try {
                withContext(NonCancellable + mainDispatcher) {
                    if (sessionPhase == GameSessionController.SessionPhase.STARTING) {
                        sessionPhase = GameSessionController.SessionPhase.IDLE
                    }
                }
            } finally {
                startMutex.unlock()
            }
        }
    }

    fun onRoomOpened() { sessionPhase = GameSessionController.SessionPhase.IN_ROOM }
    fun onExternalSessionEnd() { sessionPhase = GameSessionController.SessionPhase.IDLE }

    fun scheduleClose(action: suspend () -> Unit) {
        // 重复退出共享同一次清理，不能 cancel 掉已进行中的拆除。
        if (closeJob?.isCompleted == false) return
        sessionPhase = GameSessionController.SessionPhase.CLOSING
        closeJob = closeScope.async(start = CoroutineStart.LAZY) {
            try {
                action()
            } finally {
                if (sessionPhase == GameSessionController.SessionPhase.CLOSING) {
                    sessionPhase = GameSessionController.SessionPhase.IDLE
                }
            }
        }.also { it.start() }
    }
}
