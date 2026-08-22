/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.map

import java.util.Calendar

enum class ReplaySort {
    TimeNewest,
    TimeOldest,
    MapAsc,
    MapDesc,
    PlayersDesc,
    PlayersAsc,
    SizeDesc,
    SizeAsc,
}

enum class ReplayGroupBy {
    None,
    Map,
    Players,
    Date,
    Version,
}

enum class ReplayDateRange {
    All,
    Today,
    Last7Days,
    Last30Days,
}

data class ReplayBrowseItem(
    val replay: Replay,
    val label: ReplayLabel,
    val sortTimeMillis: Long,
    val fileSizeBytes: Long,
)

data class ReplayBrowseFacets(
    val maps: List<String>,
    val playerCounts: List<Int>,
    val hasUnknownPlayers: Boolean,
    val versions: List<String>,
    val hasUnknownVersion: Boolean,
)

data class ReplayBrowseQuery(
    val text: String = "",
    val sort: ReplaySort = ReplaySort.TimeNewest,
    val groupBy: ReplayGroupBy = ReplayGroupBy.None,
    val dateRange: ReplayDateRange = ReplayDateRange.All,
    val mapTitle: String? = null,
    val playerCount: Int? = null,
    val unknownPlayers: Boolean = false,
    val version: String? = null,
    val unknownVersion: Boolean = false,
    val nowMillis: Long = 0L,
) {
    fun hasNarrowingFilters(): Boolean {
        return text.isNotBlank() ||
            dateRange != ReplayDateRange.All ||
            mapTitle != null ||
            unknownPlayers ||
            playerCount != null ||
            unknownVersion ||
            version != null
    }
}

sealed interface ReplaySectionKey {
    data object Flat : ReplaySectionKey
    data class Map(val name: String) : ReplaySectionKey
    data class Players(val count: Int?) : ReplaySectionKey
    data class Day(val ymd: String?) : ReplaySectionKey
    data class Version(val name: String?) : ReplaySectionKey
}

data class ReplayBrowseSection(
    val key: ReplaySectionKey,
    val items: List<ReplayBrowseItem>,
) {
    val id: String
        get() = when (val k = key) {
            ReplaySectionKey.Flat -> "flat"
            is ReplaySectionKey.Map -> "map:${k.name}"
            is ReplaySectionKey.Players -> "players:${k.count ?: "unknown"}"
            is ReplaySectionKey.Day -> "day:${k.ymd ?: "unknown"}"
            is ReplaySectionKey.Version -> "version:${k.name ?: "unknown"}"
        }
}

fun toReplayBrowseItem(replay: Replay): ReplayBrowseItem {
    val label = parseReplayLabel(replay.displayName())
    val sortTime = label.recordedAtMillis?.takeIf { it > 0L } ?: replay.lastModifiedMillis
    return ReplayBrowseItem(
        replay = replay,
        label = label,
        sortTimeMillis = sortTime,
        fileSizeBytes = replay.fileSizeBytes,
    )
}

