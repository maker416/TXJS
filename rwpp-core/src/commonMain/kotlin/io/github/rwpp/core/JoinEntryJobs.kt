/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 每次进房持有自己的重试与宽限期任务；取消后旧任务不得触碰下一次进房。 */
internal class JoinEntryJobs(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private var epoch = 0
    private val retryJobs = mutableListOf<Job>()
    private var expiryJob: Job? = null
    private var cleanupJob: Job? = null

    fun currentEpoch(): Int = synchronized(lock) { epoch }
    fun isCurrent(expected: Int): Boolean = synchronized(lock) { epoch == expected }
    fun hasRunningRetry(): Boolean = synchronized(lock) { retryJobs.any { it.isActive } }

    fun invalidate(): Int = synchronized(lock) {
        epoch++
        expiryJob?.cancel()
        expiryJob = null
        val stopped = retryJobs.toList()
        stopped.forEach { it.cancel() }
        retryJobs.clear()
        if (stopped.isNotEmpty()) {
            val previous = cleanupJob
            cleanupJob = scope.launch(dispatcher) {
                previous?.join()
                stopped.forEach { it.join() }
            }
        }
        epoch
    }

    suspend fun awaitCleanup() {
        val pending = synchronized(lock) { cleanupJob }
        pending?.join()
    }

    fun scheduleRetry(expected: Int, delayMs: Long, action: suspend () -> Unit): Boolean = synchronized(lock) {
        if (epoch != expected) return false
        retryJobs.removeAll { it.isCompleted }
        expiryJob?.cancel()
        expiryJob = null
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            delay(delayMs)
            currentCoroutineContext().ensureActive()
            if (isCurrent(expected)) action()
        }
        retryJobs += job
        job.start()
        true
    }

    fun scheduleExpiry(expected: Int, delayMs: Long, action: () -> Unit) = synchronized(lock) {
        if (epoch != expected) return
        expiryJob?.cancel()
        expiryJob = scope.launch(dispatcher) {
            delay(delayMs)
            synchronized(lock) { if (epoch == expected) action() }
        }
    }
}
