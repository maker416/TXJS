/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

/** 等待室进入对局的门控，与创建/退出房间的 GameSessionController 分开。 */
class MatchStartGate {
    enum class Phase { IDLE, PREPARING, SENDING, LOADING, RUNNING, RECOVERING }
    class Attempt internal constructor(val id: Long)
    data class State(val attempt: Attempt? = null, val phase: Phase = Phase.IDLE, val map: String = "") {
        val busy: Boolean get() = phase != Phase.IDLE && phase != Phase.RUNNING
    }
    data class Failure(val state: State, val keepRoom: Boolean)

    private var sequence = 0L
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    val busy: Boolean get() = state.value.busy

    @Synchronized
    fun request(): Attempt? {
        if (state.value.phase != Phase.IDLE) return null
        val attempt = Attempt(++sequence)
        mutableState.value = State(attempt, Phase.PREPARING)
        return attempt
    }

    /** 客人直接收到引擎回调；房主的同一次请求由 SENDING 转入 LOADING。 */
    @Synchronized
    fun beginLoading(): Attempt? {
        val previous = state.value
        if (previous.phase != Phase.IDLE && previous.phase != Phase.SENDING) return null
        val attempt = previous.attempt ?: Attempt(++sequence)
        mutableState.value = previous.copy(attempt = attempt, phase = Phase.LOADING)
        return attempt
    }

    @Synchronized
    fun describe(attempt: Attempt, map: String) {
        if (isCurrent(attempt)) mutableState.value = state.value.copy(map = map)
    }

    @Synchronized
    fun sending(attempt: Attempt): Boolean {
        if (!isCurrent(attempt) || state.value.phase != Phase.PREPARING) return false
        mutableState.value = state.value.copy(phase = Phase.SENDING)
        return true
    }

    @Synchronized
    fun commit(attempt: Attempt): Boolean {
        if (!isCurrent(attempt) || state.value.phase != Phase.LOADING) return false
        mutableState.value = state.value.copy(phase = Phase.RUNNING)
        return true
    }

    @Synchronized
    fun fail(attempt: Attempt, knownUnsent: Boolean = false, memoryExhausted: Boolean = false): Failure? {
        val previous = state.value
        if (!isCurrent(attempt) || previous.phase == Phase.RECOVERING) return null
        val keepRoom = !memoryExhausted && (previous.phase == Phase.PREPARING ||
            knownUnsent && previous.phase == Phase.SENDING)
        mutableState.value = previous.copy(phase = Phase.RECOVERING)
        return Failure(previous, keepRoom)
    }

    @Synchronized
    fun finishRecovery(attempt: Attempt) {
        if (isCurrent(attempt) && state.value.phase == Phase.RECOVERING) reset()
    }

    @Synchronized
    fun reset() {
        mutableState.value = State()
    }

    fun isCurrent(attempt: Attempt): Boolean = state.value.attempt == attempt

    /** 加载不能在持有门控锁时执行；断线使尝试失效后不再提交画面。 */
    fun runLoading(attempt: Attempt, load: () -> Unit, ready: () -> Unit, failed: (Throwable) -> Unit) {
        if (!isCurrent(attempt) || state.value.phase != Phase.LOADING) return
        try {
            load()
            if (isCurrent(attempt) && state.value.phase == Phase.LOADING) ready()
        } catch (error: Throwable) {
            failed(recoverableStartCause(error))
        }
    }
}

/** 地图加载 OOM 后不再启动另一轮资源加载；退房/重新进房不能清除此进程级标志。 */
object GameStartSafety {
    private val exhausted = AtomicBoolean(false)
    val requiresRestart: Boolean get() = exhausted.get()
    fun markMemoryExhausted() { exhausted.set(true) }
    fun requireCanReload() {
        check(!requiresRestart) { "Restart required after map loading exhausted memory" }
    }
}

/** 反射调用会包装 OOM。其他 VM/链接错误仍交给原有致命异常处理。 */
fun recoverableStartCause(error: Throwable): Throwable {
    var cause = error
    while (cause is InvocationTargetException && cause.targetException != null) cause = cause.targetException
    if (cause !is Exception && cause !is OutOfMemoryError) throw cause
    return cause
}
