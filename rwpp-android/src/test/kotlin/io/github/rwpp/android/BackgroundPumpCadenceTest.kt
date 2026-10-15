/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundPumpCadenceTest {
    @Test
    fun twentyHzPumpPreservesOriginalNetworkTimeUnits() {
        val cadence = BackgroundPumpCadence()
        assertEquals(1f, cadence.delta(0, 1f))
        assertEquals(3f, cadence.delta(50_000_000, 0.2f))
        assertEquals(1.2f, cadence.delta(70_000_000, 0.2f), 0.0001f)
        assertEquals(3f, cadence.delta(20_000_000_000, 0.2f))
    }

    @Test
    fun foregroundReturnAndReloadResetTimingAndClampInvalidDeltas() {
        val cadence = BackgroundPumpCadence()
        cadence.delta(0, 1f)
        cadence.reset()
        assertEquals(0.5f, cadence.delta(60_000_000_000, 0.5f))
        assertEquals(0f, cadence.delta(59_000_000_000, 1f))
        cadence.reset()
        assertEquals(0f, cadence.delta(0, Float.NaN))
    }

    @Test
    fun workTimeIsDeductedFromTheBackgroundSleepBudget() {
        val cadence = BackgroundPumpCadence()
        assertEquals(42_000_000L, cadence.remainingNanos(0, 8_000_000))
        assertEquals(0L, cadence.remainingNanos(0, 70_000_000))
    }
}
