/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rwpp.game.base.Difficulty
import io.github.rwpp.game.map.Mission
import io.github.rwpp.game.map.Replay
import io.github.rwpp.logger

sealed class GameStartMode {
    data class Mission(val difficulty: Difficulty, val mission: io.github.rwpp.game.map.Mission) : GameStartMode()
    data class Skirmish(val sandbox: Boolean = false) : GameStartMode()
    data object Multiplayer : GameStartMode()
    data class Replay(val replay: io.github.rwpp.game.map.Replay) : GameStartMode()
    data object Continue : GameStartMode()
}

class GameSessionManager(
    private val displaySwitcher: DisplaySwitcher,
    private val onStart: (GameStartMode) -> Unit,
    private val onReturnToMenu: () -> Unit
) {
    var isGameOver: Boolean by mutableStateOf(false)
    var gameSpeed: Float by mutableStateOf(1f)
    var isHost: Boolean by mutableStateOf(false)

    fun startGame(mode: GameStartMode) {
        logger.info("[GameSession] startGame({}) — currentMode={} (thread={})",
            gameStartModeSummary(mode), displaySwitcher.currentMode.value, Thread.currentThread().name)
        displaySwitcher.switchTo(DisplayMode.Game)
        logger.debug("[GameSession] startGame: invoking onStart callback")
        onStart(mode)
        logger.info("[GameSession] startGame({}) done", gameStartModeSummary(mode))
    }

    fun returnToMenu() {
        logger.info("[GameSession] returnToMenu() — currentMode={} (thread={})",
            displaySwitcher.currentMode.value, Thread.currentThread().name)
        logger.debug("[GameSession] returnToMenu: invoking onReturnToMenu callback")
        onReturnToMenu()
        displaySwitcher.switchTo(DisplayMode.Menu)
        logger.info("[GameSession] returnToMenu() done")
    }

    private fun gameStartModeSummary(mode: GameStartMode): String = when (mode) {
        is GameStartMode.Mission -> "Mission(name=${mode.mission.displayName()}, difficulty=${mode.difficulty})"
        is GameStartMode.Skirmish -> "Skirmish(sandbox=${mode.sandbox})"
        is GameStartMode.Multiplayer -> "Multiplayer"
        is GameStartMode.Replay -> "Replay(name=${mode.replay.name})"
        is GameStartMode.Continue -> "Continue"
    }
}
