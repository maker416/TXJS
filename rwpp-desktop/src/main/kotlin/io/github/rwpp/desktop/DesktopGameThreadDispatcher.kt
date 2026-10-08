/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/** 排队超时只撤销未开始的任务；绝不在无 GL 上下文的等待线程执行引擎动作。 */
internal class DesktopGameThreadDispatcher(
    private val post: (() -> Unit) -> Unit,
    private val isOwnerThread: () -> Boolean,
    private val startTimeoutMillis: Long = 5_000L,
) {
    private val dispatchLogger = LoggerFactory.getLogger(DesktopGameThreadDispatcher::class.java)

    suspend fun <T> run(action: () -> T): T {
        if (isOwnerThread()) return action()
        val pending = AtomicBoolean(true)
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<T>()
        post {
            if (!pending.compareAndSet(true, false)) return@post
            started.complete(Unit)
            try {
                finished.complete(action())
            } catch (t: Throwable) {
                dispatchLogger.error("[GAME] dispatched engine action failed on thread=${Thread.currentThread().name}", t)
                finished.completeExceptionally(t)
            }
        }
        try {
            if (withTimeoutOrNull(startTimeoutMillis) { started.await() } == null &&
                pending.compareAndSet(true, false)
            ) {
                dispatchLogger.warn("[GAME] engine dispatch timed out before start; queued action cancelled")
                error("PC 游戏线程未响应，已取消操作。请重启客户端后重试。")
            }
        } catch (e: CancellationException) {
            if (!pending.compareAndSet(true, false)) {
                // 已开始的同步引擎操作不能中途解除重载锁。
                withContext(NonCancellable) { runCatching { finished.await() } }
            }
            throw e
        }
        return withContext(NonCancellable) { finished.await() }
    }
}
