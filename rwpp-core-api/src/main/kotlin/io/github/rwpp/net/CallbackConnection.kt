/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One connection attempt. The completion callback never reads a not-yet-published handle. */
class CallbackConnection<C : Any>(private val close: (C) -> Unit) {
    private val completed = CompletableDeferred<Unit>()
    private val handle = AtomicReference<C?>()
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
        completed.cancel()
        closeIfCancelled()
    }

    private fun closeIfCancelled() {
        val value = handle.get() ?: return
        if (cancelled.get() && closed.compareAndSet(false, true)) close(value)
    }

    suspend fun <R> connect(start: (() -> Unit) -> C, use: (C) -> R): R {
        try {
            val value = start { completed.complete(Unit) }
            handle.set(value)
            closeIfCancelled()
            completed.await()
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) throw CancellationException("Join cancelled")
            return use(value)
        } catch (error: CancellationException) {
            cancel()
            throw error
        }
    }
}
