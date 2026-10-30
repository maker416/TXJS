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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.logger
import io.github.rwpp.net.ConnectionFailure
import io.github.rwpp.net.ConnectionFailureReason
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.ConnectionFailureCard
import net.peanuuutz.tomlkt.Toml
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.awt.event.InputEvent
import java.io.File
import javax.accessibility.Accessible
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.*

/** 使用生产 WINDOW 弹层，覆盖离屏测试无法验证的原生窗口宿主。 */
class ConnectionFailureWindowTest {
    @Test
    fun nativeFailureCardsSupportBothLanguagesAndSmallWindows() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        assumeFalse(GraphicsEnvironment.isHeadless())
        val previousLayers = System.getProperty("compose.layers.type")
        System.setProperty("compose.layers.type", "WINDOW")
        assertTrue(DesktopWindowLayers.install())
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = false) } }) }.koin
        logger = LoggerFactory.getLogger("ConnectionFailureWindowTest")
        val reports = File("build/reports/connection-failure-window").apply { mkdirs() }
        val robot = Robot().apply { autoDelay = 60 }
        try {
            for (language in listOf("en", "zh")) {
                reloadI18n()
                setI18nOverride(null)
                i18nTable = Toml.parseToTomlTable(File("../rwpp-core/src/commonMain/composeResources/files/bundle_$language.toml").readText())
                for (reason in listOf(ConnectionFailureReason.TIMEOUT, ConnectionFailureReason.ROOM_FULL,
                    ConnectionFailureReason.DATA_MISMATCH, ConnectionFailureReason.UNKNOWN)) {
                    for (small in listOf(false, true)) {
                        verify(robot, reports, language, reason, small)
                    }
                }
            }
        } finally {
            stopKoin()
            reloadI18n()
            setI18nOverride(null)
            if (previousLayers == null) System.clearProperty("compose.layers.type")
            else System.setProperty("compose.layers.type", previousLayers)
        }
    }

    private fun verify(robot: Robot, reports: File, language: String, reason: ConnectionFailureReason, small: Boolean) {
        var visible by mutableStateOf(false)
        val failure = ConnectionFailure(reason, details = (1..80).joinToString("\n") {
            "diagnostic $it: java.net.SocketException / custom-unit mismatch / 127.0.0.1:5123"
        })
        lateinit var frame: JFrame
        var owner: JFrame? = null
        val name = "$language-${reason.name.lowercase()}-${if (small) "small" else "normal"}"
        try {
            onEdt {
                val panel = ComposePanel()
                frame = JFrame("RWJS connection failure regression: $name").apply {
                    add(panel)
                    setSize(if (small) 420 else 850, if (small) 420 else 650)
                    setLocationRelativeTo(null)
                }
                owner = frame
                panel.setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            AnimatedAlertDialog(visible, onDismissRequest = { visible = false }) { dismiss ->
                                ConnectionFailureCard(failure, dismiss)
                            }
                        }
                    }
                }
                frame.isVisible = true
                frame.toFront()
                frame.requestFocus()
                visible = true
            }
            await("show details button: $name") { findBounds(frame, readI18n("connectionError.showDetails")) != null }
            screenshot(robot, frame, File(reports, "$name-collapsed.png"))
            clickText(robot, frame, readI18n("connectionError.showDetails"))
            await("expanded details: $name") { findBounds(frame, readI18n("connectionError.hideDetails")) != null }
            screenshot(robot, frame, File(reports, "$name-expanded.png"))
            // 折叠后测试固定确认区；另一半用48dp关闭图标，覆盖两个退出入口。
            clickText(robot, frame, readI18n("connectionError.hideDetails"))
            clickText(robot, frame, readI18n(if (small) "common.close" else "connectionError.ok"))
            await("dialog dismissed: $name") { !visible && frame.ownedWindows.none { it.isVisible } }
        } finally {
            onEdt { owner?.let { it.ownedWindows.forEach(Window::dispose); it.dispose() } }
        }
    }

    private fun screenshot(robot: Robot, frame: JFrame, file: File) {
        robot.waitForIdle()
        lateinit var bounds: Rectangle
        onEdt { bounds = Rectangle(frame.locationOnScreen, frame.size) }
        ImageIO.write(robot.createScreenCapture(bounds), "png", file)
    }

    private fun clickText(robot: Robot, frame: JFrame, text: String) {
        var bounds: Rectangle? = null
        onEdt { bounds = findBounds(frame, text) }
        val target = assertNotNull(bounds, "Native accessible control missing: $text")
        var activated = false
        onEdt {
            fun activate(node: Accessible): Boolean {
                val context = node.accessibleContext ?: return false
                if (context.accessibleName == text || context.accessibleDescription == text) {
                    val action = context.accessibleAction
                    if (action != null && action.accessibleActionCount > 0) return action.doAccessibleAction(0)
                }
                for (index in 0 until context.accessibleChildrenCount) {
                    if (context.getAccessibleChild(index)?.let(::activate) == true) return true
                }
                return false
            }
            activated = frame.ownedWindows.filter { it.isVisible }.any(::activate)
        }
        if (!activated) {
            robot.mouseMove(target.x + target.width / 2, target.y + target.height / 2)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
        robot.waitForIdle()
    }

    private fun findBounds(frame: JFrame, text: String): Rectangle? {
        fun search(node: Accessible): Rectangle? {
            val context = node.accessibleContext ?: return null
            val component = context.accessibleComponent
            val names = listOfNotNull(context.accessibleName, context.accessibleDescription)
            if (names.any { it == text } && component != null && component.isShowing) {
                val size = component.size
                if (size.width > 0 && size.height > 0) {
                    val screen = runCatching { Rectangle(component.locationOnScreen, size) }.getOrNull()
                    if (screen != null) {
                        return screen
                    }
                }
            }
            for (index in 0 until context.accessibleChildrenCount) {
                val child = context.getAccessibleChild(index) ?: continue
                search(child)?.let { return it }
            }
            return null
        }
        for (window in frame.ownedWindows.filter { it.isVisible }) {
            search(window)?.let { return it }
        }
        return search(frame)
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
        fail("Connection failure native window did not reach: $stage")
    }
}
