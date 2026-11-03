/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.corrodinggames.librocket.scripts.ScriptEngine
import io.github.rwpp.core.GameSessionController
import io.github.rwpp.core.MatchStartController
import io.github.rwpp.desktop.impl.GameImpl
import io.github.rwpp.game.GameStartSafety
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.game.mod.ModLoadSafety
import io.github.rwpp.logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.newdawn.slick.GameContainer
import javax.swing.SwingUtilities

/** The launcher publishes visibility; only the game thread touches the vanilla menu map. */
object DesktopMenuBackgroundEngine {
    @Volatile private var requested = false
    @Volatile private var pendingAvailability = false
    private val mutableAvailable = MutableStateFlow(false)
    val available = mutableAvailable.asStateFlow()

    // A missing/broken background must not retry a map load on every frame.
    private var loadFailed = false
    private var limitMenuFrameRate = false

    fun request(requested: Boolean) {
        this.requested = requested
        if (!requested) publishAvailability(false)
    }

    /** Called by the native render callback after queued engine actions have finished. */
    internal fun prepareFrame(): Boolean {
        limitMenuFrameRate = false
        if (KeepConnectedReload.active || GameStartSafety.requiresRestart) {
            publishAvailability(false)
            return false
        }
        if (!desktopEngineLoaded) {
            publishAvailability(false)
            return true
        }
        val engine = GameEngine.B()
        if (engine == null) {
            publishAvailability(false)
            return false
        }
        val gameVisible = isGaming
        limitMenuFrameRate = !gameVisible && !engine.bX.B
        val renderRequested = requested && engine.bQ.renderBackground && !GameEngine.ay &&
            !engine.bI && !ModLoadSafety.loading
        val canLoadMap = !loadFailed && !engine.bI && !ModLoadSafety.loading &&
            !MatchStartController.gate.busy &&
            GameSessionController.sessionPhase == GameSessionController.SessionPhase.IDLE

        fun decision() = menuBackgroundFrameDecision(
            gameVisible = gameVisible,
            hasMap = engine.bG,
            menuMap = engine.bH,
            connected = engine.bX.B,
            renderRequested = renderRequested,
            canLoadMap = canLoadMap,
        )

        var frame = decision()
        if (frame.loadBackground) {
            try {
                // x() is the original AI demo-map loader. Never call it over a saved match or lobby.
                engine.x()
                if (!engine.bG || !engine.bH) {
                    loadFailed = true
                    logger.warn("[MENU] vanilla background map did not finish loading")
                }
            } catch (error: OutOfMemoryError) {
                loadFailed = true
                GameStartSafety.markMemoryExhausted()
                logger.error("[MENU] background map exhausted memory", error)
            } catch (error: Exception) {
                loadFailed = true
                logger.warn("[MENU] cannot load vanilla background map", error)
            }
            frame = decision()
        }

        if (frame.showBackground) {
            // These are vanilla menu flags; they suppress game HUD, input and mouse capture.
            engine.aq = true
            engine.cS = true
        }
        publishAvailability(frame.showBackground && !GameStartSafety.requiresRestart)
        return frame.renderOriginal && !GameStartSafety.requiresRestart
    }

    internal fun finishFrame(container: GameContainer?) {
        // u.a() resets the native target every frame. Cap only the standalone AI demo, never a room.
        if (limitMenuFrameRate) container?.setTargetFrameRate(30)
    }

    /** u.render also services these queues; decoration visibility must not suspend their work. */
    internal fun pumpIdleCallbacks(deltaMillis: Int) {
        if (!desktopEngineLoaded || isGaming || KeepConnectedReload.active ||
            GameStartSafety.requiresRestart || ModLoadSafety.loading) return
        val engine = GameEngine.B() ?: return
        if (engine.bI || engine.bX.B) return
        val delta = deltaMillis.coerceAtLeast(0) * 0.06f
        val main = GameImpl.main
        main.a(delta)
        if (KeepConnectedReload.active || GameStartSafety.requiresRestart || ModLoadSafety.loading || engine.bI) return
        ScriptEngine.getInstance()?.update(delta)
        // This vanilla deferred save normally lives at the end of u.render, after script processing.
        if (main.u && ++main.v > 2) {
            main.u = false
            main.v = 0
            engine.bQ.save()
        }
    }

    private fun publishAvailability(value: Boolean) {
        if (pendingAvailability == value && mutableAvailable.value == (value && requested && !isGaming)) return
        pendingAvailability = value
        val publish = Runnable {
            // Discard a queued game-thread result after a UI hide/image change.
            mutableAvailable.value = pendingAvailability && requested && !isGaming
        }
        if (SwingUtilities.isEventDispatchThread()) publish.run() else SwingUtilities.invokeLater(publish)
    }
}

internal data class MenuBackgroundFrameDecision(
    val renderOriginal: Boolean = false,
    val showBackground: Boolean = false,
    val loadBackground: Boolean = false,
)

/** Existing real maps belong to Continue/rooms and must never be replaced by a decoration. */
internal fun menuBackgroundFrameDecision(
    gameVisible: Boolean,
    hasMap: Boolean,
    menuMap: Boolean,
    connected: Boolean,
    renderRequested: Boolean,
    canLoadMap: Boolean,
): MenuBackgroundFrameDecision = when {
    gameVisible -> MenuBackgroundFrameDecision(renderOriginal = true)
    hasMap && menuMap -> MenuBackgroundFrameDecision(
        renderOriginal = renderRequested || connected,
        showBackground = renderRequested,
    )
    hasMap || connected -> MenuBackgroundFrameDecision(renderOriginal = connected)
    else -> MenuBackgroundFrameDecision(loadBackground = renderRequested && canLoadMap)
}
