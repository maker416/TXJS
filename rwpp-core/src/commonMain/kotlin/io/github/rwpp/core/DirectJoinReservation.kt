/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.net.sync.direct.DirectJoinEndpoint
import io.github.rwpp.net.sync.direct.DirectModLease
import io.github.rwpp.net.sync.direct.DirectModSyncServerException
import io.github.rwpp.net.sync.direct.DirectModSyncTransport
import io.github.rwpp.net.sync.direct.validateLease
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 预约覆盖下载、重载和真实注册；不把 TCP connect 成功当作槽位已交付。 */
class DirectJoinReservation private constructor(
    val endpoint: DirectJoinEndpoint,
    private val transport: DirectModSyncTransport,
    private val scope: String,
    private val token: ByteArray,
    initial: DirectModLease,
    initialExpiresAt: Long,
    private val nowNanos: () -> Long,
    parentScope: CoroutineScope,
) {
    private val closed = AtomicBoolean()
    private val terminalFailure = AtomicReference<Exception?>()
    private val failed = CompletableDeferred<Exception>()
    private val expiresAt = java.util.concurrent.atomic.AtomicLong(initialExpiresAt)
    private val renewal = Mutex()
    private val lifetime = SupervisorJob(parentScope.coroutineContext.job)
    private val heartbeat = CoroutineScope(parentScope.coroutineContext + lifetime).launch {
        var waitMillis = initial.renewAfterMillis
        while (true) {
            delay(waitMillis)
            try {
                val lease = renewNow()
                waitMillis = lease.renewAfterMillis
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 短暂断网可以在当前租期内重试；服务器明确撤销和租期耗尽必须停止本次加入。
                val rejected = generateSequence<Throwable>(error) { it.cause }.take(16)
                    .any { it is DirectModSyncServerException }
                if (rejected || expiresAt.get() - nowNanos() <= 5_000_000_000L) {
                    fail(error)
                    return@launch
                }
                waitMillis = 1_000
            }
        }
    }
    private val expiryWatchdog = CoroutineScope(parentScope.coroutineContext + lifetime).launch {
        while (true) {
            val remaining = expiresAt.get() - nowNanos()
            if (remaining <= 0) {
                fail(IOException("Direct slot reservation expired"))
                return@launch
            }
            // 独立于续租 Socket；慢响应不能让任务越过已经确认的租期继续运行。
            delay((remaining / 1_000_000).coerceAtLeast(1))
        }
    }

    private fun fail(error: Exception) {
        if (terminalFailure.compareAndSet(null, error)) failed.complete(error)
    }

    private fun ensureValid() {
        terminalFailure.get()?.let { throw it }
        check(!closed.get()) { "Direct reservation is closed" }
        if (nowNanos() >= expiresAt.get()) {
            val error = IOException("Direct slot reservation expired")
            fail(error)
            throw error
        }
    }

    /** 心跳失败会取消正在进行的下载或加入，平台重载仍按自身契约安全收尾。 */
    suspend fun <T> protect(block: suspend () -> T): T = coroutineScope {
        ensureValid()
        val operation = async { block().also { ensureValid() } }
        try {
            select {
                operation.onAwait { it }
                failed.onAwait { throw it }
            }
        } finally {
            operation.cancelAndJoin()
        }
    }

    suspend fun renewNow(): DirectModLease = renewal.withLock {
        ensureValid()
        val requestedAt = nowNanos()
        val lease = transport.renew(scope, token)
        validateLease(lease, scope)
        expiresAt.set(requestedAt + lease.ttlMillis * 1_000_000)
        ensureValid()
        lease
    }

    /** 凭证只供原版 preregistration 使用；调用方关闭句柄后不会残留到下一次加入。 */
    suspend fun openCredentials(): AutoCloseable {
        renewNow()
        return io.github.rwpp.net.sync.direct.DirectJoinReservationCredentials.open(endpoint, token)
    }

    suspend fun awaitAdmission() {
        try {
            withTimeout(30_000) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (renewNow().state == "admitted") return@withTimeout
                    delay(300)
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw java.net.SocketTimeoutException("Server did not confirm reserved slot admission")
        }
    }

    /** 取消和失败释放未交付位置；已注册的真实玩家不会被 RELEASE 踢出。 */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            lifetime.cancel()
            heartbeat.cancelAndJoin()
            runCatching { withTimeout(2_000) { transport.release(scope, token) } }
            lifetime.join()
            token.fill(0)
        }
    }

    companion object {
        suspend fun acquire(
            endpoint: DirectJoinEndpoint,
            transport: DirectModSyncTransport,
            scope: String,
            nowNanos: () -> Long = System::nanoTime,
        ): DirectJoinReservation {
            val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
            var handedOff = false
            try {
                var grant: DirectModLease? = null
                var requestedAt = nowNanos()
                repeat(3) { attempt ->
                    if (grant == null) {
                        try {
                            requestedAt = nowNanos()
                            grant = transport.reserve(scope, token)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            // 相同 token 重试创建，响应丢失也只保留一个槽位。
                            if (error is DirectModSyncServerException || attempt == 2) throw error
                            delay(250)
                        }
                    }
                }
                val initial = checkNotNull(grant)
                validateLease(initial, scope)
                if (initial.scope != scope || initial.state != "reserved") throw IOException("Invalid new Direct slot reservation")
                val expiresAt = requestedAt + initial.ttlMillis * 1_000_000
                if (nowNanos() >= expiresAt) throw IOException("Direct slot reservation expired before receipt")
                currentCoroutineContext().ensureActive()
                return DirectJoinReservation(endpoint, transport, scope, token, initial, expiresAt, nowNanos,
                    CoroutineScope(currentCoroutineContext())).also { handedOff = true }
            } finally {
                if (!handedOff) withContext(NonCancellable) {
                    runCatching { withTimeout(2_000) { transport.release(scope, token) } }
                    token.fill(0)
                }
            }
        }
    }
}
