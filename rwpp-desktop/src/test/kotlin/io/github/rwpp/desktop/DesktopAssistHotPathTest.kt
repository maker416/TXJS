/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import io.github.rwpp.desktop.impl.inject.AssistHudRowCache
import io.github.rwpp.desktop.impl.inject.drawVisibleAssists
import io.github.rwpp.game.ui.AssistHudLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DesktopAssistHotPathTest {
    @Test fun unitFogQueriesAreOrderedAndOnlyRunForDrawableFeatures() {
        val viewer = Any()
        fun trace(range: Boolean, line: Boolean, sourceVisible: Boolean, seesTarget: Boolean): List<String> {
            val calls = mutableListOf<String>()
            drawVisibleAssists(range, line,
                visibleViewer = { calls.add("source"); if (sourceVisible) viewer else null },
                targetVisible = { assertSame(viewer, it); calls.add("target"); seesTarget },
                draw = { calls.add("draw:$it") },
            )
            return calls
        }

        assertEquals(emptyList(), trace(false, false, true, true))
        assertEquals(listOf("source"), trace(true, true, false, true))
        // 范围（含选中单位单独标记）不能触碰目标迷雾；单位自身的迷雾仍然要查。
        assertEquals(listOf("source", "draw:false"), trace(true, false, true, true))
        assertEquals(listOf("source", "target"), trace(false, true, true, false))
        assertEquals(listOf("source", "target", "draw:false"), trace(true, true, true, false))
        assertEquals(listOf("source", "target", "draw:true"), trace(false, true, true, true))
        assertEquals(listOf("source", "target", "draw:true"), trace(true, true, true, true))
    }

    @Test fun hudRowsReuseFitsUntilViewportOrScaleChanges() {
        for ((count, preferred) in listOf(5 to 42f, 3 to 110f)) {
            var fits = 0
            val cache = AssistHudRowCache { w, h, s ->
                fits++
                AssistHudLayout.fit(w, h, s, count, preferred)
            }
            fun frame(w: Float, h: Float, s: Float): AssistHudLayout? {
                val row = cache.get(w, h, s)
                assertEquals(AssistHudLayout.fit(w, h, s, count, preferred), row)
                return row
            }

            val first = assertNotNull(frame(900f, 720f, 1f))
            repeat(120) { assertSame(first, cache.get(900f, 720f, 1f)) }
            assertEquals(1, fits)
            assertNotNull(frame(600f, 720f, 1f)) // 侧栏占用变化
            assertNotNull(frame(600f, 520f, 1f))
            assertNotNull(frame(600f, 520f, 2f))
            assertEquals(4, fits)
            repeat(120) { assertNull(frame(40f, 520f, 2f)) }
            assertEquals(5, fits)
            assertEquals(first, frame(900f, 720f, 1f))
            val fallback = assertNotNull(frame(900f, 720f, Float.NaN))
            assertSame(fallback, cache.get(900f, 720f, Float.NaN))
            assertEquals(7, fits)
        }
    }
}
