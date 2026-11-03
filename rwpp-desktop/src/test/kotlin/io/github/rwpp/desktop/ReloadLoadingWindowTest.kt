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
import io.github.rwpp.widget.LoadingView
import io.github.rwpp.widget.loadingMessage
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Windows 原生弹层的创建会等待忙碌的 LWJGL 子窗口线程；根重载提示必须复用当前窗口。 */
class ReloadLoadingWindowTest {
    @Test fun reloadWithAnimationsUsesTheLauncherWindow() = verify(inline = true, animations = true)
    @Test fun reloadWithoutAnimationsUsesTheLauncherWindow() = verify(inline = true, animations = false)
    @Test fun ordinaryLoadingKeepsItsNativeWindow() = verify(inline = false, animations = false)

    private fun verify(inline: Boolean, animations: Boolean) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        assumeFalse(GraphicsEnvironment.isHeadless())
        val previousLayers = System.getProperty("compose.layers.type")
        val previousMessage = loadingMessage
        System.setProperty("compose.layers.type", "WINDOW")
        assertTrue(DesktopWindowLayers.install())
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = animations) } }) }.koin
        var visible by mutableStateOf(false)
        val composed = AtomicBoolean()
        val loaded = AtomicBoolean()
        var owner: JFrame? = null
        try {
            onEdt {
                loadingMessage = "Preparing reload"
                val launcher = ComposePanel().apply {
                    setContent {
                        val currentVisible = visible
                        MaterialTheme {
                            Box(Modifier.fillMaxSize()) {
                                if (inline) {
                                    LoadingView(currentVisible, onLoaded = {}, inlineDesktop = true) {
                                        loaded.set(true)
                                        null
                                    }
                                } else {
                                    // 同时编译原签名调用，避免新增重载改变普通调用的分派。
                                    LoadingView(currentVisible, onLoaded = {}) {
                                        loaded.set(true)
                                        null
                                    }
                                }
                            }
                        }
                        SideEffect { composed.set(currentVisible) }
                    }
                }
                owner = JFrame("RWJS reload window regression").apply {
                    add(launcher)
                    setSize(800, 550)
                    setLocationRelativeTo(null)
                    isVisible = true
                }
                visible = true
            }
            await("loading content") { composed.get() && loaded.get() }
            onEdt {
                assertTrue(owner!!.isShowing)
                if (inline) {
                    assertFalse(owner!!.ownedWindows.any { it.isVisible },
                        "根重载提示新建了原生窗口，游戏线程解析期间 pShow 会冻结 EDT")
                } else {
                    assertTrue(owner!!.ownedWindows.any { it.isVisible },
                        "普通或嵌套 LoadingView 必须保留原生窗口")
                }
                loadingMessage = "Reload progress updated"
            }
            val heartbeat = CountDownLatch(1)
            SwingUtilities.invokeLater { heartbeat.countDown() }
            assertTrue(heartbeat.await(2, TimeUnit.SECONDS), "重载提示更新后 EDT 无法处理后续事件")
            onEdt { visible = false }
            await("overlay closed") {
                !composed.get() && owner!!.ownedWindows.none { it.isVisible }
            }
        } finally {
            onEdt { owner?.let { it.ownedWindows.forEach(Window::dispose); it.dispose() } }
            loadingMessage = previousMessage
            stopKoin()
            if (previousLayers == null) System.clearProperty("compose.layers.type")
            else System.setProperty("compose.layers.type", previousLayers)
        }
    }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }

    private fun await(stage: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            var complete = false
            onEdt { complete = condition() }
            if (complete) return
            Thread.sleep(20)
        }
        fail("Reload window did not reach the expected state: $stage")
    }
}
