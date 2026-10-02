/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JoinEntryJobsTest {
    @Test
    fun cancellingDuringRetryDelayPreventsConnection() = runTest {
        val jobs = JoinEntryJobs(backgroundScope, StandardTestDispatcher(testScheduler))
        val epoch = jobs.invalidate()
        var connections = 0
        assertTrue(jobs.scheduleRetry(epoch, 1_000L) { connections++ })
        runCurrent()
        advanceTimeBy(500L)
        jobs.invalidate()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(0, connections)
        assertFalse(jobs.scheduleRetry(epoch, 1L) { error("old session") })
    }

    @Test
    fun oldGracePeriodCannotCloseNewSessionWindow() = runTest {
        val jobs = JoinEntryJobs(backgroundScope, StandardTestDispatcher(testScheduler))
        var expired = false
        val old = jobs.invalidate()
        jobs.scheduleExpiry(old, 4_000L) { expired = true }
        runCurrent()
        advanceTimeBy(1_000L)
        val next = jobs.invalidate()
        jobs.scheduleExpiry(next, 8_000L) { expired = true }
        runCurrent()
        advanceTimeBy(4_000L)
        runCurrent()
        assertFalse(expired)
        assertFalse(jobs.isCurrent(old))
        assertTrue(jobs.isCurrent(next))
        advanceTimeBy(4_000L)
        runCurrent()
        assertTrue(expired)
    }

    @Test
    fun nextSessionWaitsForCancelledConnectionCleanup() = runTest {
        val jobs = JoinEntryJobs(backgroundScope, StandardTestDispatcher(testScheduler))
        val finishCleanup = CompletableDeferred<Unit>()
        var cleaned = false
        val epoch = jobs.invalidate()
        jobs.scheduleRetry(epoch, 1L) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    finishCleanup.await()
                    cleaned = true
                }
            }
        }
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(jobs.hasRunningRetry(), "连接执行期间也须保留进房 Presence")
        jobs.invalidate()
        assertFalse(jobs.hasRunningRetry())
        val barrier = async { jobs.awaitCleanup() }
        runCurrent()
        assertFalse(barrier.isCompleted)
        finishCleanup.complete(Unit)
        runCurrent()
        barrier.await()
        assertTrue(cleaned)
    }

    @Test
    fun cancellationTracksBothAlreadyRunningAndDelayedRetries() = runTest {
        val jobs = JoinEntryJobs(backgroundScope, StandardTestDispatcher(testScheduler))
        val epoch = jobs.invalidate()
        var cancelled = false
        var lateConnection = false
        jobs.scheduleRetry(epoch, 1L) {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        runCurrent()
        advanceTimeBy(1L)
        runCurrent()
        jobs.scheduleRetry(epoch, 1_000L) { lateConnection = true }
        jobs.invalidate()
        advanceTimeBy(1_000L)
        runCurrent()
        jobs.awaitCleanup()
        assertTrue(cancelled)
        assertFalse(lateConnection)
    }
}
