/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.ui

import java.lang.reflect.InvocationTargetException

/** Builds a complete replacement before the engine publishes it. Existing effects keep their identity. */
object EffectPoolGrowth {
    // JVM array representation limit, not a user-facing effect limit.
    private const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8

    internal fun nextCapacity(current: Int): Int? {
        require(current >= 0)
        if (current >= MAX_ARRAY_SIZE) return null
        return (current.toLong() + maxOf(64L, current.toLong() / 2))
            .coerceAtMost(MAX_ARRAY_SIZE.toLong()).toInt()
    }

    fun <T> expand(pool: Array<T>, create: () -> T): Array<T>? {
        val capacity = nextCapacity(pool.size) ?: return null
        try {
            val expanded = pool.copyOf(capacity)
            for (index in pool.size until capacity) expanded[index] = create()
            @Suppress("UNCHECKED_CAST")
            return expanded as Array<T>
        } catch (_: OutOfMemoryError) {
            return null
        } catch (error: InvocationTargetException) {
            // The protected engine constructor is invoked reflectively.
            if (error.cause is OutOfMemoryError) return null
            throw error
        }
    }
}
