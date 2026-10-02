/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 串行执行不可中断的引擎连接。取消后等待阻塞调用真正结束，再拆除迟到的连接，
 * 才允许调用方结束会话开始屏障或开始下一次连接；不会中断引擎线程。
 */
class BlockingJoinController {
    private val mutex = Mutex()
    @Volatile private var activeAttempt: Attempt? = null

    private class Attempt {
        private val lock = Any()
        private var cancelled = false
        private var delivered = false
        private var job: Deferred<*>? = null

        fun attach(job: Deferred<*>) = synchronized(lock) {
            this.job = job
            if (cancelled) job.cancel()
        }

        fun cancel() {
            synchronized(lock) {
                if (delivered) return
                cancelled = true
                job?.cancel()
            }
        }

        fun commitSuccess() = synchronized(lock) {
            // Deferred 可能已完成，cancel() 此时不会改变它的状态，仍须检查请求本身。
            if (cancelled) throw CancellationException("Join cancelled before result delivery")
            delivered = true
        }
    }

    suspend fun <T> connect(
        prepare: () -> Unit,
        blockingConnect: () -> T,
        cleanupCancelled: suspend () -> Unit,
    ): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val attempt = Attempt()
        var job: Deferred<T>? = null
        var preparationStarted = false
        activeAttempt = attempt
        try {
            val result = coroutineScope {
                // 保留准备引擎的调用方线程；取消准备期间的请求也会被后续 attach 保留。
                preparationStarted = true
                prepare()
                val connection = async(Dispatchers.IO, start = CoroutineStart.LAZY) { blockingConnect() }
                job = connection
                attempt.attach(connection)
                connection.start()
                val value = connection.await()
                attempt.commitSuccess()
                value
            }
            // coroutineScope 的返回也可能因父协程取消而失败，必须处于同一清理保护内。
            currentCoroutineContext().ensureActive()
            result
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // Job.cancel 不会停止已进入 Java 的 Socket 连接，join 必须等其实际完成。
                job?.join()
                if (preparationStarted) cleanupCancelled()
            }
            throw e
        } finally {
            activeAttempt = null
        }
    }

    fun cancel() {
        activeAttempt?.cancel()
    }
}
