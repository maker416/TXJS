/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.widget.AnimatedAlertDialog
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 使用真实 WINDOW 弹层和 Direct3D/Skiko；离屏 Compose 测试无法覆盖原生窗口裁剪与 resize。 */
class DesktopWindowLayersTest {
    @Test
    fun queuedViewportResizeCannotReviveDisposedWindow() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        lateinit var owner: JFrame
        lateinit var window: JDialog
        try {
            onEdt {
                val host = JPanel()
                owner = JFrame("RWJS disposed viewport regression").apply {
                    add(host)
                    setSize(400, 300)
                    setLocationRelativeTo(null)
                    isVisible = true
                }
                val content = JPanel()
                window = JDialog(owner).apply {
                    add(content)
                    setSize(100, 100)
                    isVisible = true
                }
                DesktopWindowLayers.syncViewport(window, host, content, Rectangle(0, 0, host.width, host.height))
                window.dispose()
            }
            onEdt { assertFalse(window.isDisplayable, "A queued viewport update must not recreate a disposed peer") }
        } finally {
            onEdt { owner.dispose() }
        }
    }

    @Test
    fun nonFocusableBrowserOverlayKeepsSmallWindowAndLeavesNativeContentUncovered() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        assumeFalse(GraphicsEnvironment.isHeadless())
        val previousLayerType = System.getProperty("compose.layers.type")
        System.setProperty("compose.layers.type", "WINDOW")
        assertTrue(DesktopWindowLayers.install())
        val shown = mutableStateOf(false)
        var frame: JFrame? = null
        lateinit var panel: ComposePanel
        try {
            onEdt {
                panel = ComposePanel().apply { isOpaque = false }
                frame = JFrame("RWJS non-focusable overlay regression").apply {
                    add(panel)
                    setSize(1000, 650)
                    setLocationRelativeTo(null)
                }
                panel.setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            if (shown.value) Popup(properties = PopupProperties(focusable = false)) {
                                Box(Modifier.size(80.dp, 48.dp).background(MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                }
                frame.isVisible = true
                shown.value = true
            }
            val owner = frame!!
            await { visibleLayers(owner).singleOrNull()?.width?.let { it < panel.width / 2 } == true }
            val until = System.nanoTime() + 300_000_000L
            do {
                onEdt {
                    val overlay = visibleLayers(owner).single()
                    val middle = panel.locationOnScreen.apply { translate(panel.width / 2, panel.height / 2) }
                    assertTrue(overlay.width < panel.width / 2 && overlay.height < panel.height / 2)
                    assertFalse(overlay.bounds.contains(middle), "Floating browser controls must not cover the native webpage")
                }
                Thread.sleep(10)
            } while (System.nanoTime() < until)
            onEdt { shown.value = false }
            await { visibleLayers(owner).isEmpty() }
        } finally {
            onEdt { frame?.let { it.ownedWindows.forEach(Window::dispose); it.dispose() } }
            if (previousLayerType == null) System.clearProperty("compose.layers.type")
            else System.setProperty("compose.layers.type", previousLayerType)
        }
    }

    @Test
    fun dialogsAndDropdownsKeepHostViewportAcrossAnimationsContentChangesAndResize() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        assumeFalse(GraphicsEnvironment.isHeadless())
        val previousLayerType = System.getProperty("compose.layers.type")
        System.setProperty("compose.layers.type", "WINDOW")
        assertTrue(DesktopWindowLayers.install())
        assertTrue(DesktopWindowLayers.install(), "Installation must be idempotent")
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = true) } }) }.koin

        val shown = mutableStateOf(false)
        val expanded = mutableStateOf(false)
        val savedGames = mutableStateOf(false)
        val bodyPosition = AtomicReference<String?>()
        val frameRef = AtomicReference<JFrame?>()
        var dismiss: () -> Unit = {}
        lateinit var frame: JFrame
        lateinit var panel: ComposePanel
        try {
            onEdt {
                panel = ComposePanel().apply {
                    isOpaque = false
                    setContent {
                        MaterialTheme {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                AnimatedAlertDialog(shown.value, { shown.value = false }) { close ->
                                    SideEffect { dismiss = close }
                                    Column(
                                        Modifier.fillMaxSize(.95f).background(MaterialTheme.colorScheme.surface)
                                            .onGloballyPositioned { bodyPosition.set("${it.positionInWindow()} ${it.size}") },
                                    ) {
                                        Text(if (savedGames.value) "保存的游戏" else "遭遇战地图")
                                        Box {
                                            TextButton(onClick = { expanded.value = true }) { Text("地图类型") }
                                            DropdownMenu(expanded.value, { expanded.value = false }) {
                                                DropdownMenuItem(text = { Text("遭遇战地图") }, onClick = {})
                                                DropdownMenuItem(text = { Text("自定义地图") }, onClick = {})
                                                DropdownMenuItem(text = { Text("保存的游戏") }, onClick = {})
                                            }
                                        }
                                        if (!savedGames.value) repeat(12) { Text("地图 $it", Modifier.height(30.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
                frame = JFrame("RWJS native window-layer regression").apply {
                    frameRef.set(this)
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    add(panel)
                    setSize(1000, 650)
                    setLocationRelativeTo(null)
                    isVisible = true
                }
                // 与启动器一致：宿主已 attach 后再打开弹窗，保证 Compose 已获得真实 owner。
                shown.value = true
            }
            await { bodyPosition.get() != null && visibleLayers(frame).isNotEmpty() }
            settleAndCheckViewport(frame, panel)
            val initialPosition = bodyPosition.get()
            repeat(6) {
                onEdt { expanded.value = true }
                await { visibleLayers(frame).size == 2 }
                settleAndCheckViewport(frame, panel)
                onEdt {
                    expanded.value = false
                    savedGames.value = !savedGames.value
                }
                await { visibleLayers(frame).size == 1 }
                settleAndCheckViewport(frame, panel)
                assertEquals(initialPosition, bodyPosition.get(), "Content/empty list must not move the dialog")
            }
            onEdt {
                frame.setSize(1100, 700)
                frame.setLocation(frame.x + 15, frame.y + 10)
            }
            await {
                val expected = Rectangle(panel.locationOnScreen, panel.size)
                visibleLayers(frame).all { it.bounds == expected }
            }
            settleAndCheckViewport(frame, panel)
            onEdt { expanded.value = true }
            await { visibleLayers(frame).size == 2 }
            settleAndCheckViewport(frame, panel)
            onEdt { expanded.value = false }
            await { visibleLayers(frame).size == 1 }
            onEdt { dismiss() }
            await { visibleLayers(frame).isEmpty() }
            onEdt { assertTrue(frame.isEnabled) }
        } finally {
            onEdt {
                frameRef.get()?.let { window ->
                    window.ownedWindows.forEach(Window::dispose)
                    window.dispose()
                }
            }
            stopKoin()
            if (previousLayerType == null) System.clearProperty("compose.layers.type")
            else System.setProperty("compose.layers.type", previousLayerType)
        }
    }

    private fun settleAndCheckViewport(frame: JFrame, panel: ComposePanel) {
        // 持续检查整个入场动画，而非仅检查最终静止帧。
        val until = System.nanoTime() + 300_000_000L
        do {
            onEdt {
                val expected = Rectangle(panel.locationOnScreen, panel.size)
                visibleLayers(frame).forEach { assertEquals(expected, it.bounds, "Native layer must match the host viewport") }
            }
            Thread.sleep(10)
        } while (System.nanoTime() < until)
    }

    private fun visibleLayers(frame: JFrame): List<Window> = frame.ownedWindows.filter { it.isVisible }

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        var satisfied = false
        while (!satisfied && System.nanoTime() < until) {
            onEdt { satisfied = condition() }
            if (!satisfied) Thread.sleep(10)
        }
        assertTrue(satisfied, "Native window state did not settle")
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
}
