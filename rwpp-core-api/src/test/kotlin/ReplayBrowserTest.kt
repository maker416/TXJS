/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.map.Replay
import io.github.rwpp.game.map.ReplayBrowseQuery
import io.github.rwpp.game.map.ReplayDateRange
import io.github.rwpp.game.map.ReplayGroupBy
import io.github.rwpp.game.map.ReplayLabel
import io.github.rwpp.game.map.ReplayBrowseItem
import io.github.rwpp.game.map.ReplaySectionKey
import io.github.rwpp.game.map.ReplaySort
import io.github.rwpp.game.map.browseReplays
import io.github.rwpp.game.map.parseReplayLabel
import io.github.rwpp.game.map.replayBrowseFacets
import io.github.rwpp.game.map.replayDayKey
import io.github.rwpp.game.map.toReplayBrowseItem
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayBrowserTest {
    @Test
    fun parseReplayLabelFillsRecordedAtMillis() {
        val label = parseReplayLabel("Crossing Large (10p) [v1.15] (21 8月 2026 21.50.37)")
        assertEquals("2026-08-21 21:50:37", label.recordedAt)
        val millis = label.recordedAtMillis
        assertTrue(millis != null && millis > 0L)
        assertEquals("2026-08-21", replayDayKey(millis!!))
    }

    @Test
    fun sortsByPlayerCountDescending() {
        val items = listOf(
            item(1, "A", players = 2, time = 10),
            item(2, "B", players = 10, time = 20),
            item(3, "C", players = null, time = 30),
        )
        val sections = browseReplays(items, ReplayBrowseQuery(sort = ReplaySort.PlayersDesc))
        assertEquals(listOf("B", "A", "C"), sections.single().items.map { it.label.title })
    }

    @Test
    fun filtersByMapAndVersion() {
        val items = listOf(
            item(1, "Crossing Large", players = 10, version = "v1.15"),
            item(2, "Crossing Large", players = 4, version = "v1.14"),
            item(3, "Valley", players = 10, version = "v1.15"),
        )
        val sections = browseReplays(
            items,
            ReplayBrowseQuery(mapTitle = "Crossing Large", version = "v1.15"),
        )
        assertEquals(listOf(1), sections.single().items.map { it.replay.id })
    }

    @Test
    fun filtersUnknownPlayers() {
        val items = listOf(
            item(1, "Named", players = 2),
            item(2, "Custom save", players = null),
        )
        val sections = browseReplays(items, ReplayBrowseQuery(unknownPlayers = true))
        assertEquals(listOf(2), sections.single().items.map { it.replay.id })
    }

    @Test
    fun dateRangeKeepsTodayAndDropsOld() {
        val now = noonToday()
        val items = listOf(
            item(1, "Today", time = now - 60_000L),
            item(2, "Old", time = now - 10L * 24 * 60 * 60 * 1000),
            item(3, "Unknown", time = 0L),
        )
        val today = browseReplays(
            items,
            ReplayBrowseQuery(dateRange = ReplayDateRange.Today, nowMillis = now),
        )
        assertEquals(listOf(1), today.single().items.map { it.replay.id })
    }

    @Test
    fun groupsByMapWithCounts() {
        val items = listOf(
            item(1, "Valley", players = 2, time = 1),
            item(2, "Crossing Large", players = 10, time = 2),
            item(3, "Crossing Large", players = 4, time = 3),
        )
        val sections = browseReplays(items, ReplayBrowseQuery(groupBy = ReplayGroupBy.Map))
        assertEquals(
            listOf("Crossing Large", "Valley"),
            sections.map { (it.key as ReplaySectionKey.Map).name },
        )
        assertEquals(2, sections.first().items.size)
        assertEquals(1, sections.last().items.size)
    }

    @Test
    fun textSearchMatchesTitleAndVersion() {
        val items = listOf(
            item(1, "Crossing Large", version = "v1.15"),
            item(2, "Valley", version = "v1.14"),
        )
        val byMap = browseReplays(items, ReplayBrowseQuery(text = "cross"))
        assertEquals(listOf(1), byMap.single().items.map { it.replay.id })
        val byVersion = browseReplays(items, ReplayBrowseQuery(text = "1.14"))
        assertEquals(listOf(2), byVersion.single().items.map { it.replay.id })
    }

    @Test
    fun facetsCollectDistinctMapsAndCounts() {
        val items = listOf(
            item(1, "Valley", players = 2, version = "v1.15"),
            item(2, "valley", players = 2, version = "v1.15"),
            item(3, "Custom", players = null, version = null),
        )
        val facets = replayBrowseFacets(items)
        assertEquals(listOf("Custom", "Valley"), facets.maps)
        assertEquals(listOf(2), facets.playerCounts)
        assertTrue(facets.hasUnknownPlayers)
        assertEquals(listOf("v1.15"), facets.versions)
        assertTrue(facets.hasUnknownVersion)
    }

    @Test
    fun toReplayBrowseItemUsesParsedTime() {
        val replay = object : Replay {
            override val id = 0
            override val name = "Crossing Large (10p) [v1.15] (21 8月 2026 21.50.37).replay"
            override fun displayName() = name.removeSuffix(".replay")
            override val lastModifiedMillis = 1L
            override val fileSizeBytes = 12L
        }
        val item = toReplayBrowseItem(replay)
        assertEquals("Crossing Large", item.label.title)
        assertEquals(10, item.label.playerCount)
        assertEquals(12L, item.fileSizeBytes)
        assertTrue(item.sortTimeMillis > 1L)
    }

    private fun noonToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun item(
        id: Int,
        title: String,
        players: Int? = 2,
        version: String? = "v1.15",
        time: Long = 1_000L,
        size: Long = 10L,
    ): ReplayBrowseItem {
        val replay = object : Replay {
            override val id = id
            override val name = "$title.replay"
            override fun displayName() = title
            override val lastModifiedMillis = time
            override val fileSizeBytes = size
        }
        return ReplayBrowseItem(
            replay = replay,
            label = ReplayLabel(
                title = title,
                playerCount = players,
                version = version,
                recordedAtMillis = time.takeIf { it > 0L },
            ),
            sortTimeMillis = time,
            fileSizeBytes = size,
        )
    }
}
