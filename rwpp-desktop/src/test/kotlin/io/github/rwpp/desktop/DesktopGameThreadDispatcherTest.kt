/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopGameThreadDispatcherTest {
    @Test fun timeoutDoesNotRunOnCallerOrLaterConsumeStaleTask() = runBlocking {
        var queued: (() -> Unit)? = null
        var ran = false
        val dispatcher = DesktopGameThreadDispatcher({ queued = it }, { false }, 20)
        assertFailsWith<IllegalStateException> { dispatcher.run { ran = true } }
        assertFalse(ran)
        queued!!()
        assertFalse(ran)
    }

    @Test fun engineFailureReturnsToCallerWithoutEscapingGameCallback() = runBlocking {
        val dispatcher = DesktopGameThreadDispatcher({ it() }, { false })
        val error = assertFailsWith<IllegalArgumentException> {
            dispatcher.run { throw IllegalArgumentException("fixture reload error") }
        }
        assertEquals("fixture reload error", error.message)
    }

    @Test fun cancellationBeforeDispatchPreventsLateReload() = runBlocking {
        var queued: (() -> Unit)? = null
        var ran = false
        val dispatcher = DesktopGameThreadDispatcher({ queued = it }, { false })
        val request = async { dispatcher.run { ran = true } }
        yield()
        request.cancel()
        request.join()
        queued!!()
        assertFalse(ran)
    }

    @Test fun ownerThreadRunsInline() = runBlocking {
        val dispatcher = DesktopGameThreadDispatcher({ error("Must not enqueue") }, { true })
        assertEquals(7, dispatcher.run { 7 })
    }

    @Test fun cancellationAfterStartWaitsForEngineWorkToFinish() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val dispatcher = DesktopGameThreadDispatcher({ callback ->
            Thread(callback, "fixture-game-thread").apply { isDaemon = true }.start()
        }, { false })
        val request = async {
            dispatcher.run {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                finished.complete(Unit)
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            request.cancel()
            yield()
            assertFalse(request.isCompleted)
            release.countDown()
            withTimeout(5_000) { request.join(); finished.await() }
            assertTrue(request.isCancelled)
        } finally {
            release.countDown()
        }
    }
}
