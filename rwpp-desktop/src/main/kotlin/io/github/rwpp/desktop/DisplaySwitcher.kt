/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.github.rwpp.logger
import java.awt.CardLayout
import java.awt.Canvas
import java.awt.Component
import java.awt.Window
import java.lang.reflect.InvocationTargetException
import javax.swing.JComponent
import javax.swing.SwingUtilities

sealed class DisplayMode(val cardName: String) {
    data object Menu : DisplayMode("menu")
    data object Game : DisplayMode("game")
}

class DisplaySwitcher(
    private val menuPanel: Component,
    private val gameCanvas: Canvas,
    private val displayHost: JComponent,
    private val displayLayout: CardLayout,
    private val window: Window
) {
    private val _currentMode = mutableStateOf<DisplayMode>(DisplayMode.Menu)
    val currentMode: State<DisplayMode> = _currentMode

    val onBeforeSwitch = mutableListOf<(DisplayMode) -> Unit>()
    val onAfterSwitch = mutableListOf<(DisplayMode) -> Unit>()

    fun switchTo(target: DisplayMode) {
        if (_currentMode.value == target) {
            logger.debug("[DisplaySwitcher] switchTo({}) ignored — already in this mode (thread={})", target, Thread.currentThread().name)
            return
        }
        logger.info("[DisplaySwitcher] switchTo({}) queued from {} → {} (thread={})",
            target, _currentMode.value, target, Thread.currentThread().name)
        if (SwingUtilities.isEventDispatchThread()) {
            executeSwitch(target)
            return
        }

        try {
            SwingUtilities.invokeAndWait { executeSwitch(target) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException("Interrupted while switching display to $target", e)
        } catch (e: InvocationTargetException) {
            throw RuntimeException("Failed to switch display to $target", e.cause ?: e)
        }
    }

    private fun executeSwitch(target: DisplayMode) {
        if (_currentMode.value == target) {
            logger.debug("[DisplaySwitcher] executeSwitch({}) ignored — mode already changed during queue delay", target)
            return
        }

        logger.info("[DisplaySwitcher] executeSwitch: {} → {} (canvasDisplayable={}, canvasVisible={})",
            _currentMode.value, target, gameCanvas.isDisplayable, gameCanvas.isVisible)

        onBeforeSwitch.forEach { it(target) }

        when (target) {
            DisplayMode.Menu -> showMenu()
            DisplayMode.Game -> showGame()
        }

        _currentMode.value = target

        logger.info("[DisplaySwitcher] executeSwitch complete — now in mode {}", _currentMode.value)

        onAfterSwitch.forEach { it(target) }
    }

    private fun showMenu() {
        logger.info("[DisplaySwitcher] showMenu: hiding canvas, showing menu")
        displayLayout.show(displayHost, DisplayMode.Menu.cardName)
        refreshWindow()
        SwingUtilities.invokeLater {
            menuPanel.requestFocusInWindow()
        }
    }

    private fun showGame() {
        logger.info("[DisplaySwitcher] showGame: showing canvas (canvasDisplayable={})", gameCanvas.isDisplayable)
        displayLayout.show(displayHost, DisplayMode.Game.cardName)
        refreshWindow()
        window.toFront()
        window.requestFocus()
        SwingUtilities.invokeLater {
            if (!gameCanvas.requestFocusInWindow()) {
                gameCanvas.requestFocus()
            }
            gameCanvas.repaint()
        }
    }

    private fun refreshWindow() {
        displayHost.invalidate()
        displayHost.validate()
        window.invalidate()
        window.validate()
        window.repaint()
    }
}
