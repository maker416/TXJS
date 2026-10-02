/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.ModPlaytimeClock
import kotlin.test.Test
import kotlin.test.assertEquals

class ModPlaytimeClockTest {
    @Test fun countsRealSecondsAndRetainsFractionAcrossPauseWithoutCountingPause() {
        val clock = ModPlaytimeClock()
        clock.sample(0, true)
        clock.sample(1_500_000_000, true)
        assertEquals(1L, clock.elapsedSeconds)
        clock.sample(2_000_000_000, false)
        clock.sample(4_000_000_000, false)
        clock.sample(5_000_000_000, true)
        clock.sample(5_500_000_000, true)
        assertEquals(2L, clock.elapsedSeconds)
    }

    @Test fun doesNotCountLongSleepOrBackwardClockAndCapsOneSession() {
        val clock = ModPlaytimeClock()
        clock.sample(0, true)
        clock.sample(1_000_000_000, true)
        clock.sample(90_000_000_000, true)
        clock.sample(80_000_000_000, true)
        assertEquals(1L, clock.elapsedSeconds)
        for (n in 1L..125000L) clock.sample(80_000_000_000 + n * 5_000_000_000L, true)
        assertEquals(604800L, clock.elapsedSeconds)
    }
}
