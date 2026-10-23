/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.*
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

class MatchStartGateTest {
    @Test fun competingRequestsAndRepeatedCallbacksStartOnlyOnce() {
        val gate = MatchStartGate()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val start = CountDownLatch(1)
            val results = (1..20).map { executor.submit<MatchStartGate.Attempt?> { start.await(); gate.request() } }
            start.countDown()
            val attempt = results.mapNotNull { it.get() }.single()
            assertNull(gate.beginLoading(), "准备阶段不能接受无关回调")
            assertTrue(gate.sending(attempt))
            assertSame(attempt, gate.beginLoading())
            assertNull(gate.beginLoading())
            assertTrue(gate.commit(attempt))
            assertFalse(gate.commit(attempt))
            assertNull(gate.request())
            assertFalse(gate.busy)
        } finally { executor.shutdownNow() }
    }

    @Test fun onlyPreparationOrExplicitUnsentFailureKeepsRoom() {
        for (phase in listOf("prepare", "unsent", "unknown_send", "load")) {
            val gate = MatchStartGate()
            val attempt = gate.request()!!
            if (phase != "prepare") gate.sending(attempt)
            if (phase == "load") gate.beginLoading()
            val failure = gate.fail(attempt, knownUnsent = phase == "unsent")!!
            assertEquals(phase == "prepare" || phase == "unsent", failure.keepRoom, phase)
            assertTrue(gate.busy)
            assertNull(gate.request(), "恢复未结束不能重试")
            assertNull(gate.fail(attempt), "重复失败不能重复清理")
            gate.finishRecovery(attempt)
            assertNotNull(gate.request())
        }
    }

    @Test fun remoteStartCanLoadWithoutLocalHostRequest() {
        val gate = MatchStartGate()
        val attempt = gate.beginLoading()!!
        assertTrue(gate.busy)
        assertFalse(gate.fail(attempt, knownUnsent = true)!!.keepRoom)
    }

    @Test fun disconnectAndOldCompletionCannotAffectNextRoom() {
        val gate = MatchStartGate()
        val previous = gate.beginLoading()!!
        gate.reset()
        val next = gate.request()!!
        assertFalse(gate.commit(previous))
        assertNull(gate.fail(previous))
        gate.finishRecovery(previous)
        gate.runLoading(previous, { fail("stale task executed") }, { fail("stale presentation") }, { fail("stale failure") })
        assertTrue(gate.isCurrent(next))
    }

    @Test fun disconnectDuringLoadDoesNotPresentOrCommit() {
        val gate = MatchStartGate()
        val attempt = gate.beginLoading()!!
        gate.runLoading(attempt, { gate.reset() }, { fail("presented cancelled map") }, { throw it })
        assertEquals(MatchStartGate.Phase.IDLE, gate.state.value.phase)
    }

    @Test fun mapAndPresentationExceptionsAreDeliveredWithoutKillingCaller() {
        for (presentationFailure in listOf(false, true)) {
            val gate = MatchStartGate()
            val attempt = gate.beginLoading()!!
            var recovered = false
            gate.runLoading(attempt, {
                if (!presentationFailure) throw NumberFormatException("bad map width")
            }, { throw IllegalStateException("presentation failed") }, {
                recovered = true
                assertFalse(gate.fail(attempt)!!.keepRoom)
            })
            assertTrue(recovered)
        }
    }

    @Test fun reflectedMemoryExhaustionRequiresClosingEvenBeforeSending() {
        val oom = OutOfMemoryError("injected")
        assertSame(oom, recoverableStartCause(InvocationTargetException(InvocationTargetException(oom))))
        val gate = MatchStartGate()
        assertFalse(gate.fail(gate.request()!!, memoryExhausted = true)!!.keepRoom)
        GameStartSafety.markMemoryExhausted()
        gate.reset()
        assertTrue(GameStartSafety.requiresRestart)
        assertFailsWith<IllegalStateException> { GameStartSafety.requireCanReload() }
    }

    @Test fun fatalVmAndLinkageErrorsAreNotTreatedAsBadMaps() {
        for (error in listOf(StackOverflowError(), LinkageError(), ThreadDeath())) {
            assertSame(error, assertFails { recoverableStartCause(InvocationTargetException(error)) })
        }
    }
}
