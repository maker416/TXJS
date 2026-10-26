/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.ui

import java.lang.reflect.InvocationTargetException
import kotlin.test.*

class EffectPoolGrowthTest {
    @Test fun growsGeometricallyWithoutOverflow() {
        assertEquals(64, EffectPoolGrowth.nextCapacity(0))
        assertEquals(96, EffectPoolGrowth.nextCapacity(32))
        assertEquals(525, EffectPoolGrowth.nextCapacity(350))
        assertEquals(750, EffectPoolGrowth.nextCapacity(500))
        assertEquals(Int.MAX_VALUE - 8, EffectPoolGrowth.nextCapacity(Int.MAX_VALUE - 9))
        assertNull(EffectPoolGrowth.nextCapacity(Int.MAX_VALUE - 8))
        assertNull(EffectPoolGrowth.nextCapacity(Int.MAX_VALUE))
    }

    @Test fun preservesExistingObjectsAndFillsEveryNewSlot() {
        val old = Array(350) { Any() }
        val expanded = assertNotNull(EffectPoolGrowth.expand(old) { Any() })
        assertEquals(525, expanded.size)
        old.indices.forEach { assertSame(old[it], expanded[it]) }
        assertEquals(525, expanded.toSet().size)
        assertEquals(old.javaClass, expanded.javaClass)
    }

    @Test fun failedExpansionNeverMutatesThePublishedPool() {
        for (wrapped in listOf(false, true)) {
            val old = Array(2) { Any() }
            val snapshot = old.copyOf()
            var created = 0
            assertNull(EffectPoolGrowth.expand(old) {
                if (++created == 3) {
                    val error = OutOfMemoryError("simulated constructor failure")
                    if (wrapped) throw InvocationTargetException(error) else throw error
                }
                Any()
            })
            old.indices.forEach { assertSame(snapshot[it], old[it]) }
            assertEquals(2, old.size)
        }
    }

    @Test fun unrelatedConstructorFailuresAreNotSwallowed() {
        val error = InvocationTargetException(IllegalStateException("broken constructor"))
        assertSame(error, assertFailsWith<InvocationTargetException> {
            EffectPoolGrowth.expand(arrayOf(Any())) { throw error }
        })
    }
}
