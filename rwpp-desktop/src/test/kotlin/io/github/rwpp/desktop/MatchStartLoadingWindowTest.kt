/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.MatchStartGate
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.logger
import io.github.rwpp.widget.MatchStartLoadingOverlay
import net.peanuuutz.tomlkt.Toml
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.awt.Canvas
import java.awt.CardLayout
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*

/** 离屏测试无法覆盖隐藏 ComposePanel 后独立 Dialog 仍盖在游戏 Canvas 上的情况。 */
class MatchStartLoadingWindowTest {
    @Test fun sandboxStartDoesNotLeaveLoadingWindowOverGame() = verify(GameStartMode.Skirmish(true), true)
    @Test fun multiplayerStartWithAnimationsDisabledDoesNotLeaveLoadingWindowOverGame() = verify(GameStartMode.Multiplayer, false)

    private fun verify(mode: GameStartMode, animations: Boolean) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        assumeFalse(GraphicsEnvironment.isHeadless())
        val previousLayers = System.getProperty("compose.layers.type")
        System.setProperty("compose.layers.type", "WINDOW")
        assertTrue(DesktopWindowLayers.install())
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = animations) } }) }.koin
        logger = LoggerFactory.getLogger("MatchStartLoadingWindowTest")
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("../rwpp-core/src/commonMain/composeResources/files/bundle_zh.toml").readText())
        val gate = MatchStartGate()
        lateinit var attempt: MatchStartGate.Attempt
        val composed = AtomicBoolean()
        var loading by mutableStateOf(false)
        lateinit var frame: JFrame
        var owner: JFrame? = null
        lateinit var launcher: ComposePanel
        lateinit var canvas: Canvas
        lateinit var switcher: DisplaySwitcher
        var started: GameStartMode? = null
        try {
            onEdt {
                launcher = ComposePanel()
                canvas = Canvas().apply { isVisible = false }
                val cards = CardLayout()
                val host = JPanel(cards).apply {
                    add(launcher, DisplayMode.Menu.cardName)
                    add(canvas, DisplayMode.Game.cardName)
                }
                frame = JFrame("RWJS loading overlay regression").apply {
                    add(host)
                    setSize(800, 550)
                    setLocationRelativeTo(null)
                }
                owner = frame
                switcher = DisplaySwitcher(launcher, canvas, host, cards, frame)
                launcher.setContent {
                    // 在当前重组作用域读取，SideEffect 才能确认这一轮可见状态已提交。
                    val currentLoading = loading
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            MatchStartLoadingOverlay(currentLoading, isDesktop = true)
                        }
                    }
                    SideEffect { composed.set(currentLoading) }
                }
                frame.isVisible = true
                attempt = gate.beginLoading()!!
                loading = true
            }
            await("loading overlay") {
                launcher.isShowing && (composed.get() || frame.ownedWindows.any { it.isVisible })
            }
            // 让原生弹层有机会实际显示，再复现 production 的「先切画面、再 commit」顺序。
            onEdt {
                val manager = GameSessionManager(switcher, { started = it }, {})
                manager.startGame(mode)
                assertTrue(gate.commit(attempt))
                loading = gate.busy
                assertEquals(MatchStartGate.Phase.RUNNING, gate.state.value.phase)
                assertTrue(canvas.isShowing)
                assertFalse(launcher.isShowing)
            }
            onEdt {
                assertEquals(mode, started)
                assertTrue(frame.ownedWindows.none { it.isVisible },
                    "加载已完成且启动器已隐藏，原生加载窗仍盖在游戏上")
                switcher.switchTo(DisplayMode.Menu)
            }
            await("return to launcher") {
                launcher.isShowing && !composed.get() && frame.ownedWindows.none { it.isVisible }
            }
        } finally {
            onEdt { owner?.let { it.ownedWindows.forEach(Window::dispose); it.dispose() } }
            stopKoin()
            reloadI18n()
            if (previousLayers == null) System.clearProperty("compose.layers.type")
            else System.setProperty("compose.layers.type", previousLayers)
        }
    }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }
    private fun await(stage: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            var complete = false
            onEdt { complete = condition() }
            if (complete) return
            Thread.sleep(20)
        }
        fail("Native loading overlay did not reach the expected state: $stage")
    }
}
