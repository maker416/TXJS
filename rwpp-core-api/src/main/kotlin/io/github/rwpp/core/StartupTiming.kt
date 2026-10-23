/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.logger

/** 启动路径的单调时钟计时；只记录阶段边界，不改变调用线程或初始化顺序。 */
object StartupTiming {
    @Volatile private var entryNanos = System.nanoTime()

    /** 在平台入口调用，此时日志系统可以尚未初始化。 */
    fun start() {
        entryNanos = System.nanoTime()
    }

    fun mark(stage: String) {
        logger.info("[STARTUP] {}: {} ms since entry", stage, (System.nanoTime() - entryNanos) / 1_000_000)
    }

    inline fun <T> measure(stage: String, action: () -> T): T {
        val started = System.nanoTime()
        var completed = false
        try {
            return action().also { completed = true }
        } finally {
            logger.info("[STARTUP] {}: {} ms, completed={}", stage, (System.nanoTime() - started) / 1_000_000, completed)
        }
    }
}
