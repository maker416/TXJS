/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.StartGameEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.GameRoom
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.logger
import io.github.rwpp.ui.LauncherPage
import io.github.rwpp.ui.UI
import io.github.rwpp.ui.launcherPage
import io.github.rwpp.ui.resetNavigation
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.peanuuutz.tomlkt.Toml
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MatchStartRecoveryTest {
    @BeforeTest fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings() } }) }.koin
        logger = LoggerFactory.getLogger("MatchStartRecoveryTest")
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
        SwingUtilities.invokeAndWait { MatchStartController.gate.reset(); UI.warning = null }
    }

    @AfterTest fun cleanup() {
        SwingUtilities.invokeAndWait {
            MatchStartController.gate.reset()
            resetNavigation(LauncherPage.MainMenu)
        }
        Dispatchers.resetMain()
        stopKoin()
        reloadI18n()
    }

    @Test fun successPresentsAndBroadcastsOnceAndStalePresentationIsIgnored() {
        val count = AtomicInteger()
        val delivered = CountDownLatch(1)
        val listener = GlobalEventChannel.filter(StartGameEvent::class).subscribeAlways { count.incrementAndGet(); delivered.countDown() }
        try {
            var presentations = 0
            SwingUtilities.invokeAndWait {
                val attempt = MatchStartController.gate.beginLoading()!!
                MatchStartController.present(attempt, { presentations++ }, { throw it })
                MatchStartController.present(attempt, { presentations++ }, { throw it })
            }
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(1, count.get())
            assertEquals(1, presentations)
            SwingUtilities.invokeAndWait {
                MatchStartController.gate.reset()
                val old = MatchStartController.gate.beginLoading()!!
                MatchStartController.gate.reset()
                MatchStartController.present(old, { fail("old room was presented") }, { throw it })
            }
        } finally { listener.complete() }
    }

    @Test fun presentationFailureDoesNotCommitSuccess() {
        SwingUtilities.invokeAndWait {
            val attempt = MatchStartController.gate.beginLoading()!!
            var failures = 0
            MatchStartController.present(attempt, { error("window/activity launch failed") }, {
                failures++
                MatchStartController.gate.fail(attempt)
            })
            assertEquals(1, failures)
            assertEquals(io.github.rwpp.game.MatchStartGate.Phase.RECOVERING, MatchStartController.gate.state.value.phase)
        }
    }

    @Test fun openingRoomAfterSavedGameRequestDoesNotInvalidateItsQueuedStart() {
        SwingUtilities.invokeAndWait {
            val attempt = MatchStartController.gate.request()!!
            GameSessionController.onRoomOpened()
            assertTrue(MatchStartController.gate.isCurrent(attempt))
            assertTrue(MatchStartController.gate.sending(attempt))
            assertSame(attempt, MatchStartController.gate.beginLoading())
        }
    }

    @Test fun preparationAndExplicitSendFailureKeepTheRoomAndAllowRetry() {
        val game = proxy<Game> { method, _ -> error("Kept room must not call cleanup: $method") }
        SwingUtilities.invokeAndWait {
            for (sent in listOf(false, true)) {
                resetNavigation(LauncherPage.Room)
                val attempt = MatchStartController.gate.request()!!
                if (sent) MatchStartController.gate.sending(attempt)
                val failure = MatchStartController.gate.fail(attempt, knownUnsent = sent)!!
                MatchStartController.recover(game, attempt, failure, IllegalStateException("bad map file"))
                assertEquals(LauncherPage.Room, launcherPage)
                assertNotNull(UI.warning)
                assertFalse(MatchStartController.gate.busy)
            }
        }
    }

    @Test fun failedLoadWaitsForRoomDisposeAndDisconnectBeforeNewSession() = runBlocking {
        val disposed = CompletableDeferred<Unit>()
        val disconnectEntered = CompletableDeferred<Unit>()
        val disconnectContinuation = CompletableDeferred<Continuation<Unit>>()
        var finished = false
        val room = proxy<GameRoom> { method, args -> when (method) {
            "isSinglePlayerGame" -> false
            "disconnectAndWait" -> {
                @Suppress("UNCHECKED_CAST") val continuation = args!!.last() as Continuation<Unit>
                disconnectContinuation.complete(continuation)
                disconnectEntered.complete(Unit)
                COROUTINE_SUSPENDED
            }
            else -> error("Unexpected room call: $method")
        } }
        val game = proxy<Game> { method, _ -> when (method) {
            "getGameRoom" -> room
            "cancelJoinServer", "onBanUnits" -> null
            else -> error("Unexpected game call: $method")
        } }
        try {
            SwingUtilities.invokeAndWait {
                resetNavigation(LauncherPage.Room)
                GameSessionController.attachRoomView(disposed)
                GameSessionController.closeFailedStart(game, "bad map") { finished = true }
                assertEquals(LauncherPage.Multiplayer, launcherPage)
            }
            assertFalse(disconnectEntered.isCompleted)
            val next = async { GameSessionController.beginSession { assertTrue(finished); "next room" } }
            disposed.complete(Unit)
            withTimeout(5_000) { disconnectEntered.await() }
            assertFalse(next.isCompleted)
            disconnectContinuation.await().resume(Unit)
            assertEquals("next room", withTimeout(5_000) { next.await() })
        } finally {
            SwingUtilities.invokeAndWait { GameSessionController.detachRoomView(disposed) }
        }
    }

    private inline fun <reified T> proxy(crossinline action: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> action(method.name, args) } as T
}
