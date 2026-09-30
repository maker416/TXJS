/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.rwpp.event.AbstractEvent
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.broadcast
import io.github.rwpp.event.events.KeyboardEvent
import io.github.rwpp.event.onDispose
import io.github.rwpp.ui.LauncherOverlayHost
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BackHandlerComposeTest {
    private class RefreshForTest : AbstractEvent()

    private fun sendEsc(): KeyboardEvent {
        val event = runBlocking { withTimeout(3000) { KeyboardEvent(0x1B).broadcast() } }
        SwingUtilities.invokeAndWait { }
        return event
    }

    @Test
    fun recomposeUpdatesCallbackAndEnabledWithoutReregistering() = runDesktopComposeUiTest {
        var version by mutableStateOf(1)
        var enabled by mutableStateOf(true)
        var called = 0
        setContent {
            val capturedVersion = version
            BackHandler(enabled) { called = capturedVersion }
        }
        waitForIdle()
        runOnIdle { version = 2 }
        waitForIdle()
        assertTrue(sendEsc().isIntercepted)
        assertEquals(2, called)
        runOnIdle { enabled = false }
        waitForIdle()
        called = 0
        assertFalse(sendEsc().isIntercepted)
        assertEquals(0, called)
    }

    @Test
    fun accountBackDoesNotExitRoomAndExitingAccountCannotHandleNextBack() = runDesktopComposeUiTest {
        var accountOpen by mutableStateOf(true)
        var roomExits = 0
        var accountExits = 0
        mainClock.autoAdvance = false
        setContent {
            Box {
                BackHandlerScope(enabled = { !accountOpen }) {
                    BackHandler(true) { roomExits++ }
                }
                LauncherOverlayHost(
                    visible = accountOpen,
                    isInteractive = { accountOpen },
                    enter = EnterTransition.None,
                    exit = fadeOut(tween(1000)),
                ) {
                    BackHandler(true) { accountExits++; accountOpen = false }
                }
            }
        }
        waitForIdle()
        sendEsc()
        waitForIdle()
        assertEquals(1, accountExits)
        assertEquals(0, roomExits)
        // 不推进动画时旧账号页仍留在组合中，下一次返回也只能交给房间。
        sendEsc()
        assertEquals(1, accountExits)
        assertEquals(1, roomExits)
    }

    @Test
    fun exitingPageCannotReceivePointerClicks() = runDesktopComposeUiTest {
        var shown by mutableStateOf(true)
        var clicks = 0
        mainClock.autoAdvance = false
        setContent {
            LauncherOverlayHost(
                visible = shown,
                isInteractive = { shown },
                enter = EnterTransition.None,
                exit = fadeOut(tween(1000)),
            ) {
                Box(Modifier.size(100.dp).testTag("outgoing").clickable { clicks++ })
            }
        }
        waitForIdle()
        onNodeWithTag("outgoing").performTouchInput { click() }
        assertEquals(1, clicks)
        runOnIdle { shown = false }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithTag("outgoing").performTouchInput { click() }
        assertEquals(1, clicks)
    }

    @Test
    fun disposingUiUnregistersBackAndRefreshListeners() = runDesktopComposeUiTest {
        var shown by mutableStateOf(true)
        var backs = 0
        val refreshes = AtomicInteger()
        val channel = GlobalEventChannel.filter(RefreshForTest::class)
        setContent {
            if (shown) {
                BackHandler(true) { backs++ }
                channel.onDispose { subscribeAlways { refreshes.incrementAndGet() } }
            }
        }
        waitForIdle()
        sendEsc()
        runBlocking { withTimeout(3000) { RefreshForTest().broadcast() } }
        assertEquals(1, backs)
        assertEquals(1, refreshes.get())
        runOnIdle { shown = false }
        waitForIdle()
        assertFalse(sendEsc().isIntercepted)
        runBlocking { withTimeout(3000) { RefreshForTest().broadcast() } }
        assertEquals(1, backs)
        assertEquals(1, refreshes.get())
    }
}
