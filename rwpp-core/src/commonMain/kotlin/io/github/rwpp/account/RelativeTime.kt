/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.account

/**
 * 极简 RFC 3339（Go `time.Time` JSON）解析与相对时间格式化。
 *
 * 支持形态：`2026-09-23T10:00:00+08:00`、`...Z`、可带小数秒。
 * 项目未引入 kotlinx-datetime，这里用 days-from-civil 算法手算 epoch，避免平台差异。
 */
object RelativeTime {

    /** 解析 RFC 3339 时间戳为 epoch millis；非法输入返回 null。 */
    fun parseEpochMillis(text: String): Long? {
        val t = text.trim()
        // 形如 2026-09-23T10:00:00[.fff][Z|±hh:mm]
        val match = Regex(
            """^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(?:[Zz]|([+-])(\d{2}):(\d{2}))$""",
        ).matchEntire(t) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val hour = match.groupValues[4].toIntOrNull() ?: return null
        val minute = match.groupValues[5].toIntOrNull() ?: return null
        val second = match.groupValues[6].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) {
            return null
        }
        val localEpochDays = daysFromCivil(year, month, day)
        val localMillis = (((localEpochDays * 24 + hour) * 60 + minute) * 60 + second) * 1000
        // 时区偏移：+08:00 表示本地时间比 UTC 快 8 小时，UTC = 本地 - 偏移
        val sign = match.groupValues[7]
        val offsetMillis = if (sign.isEmpty()) {
            0L
        } else {
            val oh = match.groupValues[8].toIntOrNull() ?: return null
            val om = match.groupValues[9].toIntOrNull() ?: return null
            val total = ((oh * 60L + om) * 60L) * 1000L
            if (sign == "-") -total else total
        }
        return localMillis - offsetMillis
    }

    /** 距今的相对时间：(值, 单位)；单位单位用于 i18n 键选择。 */
    fun elapsed(iso: String, nowMillis: Long): Pair<Long, Unit>? {
        val then = parseEpochMillis(iso) ?: return null
        val diff = (nowMillis - then).coerceAtLeast(0L)
        return when {
            diff < 60_000L -> 0L to Unit.JUST_NOW
            diff < 3_600_000L -> (diff / 60_000L) to Unit.MINUTES
            diff < 86_400_000L -> (diff / 3_600_000L) to Unit.HOURS
            else -> (diff / 86_400_000L) to Unit.DAYS
        }
    }

    enum class Unit { JUST_NOW, MINUTES, HOURS, DAYS }

    /** Howard Hinnant 的 days_from_civil：公历日期 → 1970-01-01 起的天数。 */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        var y = year
        val m = month
        if (m <= 2) y -= 1
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400 // [0, 399]
        val mp = (m + 9) % 12 // [0, 11]
        val doy = (153 * mp + 2) / 5 + day - 1 // [0, 365]
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146097L + doe) - 719468L
    }
}
