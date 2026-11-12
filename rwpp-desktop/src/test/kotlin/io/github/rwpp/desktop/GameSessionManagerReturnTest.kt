/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import io.github.rwpp.logger
import org.junit.Assume.assumeFalse
import org.slf4j.LoggerFactory
import java.awt.CardLayout
import java.awt.Canvas
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameSessionManagerReturnTest {
    @Test
    fun engineReturnKeepsProcessingMessagesWhileEdtShowsTheMenu() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val displayNeedsGameMessage = CountDownLatch(1)
        val gameHandledMessage = CountDownLatch(1)
        val transitionCompleted = CountDownLatch(1)
        val displayReceivedMessage = AtomicBoolean()
        val returnCallbackOnEdt = AtomicBoolean()
        val gameFailure = AtomicReference<Throwable?>()
        val fixture = createFixture()
        val session = GameSessionManager(fixture.switcher, onStart = {}, onReturnToMenu = {
            returnCallbackOnEdt.set(SwingUtilities.isEventDispatchThread())
        })
        val gameThread = Thread({
            try {
                session.returnToMenu()
                // 模拟 Windows 向 LWJGL 线程发消息：只有原版退回回调先返回，游戏线程才能继续泵。
                check(displayNeedsGameMessage.await(3, TimeUnit.SECONDS)) { "EDT did not request a native message" }
                gameHandledMessage.countDown()
            } catch (failure: Throwable) {
                gameFailure.set(failure)
            }
        }, "rwjs-return-regression-game").apply { isDaemon = true }
        try {
            onEdt {
                fixture.switcher.onBeforeSwitch += { target ->
                    if (target == DisplayMode.Menu) {
                        displayNeedsGameMessage.countDown()
                        // 与原生 pShow 的依赖相同；回归为 invokeAndWait 时，此处收不到游戏线程的响应。
                        displayReceivedMessage.set(gameHandledMessage.await(2, TimeUnit.SECONDS))
                    }
                }
                fixture.switcher.onAfterSwitch += { target ->
                    if (target == DisplayMode.Menu) transitionCompleted.countDown()
                }
            }
            gameThread.start()
            assertTrue(transitionCompleted.await(5, TimeUnit.SECONDS), "Menu transition must complete")
            gameThread.join(1000)
            assertTrue(displayReceivedMessage.get(), "Showing the menu must leave the engine free to process native messages")
            assertTrue(returnCallbackOnEdt.get(), "The return callback and display switch must execute together on EDT")
            assertNull(gameFailure.get())
            onEdt { assertEquals(DisplayMode.Menu, fixture.switcher.currentMode.value) }
        } finally {
            displayNeedsGameMessage.countDown()
            gameHandledMessage.countDown()
            gameThread.join(1000)
            onEdt { fixture.frame.dispose() }
        }
    }

    @Test
    fun returnFromEdtCompletesItsCallbackAndMenuSwitchBeforeReturning() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val fixture = createFixture()
        var callbackCompleted = false
        try {
            onEdt {
                val session = GameSessionManager(fixture.switcher, onStart = {}, onReturnToMenu = {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    callbackCompleted = true
                })
                session.returnToMenu()
                assertTrue(callbackCompleted)
                assertEquals(DisplayMode.Menu, fixture.switcher.currentMode.value)
            }
        } finally {
            onEdt { fixture.frame.dispose() }
        }
    }

    @Test
    fun queuedReturnCannotHideANewerContinueOrRunItsOldCleanupCallback() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val fixture = createFixture()
        val returnQueued = CountDownLatch(1)
        val returnCallback = AtomicBoolean()
        val startCallback = AtomicBoolean()
        val gameFailure = AtomicReference<Throwable?>()
        val session = GameSessionManager(fixture.switcher, onStart = { mode ->
            assertEquals(GameStartMode.Continue, mode)
            assertTrue(SwingUtilities.isEventDispatchThread(), "onStart must retain its caller's thread")
            startCallback.set(true)
        }, onReturnToMenu = { returnCallback.set(true) })
        val gameThread = Thread({
            try {
                session.returnToMenu()
                returnQueued.countDown()
            } catch (failure: Throwable) {
                gameFailure.set(failure)
            }
        }, "rwjs-stale-return-regression").apply { isDaemon = true }
        try {
            onEdt {
                // 暂占 EDT，确保旧退回已入队、尚未执行，再由当前 UI 动作发起更新的会话。
                gameThread.start()
                assertTrue(returnQueued.await(3, TimeUnit.SECONDS), "An engine return must enqueue without waiting for EDT")
                session.startGame(GameStartMode.Continue)
                assertTrue(startCallback.get())
                assertFalse(returnCallback.get())
            }
            // invokeAndWait 排在旧 return 之后，明确等待该任务完成过期检查。
            onEdt {
                assertFalse(returnCallback.get(), "A stale return must not clear a newer session")
                assertEquals(DisplayMode.Game, fixture.switcher.currentMode.value)
            }
            assertNull(gameFailure.get())
        } finally {
            gameThread.join(1000)
            onEdt { fixture.frame.dispose() }
        }
    }

    private fun createFixture(): Fixture {
        val result = AtomicReference<Fixture>()
        onEdt {
            logger = LoggerFactory.getLogger(GameSessionManagerReturnTest::class.java)
            val menu = JPanel()
            val canvas = Canvas()
            val layout = CardLayout()
            val host = JPanel(layout).apply {
                add(menu, DisplayMode.Menu.cardName)
                add(canvas, DisplayMode.Game.cardName)
            }
            // 不显示窗口、不申请系统前台；这里只验证线程依赖和真实 DisplaySwitcher 契约。
            val frame = JFrame("RWJS return threading regression").apply { add(host) }
            val switcher = DisplaySwitcher(menu, canvas, host, layout, frame)
            switcher.switchTo(DisplayMode.Game)
            result.set(Fixture(frame, switcher))
        }
        return result.get()
    }

    private data class Fixture(val frame: JFrame, val switcher: DisplaySwitcher)

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
