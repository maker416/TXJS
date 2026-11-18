/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl.inject

import com.corrodinggames.rts.gameFramework.d.c as EffectEngine
import com.corrodinggames.rts.gameFramework.d.e as Effect
import com.corrodinggames.rts.gameFramework.d.h as EffectPriority
import io.github.rwpp.game.ui.EffectPoolGrowth
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.InterruptResult
import org.slf4j.LoggerFactory

@InjectClass(EffectEngine::class)
object EffectLimitInject {
    private class AllocationHint {
        var pool: Array<Effect>? = null
        var lastCount = -1
        var nextSearch = 0
        var endCoversLive = false
    }

    @Volatile
    private var unlimitedHint: AllocationHint? = null
    private var failedEngine: EffectEngine? = null
    private var traversalEngine: EffectEngine? = null
    private var traversalCapacity = 0
    private var pendingEnd = 0
    private val log by lazy { LoggerFactory.getLogger(EffectLimitInject::class.java) }
    private val effectConstructor by lazy {
        Effect::class.java.getDeclaredConstructor(EffectEngine::class.java).apply { isAccessible = true }
    }

    /** Applied with the other effect settings after engine startup, never from settings UI. */
    fun configure(limit: Int) {
        // Original-mode allocations aren't observed. Start a fresh hint on every mode change.
        unlimitedHint = if (limit == Int.MAX_VALUE) AllocationHint() else null
    }

    private fun track(engine: EffectEngine, hint: AllocationHint) {
        if (traversalEngine !== engine) {
            hint.pool = null
            failedEngine = null
            traversalEngine = engine
            traversalCapacity = EffectEngine.f.size
            pendingEnd = 0
        }
    }

    @Inject("a", InjectMode.InsertBefore, "(F)V")
    @Suppress("UNUSED_PARAMETER")
    fun EffectEngine.beforeEffectUpdate(delta: Float): Any {
        val hint = unlimitedHint ?: return Unit
        track(this, hint)
        // The original update caches f. Publish the new range only before it takes that snapshot.
        EffectEngine.g = maxOf(EffectEngine.g, pendingEnd)
        pendingEnd = 0
        traversalCapacity = EffectEngine.f.size
        return Unit
    }

    @Inject("a", InjectMode.InsertBefore, "(Z)V")
    fun EffectEngine.beforeEffectClear(keep: Boolean): Any {
        val hint = unlimitedHint ?: return Unit
        if (!keep) {
            track(this, hint)
            hint.pool = null
            traversalCapacity = EffectEngine.f.size
            pendingEnd = 0
        }
        return Unit
    }

    @Inject("a", InjectMode.InsertBefore,
        "(Lcom/corrodinggames/rts/gameFramework/d/h;)Lcom/corrodinggames/rts/gameFramework/d/e;")
    @Suppress("UNUSED_PARAMETER")
    fun EffectEngine.allocateEffect(priority: EffectPriority?): Any {
        val hint = unlimitedHint ?: return Unit

        track(this, hint)
        var pool = EffectEngine.f
        if (hint.pool !== pool) {
            hint.pool = pool
            hint.lastCount = -1
            hint.endCoversLive = false
        }
        val activeCount = a
        // Only this allocator activates slots in unlimited mode. Death decrements a before emitting
        // children; every successful allocation (including a child) replaces the hint before returning.
        // Between those allocations a can only decrease: equal counts certify the previous full prefix.
        var index = when {
            activeCount == pool.size -> pool.size
            activeCount == hint.lastCount -> hint.nextSearch
            hint.endCoversLive && activeCount == maxOf(EffectEngine.g, pendingEnd) -> activeCount
            else -> 0
        }
        while (index < pool.size && pool[index].o) index++
        if (index == pool.size) {
            // Avoid repeated allocation failures/GC on every emission after exhausting memory.
            if (failedEngine === this) return InterruptResult(null)
            val expanded = EffectPoolGrowth.expand(pool) { effectConstructor.newInstance(this) }
            if (expanded == null) {
                failedEngine = this
                log.warn("[EFFECTS] Cannot grow effect pool beyond {}; reusing existing slots until restart", pool.size)
                return InterruptResult(null)
            }
            EffectEngine.f = expanded
            hint.pool = expanded
            pool = expanded
        }

        val effect = pool[index]
        effect.o = true
        a += 1
        // Child effects may exhaust the pool during its own update. An old array can still be
        // on the stack, so defer slots beyond that snapshot until the next update begins.
        if (index + 1 > traversalCapacity) pendingEnd = maxOf(pendingEnd, index + 1)
        EffectEngine.g = maxOf(EffectEngine.g, minOf(index + 1, traversalCapacity))
        hint.nextSearch = index + 1
        hint.lastCount = a
        // A packed prefix proves the published/deferred end now bounds every live slot. Updates only
        // trim inactive tail slots. Do not assume this on entry from another mode or an unknown pool.
        if (index == activeCount) hint.endCoversLive = true
        // The original caller still resets the effect, assigns priority and applies visibility rules.
        return InterruptResult(effect)
    }
}
