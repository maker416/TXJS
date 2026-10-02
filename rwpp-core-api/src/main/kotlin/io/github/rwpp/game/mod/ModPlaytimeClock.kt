/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import io.github.rwpp.net.playtime.ModPlaytimeClient

/** 单调时钟计现实时间。长时间无采样（休眠/后台冻结）不当作持续游玩。 */
class ModPlaytimeClock {
    private var previousNanos: Long? = null
    private var previouslyActive = false
    private var elapsedNanos = 0L
    val elapsedSeconds: Long get() = elapsedNanos / 1_000_000_000L

    fun sample(nowNanos: Long, active: Boolean) {
        previousNanos?.let { previous ->
            val gap = nowNanos - previous
            // 状态变化的那一个采样间隔保守不计；暂停与加载不补记。
            if (previouslyActive && active && gap in 0..5_000_000_000L) {
                elapsedNanos = (elapsedNanos + gap).coerceAtMost(ModPlaytimeClient.MAX_SESSION_SECONDS * 1_000_000_000L)
            }
        }
        previousNanos = nowNanos
        previouslyActive = active
    }
}
