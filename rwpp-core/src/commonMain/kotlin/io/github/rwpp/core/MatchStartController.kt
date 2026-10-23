/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.StartGameEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.GameStartSafety
import io.github.rwpp.game.MatchStartGate
import io.github.rwpp.game.recoverableStartCause
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.logger
import io.github.rwpp.ui.UI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 两端开局共用状态、提示和退房收尾；引擎动作仍由平台在正确线程执行。 */
object MatchStartController {
    val gate = MatchStartGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun ensureCanStart(): Boolean {
        if (!GameStartSafety.requiresRestart) return true
        scope.launch { UI.showWarning(readI18n("multiplayer.room.startMemoryExhausted")) }
        return false
    }

    fun request(): MatchStartGate.Attempt? {
        if (!ensureCanStart()) return null
        if (KeepConnectedReload.active || ModSyncController.cancellingReload) return null
        return gate.request()?.also { trace(it, "request") }
    }

    fun trace(attempt: MatchStartGate.Attempt, action: String) {
        val state = gate.state.value
        logger.info("[GAMESTART] attempt={} action={} phase={} map={} thread={}",
            attempt.id, action, state.phase, state.map, Thread.currentThread().name)
    }

    /** 不能从引擎锁内 invokeAndWait；画面切换排到 Main，并再次校验尝试身份。 */
    fun present(attempt: MatchStartGate.Attempt, action: () -> Unit, onFailure: (Throwable) -> Unit) {
        scope.launch {
            if (!gate.isCurrent(attempt) || gate.state.value.phase != MatchStartGate.Phase.LOADING) return@launch
            try {
                if (!ensureCanStart()) error("Restart required")
                action()
                if (gate.commit(attempt)) {
                    trace(attempt, "started")
                    StartGameEvent().broadcastIn()
                }
            } catch (error: Throwable) {
                onFailure(recoverableStartCause(error))
            }
        }
    }

    /** 平台已阻止残缺地图继续运行；失败恢复不依赖 App 组合或发起协程的寿命。 */
    fun recover(game: Game, attempt: MatchStartGate.Attempt, failure: MatchStartGate.Failure, error: Throwable,
                restorePresentation: () -> Unit = {}) {
        logger.error("[GAMESTART] attempt=${attempt.id} phase=${failure.state.phase} " +
            "map=${failure.state.map} keepRoom=${failure.keepRoom} thread=${Thread.currentThread().name}", error)
        scope.launch {
            if (!gate.isCurrent(attempt)) return@launch
            try {
                restorePresentation()
            } catch (presentationError: Exception) {
                logger.error("[GAMESTART] failed to restore launcher display", presentationError)
            }
            val message = readI18n(if (GameStartSafety.requiresRestart) "multiplayer.room.startMemoryExhausted"
                else if (failure.keepRoom) "multiplayer.room.startPrepareFailed" else "multiplayer.room.startMapLoadFailed")
            if (GameStartSafety.requiresRestart) UI.modReloadMemoryExhausted = true
            if (failure.keepRoom) {
                gate.finishRecovery(attempt)
                if (error !is CancellationException) UI.showWarning(message)
            } else {
                GameSessionController.closeFailedStart(game, message) {
                    gate.finishRecovery(attempt)
                    if (error !is CancellationException) UI.showWarning(message)
                }
            }
        }
    }
}
