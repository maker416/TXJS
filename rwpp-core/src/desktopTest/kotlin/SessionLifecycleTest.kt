/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.event.AbstractEvent
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.broadcast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleTest {
    private class DisconnectForTest : AbstractEvent()

    @Test
    fun duplicateStartIsIgnoredThroughoutCloseWaitAndSetup() = runTest {
        val lifecycle = SessionLifecycle(StandardTestDispatcher(testScheduler))
        val close = CompletableDeferred<Unit>()
        val setup = CompletableDeferred<Unit>()
        lifecycle.scheduleClose { close.await() }
        var calls = 0
        val first = async { lifecycle.beginSession { calls++; setup.await(); "started" } }
        runCurrent()
        assertEquals(0, calls)
        assertNull(lifecycle.beginSession { error("duplicate while closing") })
        close.complete(Unit)
        runCurrent()
        assertEquals(1, calls)
        assertNull(lifecycle.beginSession { error("duplicate during setup") })
        setup.complete(Unit)
        assertEquals("started", first.await())
    }

    @Test
    fun repeatedCloseDoesNotCancelTheOriginalCleanup() = runTest {
        val lifecycle = SessionLifecycle(StandardTestDispatcher(testScheduler))
        val finishClose = CompletableDeferred<Unit>()
        var cleaned = false
        lifecycle.scheduleClose { finishClose.await(); cleaned = true }
        runCurrent()
        lifecycle.scheduleClose { error("second close must be ignored") }
        val start = async { lifecycle.beginSession { assertTrue(cleaned) } }
        runCurrent()
        assertFalse(start.isCompleted)
        finishClose.complete(Unit)
        start.await()
    }

    @Test
    fun cancellingStartKeepsCloseRunningAndReleasesStartGuard() = runTest {
        val lifecycle = SessionLifecycle(StandardTestDispatcher(testScheduler))
        val finishClose = CompletableDeferred<Unit>()
        var cleaned = false
        lifecycle.scheduleClose { finishClose.await(); cleaned = true }
        val first = async { lifecycle.beginSession { error("cancelled setup") } }
        runCurrent()
        first.cancel()
        runCurrent()
        val second = async { lifecycle.beginSession { assertTrue(cleaned); "second" } }
        runCurrent()
        assertFalse(second.isCompleted)
        finishClose.complete(Unit)
        assertEquals("second", second.await())
    }

    @Test
    fun failedCloseDoesNotPermitSetup() = runTest {
        val lifecycle = SessionLifecycle(StandardTestDispatcher(testScheduler))
        lifecycle.scheduleClose { error("cleanup failed") }
        runCurrent()
        assertFailsWith<IllegalStateException> {
            lifecycle.beginSession { error("must not start") }
        }.also { assertEquals("cleanup failed", it.message) }
    }

    @Test
    fun setupFailureReleasesGuardAndResetsStartingPhase() = runTest {
        val lifecycle = SessionLifecycle(StandardTestDispatcher(testScheduler))
        assertFailsWith<IllegalStateException> { lifecycle.beginSession<Unit> { error("setup failed") } }
        assertEquals(GameSessionController.SessionPhase.IDLE, lifecycle.sessionPhase)
        assertEquals("retry", lifecycle.beginSession { "retry" })
    }

    @Test
    fun startWaitsForDisconnectSubscriberCompletion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val lifecycle = SessionLifecycle(dispatcher)
        val channel = GlobalEventChannel.filter(DisconnectForTest::class)
        val cleanup = CompletableDeferred<Unit>()
        var cleaned = false
        val listener = channel.subscribeAlways(dispatcher) { cleanup.await(); cleaned = true }
        try {
            lifecycle.scheduleClose { DisconnectForTest().broadcast() }
            val start = async { lifecycle.beginSession { assertTrue(cleaned); "ready" } }
            runCurrent()
            assertFalse(start.isCompleted)
            cleanup.complete(Unit)
            assertEquals("ready", start.await())
        } finally {
            channel.unregisterListener(listener)
        }
    }
}
