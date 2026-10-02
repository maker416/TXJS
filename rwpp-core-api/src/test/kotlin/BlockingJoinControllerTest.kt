/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.BlockingJoinController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockingJoinControllerTest {
    @Test
    fun cancelledCallerWaitsForLateConnectionAndCleanupBeforeNextJoin() = runBlocking {
        val controller = BlockingJoinController()
        val entered = CompletableDeferred<Unit>()
        val releaseConnection = CountDownLatch(1)
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var nextPrepared = false
        var lateConnected = false
        val first = async {
            controller.connect(
                prepare = {},
                blockingConnect = {
                    entered.complete(Unit)
                    releaseConnection.await()
                    lateConnected = true
                    "old"
                },
                cleanupCancelled = {
                    cleanupEntered.complete(Unit)
                    releaseCleanup.await()
                    lateConnected = false
                },
            )
        }
        try {
            withTimeout(5_000) { entered.await() }
            first.cancel()
            val next = async(start = CoroutineStart.UNDISPATCHED) {
                controller.connect(
                    prepare = { nextPrepared = true },
                    blockingConnect = { "next" },
                    cleanupCancelled = { error("new join should not be cancelled") },
                )
            }
            assertFalse(first.isCompleted)
            assertFalse(nextPrepared)
            releaseConnection.countDown()
            withTimeout(5_000) { cleanupEntered.await() }
            assertTrue(lateConnected)
            assertFalse(first.isCompleted)
            assertFalse(nextPrepared)
            releaseCleanup.complete(Unit)
            withTimeout(5_000) {
                first.join()
                assertEquals("next", next.await())
            }
            assertFalse(lateConnected)
            assertTrue(nextPrepared)
        } finally {
            releaseConnection.countDown()
            releaseCleanup.complete(Unit)
        }
    }

    @Test
    fun explicitCancelKeepsBlockedEngineRunningUntilLateConnectionIsRemoved() = runBlocking {
        val controller = BlockingJoinController()
        val entered = CompletableDeferred<Unit>()
        val releaseConnection = CountDownLatch(1)
        var cleaned = false
        var engineInterrupted = false
        val connection = async {
            controller.connect(
                prepare = {},
                blockingConnect = {
                    entered.complete(Unit)
                    try {
                        releaseConnection.await()
                    } catch (e: InterruptedException) {
                        engineInterrupted = true
                        throw e
                    }
                    "connected"
                },
                cleanupCancelled = { cleaned = true },
            )
        }
        try {
            withTimeout(5_000) { entered.await() }
            controller.cancel()
            assertFalse(cleaned)
            assertFalse(connection.isCompleted)
            releaseConnection.countDown()
            withTimeout(5_000) { connection.join() }
            assertTrue(connection.isCancelled)
            assertTrue(cleaned)
            assertFalse(engineInterrupted)
        } finally {
            releaseConnection.countDown()
        }
    }

    @Test
    fun cancelDuringPreparationIsRetainedBeforeBlockingConnectionStarts() = runBlocking {
        val controller = BlockingJoinController()
        val preparing = CompletableDeferred<Unit>()
        val releasePreparation = CountDownLatch(1)
        var connected = false
        var cleaned = false
        val connection = async(Dispatchers.Default) {
            controller.connect(
                prepare = {
                    preparing.complete(Unit)
                    releasePreparation.await()
                },
                blockingConnect = { connected = true },
                cleanupCancelled = { cleaned = true },
            )
        }
        try {
            withTimeout(5_000) { preparing.await() }
            controller.cancel()
            releasePreparation.countDown()
            withTimeout(5_000) { connection.join() }
            assertTrue(connection.isCancelled)
            assertFalse(connected)
            assertTrue(cleaned)
        } finally {
            releasePreparation.countDown()
        }
    }

    @Test
    fun explicitCancelAfterIoCompletesStillCleansBeforeDeliveringTheResult() = runBlocking {
        val controller = BlockingJoinController()
        val dispatcher = HeldDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val releaseConnection = CountDownLatch(1)
        var cleaned = false
        val connection = scope.async {
            controller.connect(
                prepare = {},
                blockingConnect = { releaseConnection.await(); "connected" },
                cleanupCancelled = { cleaned = true },
            )
        }
        try {
            dispatcher.runNext()
            releaseConnection.countDown()
            // await 恢复排队，说明 IO Deferred 已完成；Job.cancel 已无法取消其结果。
            val resume = dispatcher.takeNext()
            controller.cancel()
            resume.run()
            withTimeout(5_000) { connection.join() }
            assertTrue(connection.isCancelled)
            assertTrue(cleaned)
        } finally {
            releaseConnection.countDown()
            scope.cancel()
        }
    }

    @Test
    fun callerCancelAfterIoCompletesStillCleansBeforeReturning() = runBlocking {
        val controller = BlockingJoinController()
        val dispatcher = HeldDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val releaseConnection = CountDownLatch(1)
        var cleaned = false
        val connection = scope.async {
            controller.connect(
                prepare = {},
                blockingConnect = { releaseConnection.await(); "connected" },
                cleanupCancelled = { cleaned = true },
            )
        }
        try {
            dispatcher.runNext()
            releaseConnection.countDown()
            val resume = dispatcher.takeNext()
            connection.cancel()
            resume.run()
            withTimeout(5_000) { connection.join() }
            assertTrue(connection.isCancelled)
            assertTrue(cleaned)
            assertEquals("next", controller.connect({}, { "next" }, { error("fresh join") }))
        } finally {
            releaseConnection.countDown()
            scope.cancel()
        }
    }

    private class HeldDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.put(block) }
        fun takeNext(): Runnable = checkNotNull(queue.poll(5, TimeUnit.SECONDS)) { "恢复任务未进入调度队列" }
        fun runNext() { takeNext().run() }
    }
}
