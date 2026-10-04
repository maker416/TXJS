/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.config.Settings
import io.github.rwpp.game.ui.AssistHudLayout
import io.github.rwpp.game.ui.InGameAssistRules
import io.github.rwpp.game.ui.QuickSelectGroup
import io.github.rwpp.game.units.MovementType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class InGameAssistRulesTest {
    @Test
    fun userChoicesSurviveSavingAndRepeatedMigration() {
        val original = Settings(showBuildingAttackRange = true, showAttackRangeUnit = "All",
            showUnitTargetLine = true, improvedHealthBar = true, enableQuickSelectMenu = true,
            displayTimeInGame = true, showExtraButton = true, enableLargerKeys = true,
            enableOffscreenPanel = true, enhancedReinforceTroops = true)
        val settings = Json.decodeFromString<Settings>(Json.encodeToString(original))
        repeat(2) { settings.migrate() }
        assertTrue(settings.showBuildingAttackRange)
        assertEquals("All", settings.showAttackRangeUnit)
        assertTrue(settings.showUnitTargetLine)
        assertTrue(settings.improvedHealthBar)
        assertTrue(settings.enableQuickSelectMenu)
        assertTrue(settings.displayTimeInGame)
        assertTrue(settings.showExtraButton)
        assertTrue(settings.enableLargerKeys)
        assertFalse(settings.enableOffscreenPanel)
        assertFalse(settings.enhancedReinforceTroops)
    }

    @Test
    fun defaultsStayOptInAndUnknownRangeModeIsSanitized() {
        val settings = Settings(showAttackRangeUnit = "unknown")
        settings.migrate()
        assertEquals("Never", settings.showAttackRangeUnit)
        assertFalse(settings.showBuildingAttackRange)
        assertFalse(settings.enableQuickSelectMenu)
    }

    @Test
    fun amphibiousUnitsBelongToBothSeaAndLandAndBuildersAreExcludedFromTroops() {
        for (movement in listOf(MovementType.HOVER, MovementType.OVER_CLIFF_WATER)) {
            assertTrue(QuickSelectGroup.SEA.matches(movement, false))
            assertTrue(QuickSelectGroup.LAND.matches(movement, false))
        }
        assertTrue(QuickSelectGroup.ALL.matches(MovementType.AIR, false))
        assertFalse(QuickSelectGroup.LAND.matches(MovementType.AIR, false))
        assertFalse(QuickSelectGroup.AIR.matches(MovementType.WATER, false))
        for (movement in MovementType.entries) {
            assertFalse(QuickSelectGroup.ALL.matches(movement, true))
        }
        for (movement in listOf(MovementType.BUILDING, MovementType.NONE)) {
            assertTrue(QuickSelectGroup.BUILDINGS.matches(movement, false))
            assertFalse(QuickSelectGroup.ALL.matches(movement, false))
        }
    }

    @Test
    fun seaRangeUsesAllModeAndHighlightedUnitOverridesDisabledMode() {
        assertFalse(InGameAssistRules.showRange(MovementType.WATER, true, "Land", false))
        assertTrue(InGameAssistRules.showRange(MovementType.WATER, false, "All", false))
        assertFalse(InGameAssistRules.showRange(MovementType.BUILDING, false, "All", false))
        assertTrue(InGameAssistRules.showRange(MovementType.BUILDING, true, "Never", false))
        assertTrue(InGameAssistRules.showRange(MovementType.AIR, false, "Never", true))
    }

    @Test
    fun healthColorsRespectThresholdsAndOtherBars() {
        val green = 0xC8009600.toInt()
        val yellow = 0xC8ED9121.toInt()
        val red = 0xC8F04444.toInt()
        assertEquals(green, InGameAssistRules.healthBarColor(green, 60f, 100f))
        assertEquals(yellow, InGameAssistRules.healthBarColor(green, 30f, 100f))
        assertEquals(red, InGameAssistRules.healthBarColor(green, 29f, 100f))
        assertEquals(green, InGameAssistRules.healthBarColor(green, 5f, 0f))
        assertEquals(green, InGameAssistRules.healthBarColor(green, Float.NaN, 100f))
        val energy = 0xB8677577.toInt()
        assertEquals(energy, InGameAssistRules.healthBarColor(energy, 20f, 100f))
    }

    @Test
    fun hudFitsNarrowViewportWithoutOverlappingAndHidesWhenTooSmall() {
        for (scale in listOf(0.5f, 1f, 2f, 3f)) {
            val row = assertNotNull(AssistHudLayout.fit(320f, 720f, scale, 5))
            assertTrue(row.left >= 0)
            assertTrue(row.x(4) + row.width <= 320)
            assertTrue(row.top + row.height < 720)
        }
        assertNull(AssistHudLayout.fit(100f, 720f, 1f, 5))
        assertNull(AssistHudLayout.fit(320f, 90f, 1f, 5))
        assertNull(AssistHudLayout.fit(Float.NaN, 720f, 1f, 5))
    }

    @Test
    fun clockHandlesReplaySeekingAndMissingPing() {
        assertEquals("0:00", InGameAssistRules.clockText(-1, null))
        assertEquals("2:05  42ms", InGameAssistRules.clockText(125999, 42))
        assertEquals("60:00", InGameAssistRules.clockText(3600000, -1))
    }
}