fun replayBrowseFacets(items: List<ReplayBrowseItem>): ReplayBrowseFacets {
    val maps = items.map { it.label.title.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
    val counts = items.mapNotNull { it.label.playerCount }.distinct().sorted()
    val versions = items.mapNotNull { it.label.version?.trim()?.takeIf { v -> v.isNotEmpty() } }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
    return ReplayBrowseFacets(
        maps = maps,
        playerCounts = counts,
        hasUnknownPlayers = items.any { it.label.playerCount == null },
        versions = versions,
        hasUnknownVersion = items.any { it.label.version.isNullOrBlank() },
    )
}

fun browseReplays(
    items: List<ReplayBrowseItem>,
    query: ReplayBrowseQuery,
): List<ReplayBrowseSection> {
    val now = if (query.nowMillis > 0L) query.nowMillis else System.currentTimeMillis()
    val filtered = items.filter { matchesReplayQuery(it, query, now) }
        .sortedWith { a, b -> compareReplayItems(a, b, query.sort) }
    if (filtered.isEmpty()) return emptyList()
    return when (query.groupBy) {
        ReplayGroupBy.None -> listOf(ReplayBrowseSection(ReplaySectionKey.Flat, filtered))
        ReplayGroupBy.Map -> groupBySorted(filtered, query.sort) { ReplaySectionKey.Map(it.label.title) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { (it.key as ReplaySectionKey.Map).name })
        ReplayGroupBy.Players -> groupBySorted(filtered, query.sort) { ReplaySectionKey.Players(it.label.playerCount) }
            .sortedWith(compareBy<ReplayBrowseSection> { section ->
                val count = (section.key as ReplaySectionKey.Players).count
                if (count == null) 1 else 0
            }.thenByDescending { (it.key as ReplaySectionKey.Players).count ?: -1 })
        ReplayGroupBy.Date -> groupBySorted(filtered, query.sort) {
            ReplaySectionKey.Day(replayDayKey(it.sortTimeMillis))
        }.sortedWith(compareBy<ReplayBrowseSection> { section ->
            val day = (section.key as ReplaySectionKey.Day).ymd
            if (day == null) 1 else 0
        }.thenByDescending { (it.key as ReplaySectionKey.Day).ymd ?: "" })
        ReplayGroupBy.Version -> groupBySorted(filtered, query.sort) {
            ReplaySectionKey.Version(it.label.version?.takeIf { v -> v.isNotBlank() })
        }.sortedWith(compareBy<ReplayBrowseSection> { section ->
            val version = (section.key as ReplaySectionKey.Version).name
            if (version == null) 1 else 0
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { (it.key as ReplaySectionKey.Version).name ?: "" })
    }
}

internal fun matchesReplayQuery(
    item: ReplayBrowseItem,
    query: ReplayBrowseQuery,
    nowMillis: Long,
): Boolean {
    val text = query.text.trim()
    if (text.isNotEmpty()) {
        val players = item.label.playerCount?.toString().orEmpty()
        val hit = item.label.title.contains(text, ignoreCase = true) ||
            item.replay.name.contains(text, ignoreCase = true) ||
            item.replay.displayName().contains(text, ignoreCase = true) ||
            (item.label.version?.contains(text, ignoreCase = true) == true) ||
            (item.label.recordedAt?.contains(text, ignoreCase = true) == true) ||
            players.contains(text)
        if (!hit) return false
    }
    val mapTitle = query.mapTitle
    if (mapTitle != null && !item.label.title.equals(mapTitle, ignoreCase = true)) {
        return false
    }
    if (query.unknownPlayers) {
        if (item.label.playerCount != null) return false
    } else if (query.playerCount != null && item.label.playerCount != query.playerCount) {
        return false
    }
    if (query.unknownVersion) {
        if (!item.label.version.isNullOrBlank()) return false
    } else if (query.version != null && item.label.version?.equals(query.version, ignoreCase = true) != true) {
        return false
    }
    return inReplayDateRange(item.sortTimeMillis, query.dateRange, nowMillis)
}

internal fun inReplayDateRange(
    sortTimeMillis: Long,
    range: ReplayDateRange,
    nowMillis: Long,
): Boolean {
    if (range == ReplayDateRange.All) return true
    if (sortTimeMillis <= 0L) return false
    val startOfToday = startOfLocalDay(nowMillis)
    val start = when (range) {
        ReplayDateRange.All -> return true
        ReplayDateRange.Today -> startOfToday
        ReplayDateRange.Last7Days -> startOfToday - 6L * DAY_MS
        ReplayDateRange.Last30Days -> startOfToday - 29L * DAY_MS
    }
    return sortTimeMillis >= start && sortTimeMillis <= nowMillis + DAY_MS
}

internal fun compareReplayItems(a: ReplayBrowseItem, b: ReplayBrowseItem, sort: ReplaySort): Int {
    val primary = when (sort) {
        ReplaySort.TimeNewest -> compareTime(a.sortTimeMillis, b.sortTimeMillis, newestFirst = true)
        ReplaySort.TimeOldest -> compareTime(a.sortTimeMillis, b.sortTimeMillis, newestFirst = false)
        ReplaySort.MapAsc -> a.label.title.compareTo(b.label.title, ignoreCase = true)
        ReplaySort.MapDesc -> b.label.title.compareTo(a.label.title, ignoreCase = true)
        ReplaySort.PlayersDesc -> compareNullableInt(a.label.playerCount, b.label.playerCount, descending = true)
        ReplaySort.PlayersAsc -> compareNullableInt(a.label.playerCount, b.label.playerCount, descending = false)
        ReplaySort.SizeDesc -> b.fileSizeBytes.compareTo(a.fileSizeBytes)
        ReplaySort.SizeAsc -> a.fileSizeBytes.compareTo(b.fileSizeBytes)
    }
    if (primary != 0) return primary
    val byTitle = a.label.title.compareTo(b.label.title, ignoreCase = true)
    if (byTitle != 0) return byTitle
    return a.replay.name.compareTo(b.replay.name, ignoreCase = true)
}

private fun groupBySorted(
    items: List<ReplayBrowseItem>,
    sort: ReplaySort,
    keyOf: (ReplayBrowseItem) -> ReplaySectionKey,
): List<ReplayBrowseSection> {
    return items.groupBy(keyOf).map { (key, grouped) ->
        ReplayBrowseSection(
            key = key,
            items = grouped.sortedWith { a, b -> compareReplayItems(a, b, sort) },
        )
    }
}

private fun compareTime(a: Long, b: Long, newestFirst: Boolean): Int {
    val aMissing = a <= 0L
    val bMissing = b <= 0L
    if (aMissing && bMissing) return 0
    if (aMissing) return 1
    if (bMissing) return -1
    return if (newestFirst) b.compareTo(a) else a.compareTo(b)
}

private fun compareNullableInt(a: Int?, b: Int?, descending: Boolean): Int {
    if (a == null && b == null) return 0
    if (a == null) return 1
    if (b == null) return -1
    return if (descending) b.compareTo(a) else a.compareTo(b)
}

private fun startOfLocalDay(millis: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = millis
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private const val DAY_MS = 24L * 60L * 60L * 1000L
