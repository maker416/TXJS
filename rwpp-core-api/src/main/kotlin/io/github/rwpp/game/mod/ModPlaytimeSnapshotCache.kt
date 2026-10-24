/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import java.util.concurrent.atomic.AtomicLong

/** Main 线程读写快照；重载入口只递增原子代次，不跨线程清理引擎引用。 */
class ModPlaytimeSnapshotCache {
    private val revisionCounter = AtomicLong()
    val revision: Long get() = revisionCounter.get()
    private var key: Pair<Long, Long>? = null
    private var snapshot: Snapshot? = null

    data class Snapshot(val mods: List<Mod>, val sources: List<ModPlaytimeSource>)

    fun invalidate() { revisionCounter.incrementAndGet() }

    fun get(generation: Long, capture: () -> List<Mod>): Snapshot {
        val currentKey = generation to revision
        if (key == currentKey) return checkNotNull(snapshot)
        val mods = capture().toList()
        val next = Snapshot(mods, mods.map { ModPlaytimeSource(it.name, it.path) })
        check(currentKey.second == revision) { "Mod sources changed during sampling" }
        snapshot = next
        key = currentKey
        return next
    }

    fun clear() { key = null; snapshot = null }
}

/** 兼容旧 Game 实现：包装对象的名字/路径也只在本局第一次有效采样时读取。 */
class ModPlaytimeSources {
    private var generation: Long? = null
    private var sources = emptyList<ModPlaytimeSource>()
    fun read(state: ModPlaytimeState): List<ModPlaytimeSource> {
        if (!state.reliable) return emptyList()
        if (!state.hasMatch) {
            generation = null
            sources = emptyList()
            return sources
        }
        state.sources?.let { return it }
        if (generation != state.generation) {
            sources = state.mods.map { ModPlaytimeSource(it.name, it.path) }
            generation = state.generation
        }
        return sources
    }
}
