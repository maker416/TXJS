/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.account.RelativeTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelativeTimeTest {

    @Test
    fun parsesUtcZulu() {
        // 1970-01-01T00:00:00Z 必须是 epoch 0
        assertEquals(0L, RelativeTime.parseEpochMillis("1970-01-01T00:00:00Z"))
        assertEquals(0L, RelativeTime.parseEpochMillis("1970-01-01T00:00:00.123Z"))
    }

    @Test
    fun parsesPositiveOffset() {
        // +08:00 的 08:00 等于 UTC 00:00
        assertEquals(0L, RelativeTime.parseEpochMillis("1970-01-01T08:00:00+08:00"))
        assertEquals(
            1_000L,
            RelativeTime.parseEpochMillis("1970-01-01T08:00:01+08:00"),
        )
    }

    @Test
    fun parsesNegativeOffset() {
        // -05:00 的 00:00 等于 UTC 05:00
        assertEquals(
            5 * 3_600_000L,
            RelativeTime.parseEpochMillis("1970-01-01T00:00:00-05:00"),
        )
    }

    @Test
    fun parsesGoStyleTimestamp() {
        // 2026-09-23T10:00:00+08:00 == 2026-09-23T02:00:00Z
        val a = RelativeTime.parseEpochMillis("2026-09-23T10:00:00+08:00")
        val b = RelativeTime.parseEpochMillis("2026-09-23T02:00:00Z")
        assertEquals(b, a)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(RelativeTime.parseEpochMillis("not a time"))
        assertNull(RelativeTime.parseEpochMillis("2026-13-01T00:00:00Z"))
        assertNull(RelativeTime.parseEpochMillis("2026-09-23"))
    }

    @Test
    fun elapsedPicksUnit() {
        val now = RelativeTime.parseEpochMillis("2026-09-23T12:00:00Z")!!
        val just = RelativeTime.elapsed("2026-09-23T11:59:30Z", now)
        assertEquals(RelativeTime.Unit.JUST_NOW, just?.second)
        val minutes = RelativeTime.elapsed("2026-09-23T11:45:00Z", now)
        assertEquals(15L to RelativeTime.Unit.MINUTES, minutes)
        val hours = RelativeTime.elapsed("2026-09-23T09:00:00Z", now)
        assertEquals(3L to RelativeTime.Unit.HOURS, hours)
        val days = RelativeTime.elapsed("2026-09-20T12:00:00Z", now)
        assertEquals(3L to RelativeTime.Unit.DAYS, days)
    }

    @Test
    fun elapsedClampsFutureToJustNow() {
        val now = RelativeTime.parseEpochMillis("2026-09-23T12:00:00Z")!!
        val future = RelativeTime.elapsed("2026-09-23T12:05:00Z", now)
        assertEquals(RelativeTime.Unit.JUST_NOW, future?.second)
    }
}
