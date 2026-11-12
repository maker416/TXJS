/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PageFileLoadingTest {
    @Test
    fun blockedDiskReadLeavesOwnerResponsiveAndPublishesOnlyAfterCompletion() = runBlocking {
        val state = PageFileLoadState<List<String>>()
        val ownerThread = Thread.currentThread()
        val entered = CompletableDeferred<Thread>()
        val releaseDisk = CountDownLatch(1)
        val load = async {
            state.load {
                entered.complete(Thread.currentThread())
                check(releaseDisk.await(5, TimeUnit.SECONDS))
                listOf("map.tmx")
            }
        }
        try {
            assertNotSame(ownerThread, withTimeout(5_000) { entered.await() })
            // The owner can process another UI action while a real worker is blocked in disk work.
            var heartbeat = false
            launch { heartbeat = true }.join()
            assertTrue(heartbeat)
            assertTrue(state.isLoading)
            assertFalse(state.isReady)
            assertNull(state.value)
            releaseDisk.countDown()
            assertEquals(listOf("map.tmx"), withTimeout(5_000) { load.await() })
            assertTrue(state.isReady)
        } finally {
            releaseDisk.countDown()
            load.cancel()
            load.join()
            state.close()
        }
    }

    @Test
    fun slowOldScanCannotReplaceNewerRefresh() = runTest {
        val state = PageFileLoadState<String>(StandardTestDispatcher(testScheduler))
        val oldDiskRead = CompletableDeferred<String>()
        val old = async { state.load { oldDiskRead.await() } }
        runCurrent()
        assertTrue(state.isLoading)

        assertEquals("fresh", state.load { "fresh" })
        assertTrue(state.isReady)
        oldDiskRead.complete("stale")
        assertNull(old.await())
        assertEquals("fresh", state.value)
        assertTrue(state.isReady)
    }

    @Test
    fun cancellingReaderThatIgnoresCancellationDoesNotPublishItsResult() = runTest {
        val state = PageFileLoadState<String>(StandardTestDispatcher(testScheduler))
        state.load { "before import" }
        val diskRead = CompletableDeferred<Unit>()
        val refresh = launch {
            state.load {
                withContext(NonCancellable) { diskRead.await() }
                "cancelled result"
            }
        }
        runCurrent()
        refresh.cancel()
        diskRead.complete(Unit)
        refresh.join()
        assertEquals("before import", state.value)
        assertTrue(state.isReady)
        assertNull(state.failure)
    }

    @Test
    fun disposedPageRejectsLateResultAndDoesNotStartAnotherScan() = runTest {
        val state = PageFileLoadState<String>(StandardTestDispatcher(testScheduler))
        val diskRead = CompletableDeferred<String>()
        val scan = async { state.load { diskRead.await() } }
        runCurrent()
        state.close()
        diskRead.complete("late page data")
        assertNull(scan.await())
        assertNull(state.value)
        var extraReads = 0
        assertNull(state.load { extraReads++; "unexpected" })
        assertEquals(0, extraReads, "Disposed pages must not start disk reads")
    }

    @Test
    fun failedScanCanRetryAndEmptyDirectoryIsReady() = runTest {
        val state = PageFileLoadState<List<String>>(StandardTestDispatcher(testScheduler))
        assertNull(state.load { throw IOException("read failed") })
        assertFalse(state.isLoading)
        assertFalse(state.isReady)
        assertTrue(state.failure is IOException)

        assertEquals(emptyList(), state.load { emptyList() })
        assertTrue(state.isReady)
        assertNull(state.failure)
    }
}
