/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.KeyboardEvent
import io.github.rwpp.ui.MapItem
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.context.startKoin
import org.koin.compose.KoinContext
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DesktopUiStabilityTest {
    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = true) } }) }.koin
    }

    @AfterTest
    fun cleanup() { stopKoin() }

    @Composable
    private fun TestContent(content: @Composable () -> Unit) {
        KoinContext(appKoin) { MaterialTheme(content = content) }
    }

    @Test
    fun closingParentClosesDropdownAndRepeatedDismissDoesNotRestartExit() = runDesktopComposeUiTest {
        var shown by mutableStateOf(true)
        var dismiss: () -> Unit = {}
        var dismissals = 0
        var clicks = 0
        setContent {
            TestContent {
                Text("room", Modifier.clickable { clicks++ })
                AnimatedAlertDialog(shown, { dismissals++; shown = false }) { close ->
                    SideEffect { dismiss = close }
                    Column {
                        LargeDropdownMenu(
                            modifier = Modifier.testTag("dropdown"),
                            label = "type", items = listOf("first", "second"), selectedIndex = 0,
                            onItemSelected = { _, _ -> },
                        )
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("dropdown").performTouchInput { click() }
        onNodeWithText("second").assertIsDisplayed()
        mainClock.autoAdvance = false
        runOnIdle { dismiss(); dismiss() }
        mainClock.advanceTimeBy(200)
        waitForIdle()
        onNodeWithText("second").assertDoesNotExist()
        runOnIdle { dismiss() }
        mainClock.advanceTimeBy(350)
        waitForIdle()
        assertEquals(1, dismissals)
        onNodeWithText("room").performTouchInput { click() }
        assertEquals(1, clicks)
    }

    @Test
    fun closingDuringEntryCannotReopenDialog() = runDesktopComposeUiTest {
        mainClock.autoAdvance = false
        var shown by mutableStateOf(true)
        var dismiss: () -> Unit = {}
        var calls = 0
        setContent {
            TestContent {
                AnimatedAlertDialog(shown, { calls++; shown = false }) { close ->
                    SideEffect { dismiss = close }
                    Text("dialog")
                }
            }
        }
        mainClock.advanceTimeBy(32)
        waitForIdle()
        runOnIdle { dismiss(); dismiss() }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        assertEquals(1, calls)
        onNodeWithText("dialog").assertDoesNotExist()
    }

    @Test
    fun previewAndLongNameCannotChangeMapRowHeight() = runDesktopComposeUiTest(width = 800, height = 600) {
        var name by mutableStateOf("short")
        var showImage by mutableStateOf(false)
        setContent {
            TestContent {
                LazyVerticalGrid(columns = GridCells.Fixed(2)) {
                    item {
                        Box(Modifier.testTag("card")) { MapItem(name, null, showImage) {} }
                    }
                    item { MapItem("other", null, false) {} }
                }
            }
        }
        val before = onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        runOnIdle { name = "a very long map name ".repeat(20); showImage = true }
        waitForIdle()
        assertEquals(before, onNodeWithTag("card").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun keyboardSubscriberCanReturnToEdtWithoutBlockingInput() = runDesktopComposeUiTest {
        val received = AtomicInteger()
        val edt = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        }
        val channel = GlobalEventChannel.filter(KeyboardEvent::class)
        val listener = channel.subscribeAlways(edt) { if (it.keyCode == Key.A.keyCode.toInt()) received.incrementAndGet() }
        try {
            setContent {
                val focus = remember { FocusRequester() }
                val scope = rememberCoroutineScope()
                Box(Modifier.size(100.dp).testTag("input").launcherKeyboardEvents(scope).focusRequester(focus).focusable())
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
            onNodeWithTag("input").performKeyInput { pressKey(Key.A) }
            waitUntil(timeoutMillis = 3000) { received.get() == 1 }
        } finally {
            channel.unregisterListener(listener)
        }
    }
}
