/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LoadingViewTest {
    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = false) } }) }.koin
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
        loadingMessage = ""
    }

    @AfterTest
    fun tearDown() {
        loadingMessage = ""
        Dispatchers.resetMain()
        stopKoin()
    }

    @Test
    fun externallyControlledReloadKeepsItsMessageAndCannotBeDismissed() = runDesktopComposeUiTest {
        var visible by mutableStateOf(true)
        val loaded = AtomicBoolean()
        var dismissals = 0
        var completions = 0
        setContent {
            KoinContext(appKoin) {
                MaterialTheme {
                    LoadingView(
                        visible, onLoaded = { dismissals++ },
                        onLoadingFinished = { completions++; visible = false },
                    ) {
                        message("Cancelling mod reload")
                        loaded.set(true)
                        null
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 3_000) { loaded.get() }
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithText("Cancelling mod reload").assertIsDisplayed()
        onAllNodes(hasClickAction()).assertCountEquals(0)
        assertEquals(0, dismissals)
        assertEquals(0, completions)
        runOnIdle { visible = false }
        onNodeWithText("Cancelling mod reload").assertDoesNotExist()
    }

    @Test
    fun failedConnectionKeepsTheReasonUntilUserDismissesIt() = runDesktopComposeUiTest {
        var visible by mutableStateOf(true)
        var dismissals = 0
        var completions = 0
        setContent {
            KoinContext(appKoin) {
                MaterialTheme {
                    LoadingView(
                        visible, onLoaded = { dismissals++; visible = false },
                        onLoadingFinished = { completions++; visible = false },
                    ) {
                        message("Connection refused")
                        false
                    }
                }
            }
        }
        // 失败态的关闭按钮只在 loadContent 已返回 false 并处理完后出现。
        waitUntil(timeoutMillis = 3_000) { onAllNodes(hasClickAction()).fetchSemanticsNodes().size == 1 }
        onNodeWithText("Connection refused").assertIsDisplayed()
        assertEquals(0, completions)
        onNode(hasClickAction()).performClick()
        waitForIdle()
        assertEquals(1, dismissals)
        onNodeWithText("Connection refused").assertDoesNotExist()
    }

    @Test
    fun successfulJoinClearsTheOverlayWithoutCancellingTheConnection() = runDesktopComposeUiTest {
        var visible by mutableStateOf(true)
        var dismissals = 0
        var completions = 0
        setContent {
            KoinContext(appKoin) {
                MaterialTheme {
                    LoadingView(
                        visible, onLoaded = { dismissals++; visible = false },
                        cancellable = true,
                        onLoadingFinished = { completions++; visible = false },
                    ) {
                        message("Connecting")
                        true
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 3_000) { completions == 1 }
        waitForIdle()
        assertEquals(0, dismissals)
        assertEquals("", loadingMessage)
        onAllNodes(hasClickAction()).assertCountEquals(0)
        onNodeWithText("Connecting").assertDoesNotExist()
    }
}
