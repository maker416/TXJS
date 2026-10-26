/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl.inject

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
    @Volatile
    private var unlimited = false
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
        unlimited = limit == Int.MAX_VALUE
    }

    private fun track(engine: EffectEngine) {
        if (traversalEngine !== engine) {
            failedEngine = null
            traversalEngine = engine
            traversalCapacity = EffectEngine.f.size
            pendingEnd = 0
        }
    }

    @Inject("a", InjectMode.InsertBefore, "(F)V")
    @Suppress("UNUSED_PARAMETER")
    fun EffectEngine.beforeEffectUpdate(delta: Float): Any {
        if (unlimited) {
            track(this)
            // The original update caches f. Publish the new range only before it takes that snapshot.
            EffectEngine.g = maxOf(EffectEngine.g, pendingEnd)
            pendingEnd = 0
            traversalCapacity = EffectEngine.f.size
        }
        return Unit
    }

    @Inject("a", InjectMode.InsertBefore, "(Z)V")
    fun EffectEngine.beforeEffectClear(keep: Boolean): Any {
        if (unlimited && !keep) {
            track(this)
            traversalCapacity = EffectEngine.f.size
            pendingEnd = 0
        }
        return Unit
    }

    @Inject("a", InjectMode.InsertBefore,
        "(Lcom/corrodinggames/rts/gameFramework/d/h;)Lcom/corrodinggames/rts/gameFramework/d/e;")
    @Suppress("UNUSED_PARAMETER")
    fun EffectEngine.allocateEffect(priority: EffectPriority?): Any {
        if (!unlimited) return Unit

        track(this)
        var pool = EffectEngine.f
        var index = pool.indexOfFirst { !it.o }
        if (index == -1) {
            // Avoid repeated allocation failures/GC on every emission after exhausting memory.
            if (failedEngine === this) return InterruptResult(null)
            val expanded = EffectPoolGrowth.expand(pool) { effectConstructor.newInstance(this) }
            if (expanded == null) {
                failedEngine = this
                log.warn("[EFFECTS] Cannot grow effect pool beyond {}; reusing existing slots until restart", pool.size)
                return InterruptResult(null)
            }
            index = pool.size
            EffectEngine.f = expanded
            pool = expanded
        }

        val effect = pool[index]
        effect.o = true
        a += 1
        // Child effects may exhaust the pool during its own update. An old array can still be
        // on the stack, so defer slots beyond that snapshot until the next update begins.
        if (index + 1 > traversalCapacity) pendingEnd = maxOf(pendingEnd, index + 1)
        EffectEngine.g = maxOf(EffectEngine.g, minOf(index + 1, traversalCapacity))
        // The original caller still resets the effect, assigns priority and applies visibility rules.
        return InterruptResult(effect)
    }
}
