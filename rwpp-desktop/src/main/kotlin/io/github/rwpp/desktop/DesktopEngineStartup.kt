/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import kotlinx.coroutines.CompletableDeferred

// 与引擎类分离，初始化失败导致 GameImpl 类不可用时，退出流程仍能读取此标志。
@Volatile internal var desktopEngineLoaded = false

/** 把游戏线程的启动结果交给加载协程；Error 也必须结束等待，并继续交给线程异常日志。 */
internal class DesktopEngineStartup {
    private val initialized = CompletableDeferred<Unit>()

    fun runEngine(action: () -> Unit) {
        try {
            action()
            initialized.completeExceptionally(IllegalStateException("Game thread stopped before initialization completed"))
        } catch (error: Throwable) {
            initialized.completeExceptionally(error)
            throw error
        }
    }

    fun completeInitialization() {
        initialized.complete(Unit)
    }

    suspend fun awaitInitialization() {
        initialized.await()
    }
}
