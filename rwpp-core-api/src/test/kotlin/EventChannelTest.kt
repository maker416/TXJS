/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.event

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EventChannelTest {
    private class TestEvent : AbstractEvent()

    @Test
    fun interceptionStopsTheNextListenerAtTheSamePriority() = runTest {
        val channel = EventChannel<TestEvent>(backgroundScope)
        val calls = mutableListOf<String>()
        channel.subscribeAlways { calls.add("first"); it.intercept() }
        channel.subscribeAlways { calls.add("second") }
        channel.subscribeAlways(priority = EventPriority.LOW) { calls.add("low") }
        runCurrent()
        val event = TestEvent()
        channel._events.emit(event)
        event.job.join()
        assertEquals(listOf("first"), calls)
    }

    @Test
    fun listenerFailureDoesNotStrandBroadcastOrSkipOtherCleanup() = runTest {
        val channel = EventChannel<TestEvent>(backgroundScope)
        val errors = mutableListOf<Throwable>()
        channel.errorHandler = { errors.add(it) }
        channel.subscribeAlways { error("failed listener") }
        var cleaned = false
        channel.subscribeAlways(priority = EventPriority.MONITOR) { cleaned = true }
        runCurrent()
        val event = TestEvent()
        val broadcast = async { channel._events.emit(event); event.job.join() }
        broadcast.await()
        assertTrue(cleaned)
        assertEquals("failed listener", errors.single().message)
    }

    @Test
    fun removedAndCompletedListenersDoNotReceiveEvents() = runTest {
        val channel = EventChannel<TestEvent>(backgroundScope)
        var calls = 0
        val removed = channel.subscribeAlways { calls++ }
        val completed = channel.subscribeAlways { calls++ }
        channel.unregisterListener(removed)
        completed.complete()
        runCurrent()
        val event = TestEvent()
        channel._events.emit(event)
        event.job.join()
        assertEquals(0, calls)
    }
}
