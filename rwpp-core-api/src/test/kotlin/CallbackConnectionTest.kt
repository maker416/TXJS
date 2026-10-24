/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
import io.github.rwpp.net.CallbackConnection
import kotlinx.coroutines.*
import kotlin.test.*

class CallbackConnectionTest {
    @Test fun callbackBeforeStartReturnsIsSafe() = runBlocking {
        val attempt = CallbackConnection<String> { error("Successful connection must stay open") }
        assertEquals("ready", attempt.connect(start = { done -> done(); "ready" }, use = { it }))
    }

    @Test fun cancelBeforeHandlePublicationClosesLateHandle() = runBlocking {
        val closed = mutableListOf<String>()
        val attempt = CallbackConnection<String> { closed += it }
        assertFailsWith<CancellationException> {
            attempt.connect(start = { done -> attempt.cancel(); done(); "late" }, use = { error("must not attach") })
        }
        assertEquals(listOf("late"), closed)
    }

    @Test fun lateCallbackCannotCompleteAnotherAttempt() = runBlocking {
        var callback: (() -> Unit)? = null
        val ready = CompletableDeferred<Unit>()
        val closed = mutableListOf<String>()
        val old = CallbackConnection<String> { closed += it }
        val job = launch {
            old.connect(start = { done -> callback = done; ready.complete(Unit); "old" }, use = { error("cancelled") })
        }
        ready.await()
        job.cancelAndJoin()
        callback!!.invoke()
        val next = CallbackConnection<String> { closed += it }
        assertEquals("new", next.connect(start = { done -> done(); "new" }, use = { it }))
        assertEquals(listOf("old"), closed)
    }
}
