/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.widget.defaultRWPPColorScheme
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证受限高度下固定操作栏的真实触控范围，以及滚动、筛选回调和外部重置。 */
@OptIn(ExperimentalTestApi::class)
class MultiplayerFilterPanelUiTest {
    private val labels = listOf(
        "3v3", "4v4", "5v5", "不对称", "公益", "单挑", "双排",
        "多队伍", "模组同步", "混战", "生存", "超多人", "默认",
    )

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin {
            modules(module { single { Settings(enableAnimations = false) } })
        }.koin
        loadLanguage("zh")
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        setI18nOverride(null)
        reloadI18n()
    }

    @Test
    fun shortLandscapeKeepsBlacklistAndResetFullyVisibleAfterScrolling() =
        checkFixedActions(width = 640, height = 320, language = "zh", fontScale = 1f)

    @Test
    fun narrowEnglishLargeFontKeepsTitleCloseAndBottomActionsUsable() =
        checkFixedActions(width = 320, height = 500, language = "en", fontScale = 1.3f)

    private fun checkFixedActions(width: Int, height: Int, language: String, fontScale: Float) =
        runDesktopComposeUiTest(width = width, height = height) {
            loadLanguage(language)
            var filters by mutableStateOf(MultiplayerFilters())
            var blacklistClicks = 0
            var resetClicks = 0
            var closeClicks = 0
            setContent {
                TestPanel(
                    filters = filters,
                    fontScale = fontScale,
                    onFiltersChange = { filters = it },
                    onReset = { resetClicks++; filters = MultiplayerFilters() },
                    onBlacklist = { blacklistClicks++ },
                    onDismiss = { closeClicks++ },
                )
            }
            waitForIdle()
            val initialActions = assertActionsInsidePanel()
            assertTitleAndCloseDoNotOverlap()
            assertTextFits(readI18n("multiplayer.filter.title"))
            assertTextFits(readI18n("multiplayer.filter.blacklist"))
            assertTextFits(readI18n("multiplayer.filter.reset"))
            onNodeWithTag("multiplayer-filter-blacklist").performTouchInput { click() }
            assertEquals(1, blacklistClicks)
            save("filter-" + width + "x$height-$language.png")

            onNodeWithTag("multiplayer-filter-scroll").performScrollToNode(hasText(labels.last()))
            onNodeWithText(labels.last()).performScrollTo().assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(setOf(labels.last()), filters.selectedLabels)
            onNodeWithText(labels.last()).assertIsSelected()
            assertEquals(initialActions, assertActionsInsidePanel(), "scrolling must not move the action bar")
            save("filter-" + width + "x$height-$language-labels.png")

            onNodeWithTag("multiplayer-filter-blacklist").performTouchInput { click() }
            assertEquals(2, blacklistClicks, "blacklist must remain reachable at the end of the list")
            onNodeWithTag("multiplayer-filter-reset").performTouchInput { click() }
            assertEquals(1, resetClicks)
            assertEquals(MultiplayerFilters(), filters)
            onNodeWithContentDescription(readI18n("common.close"))
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
                .performTouchInput { click() }
            assertEquals(1, closeClicks)
        }

    @Test
    fun textSwitchesAndResetKeepExternalFilterStateInSync() =
        runDesktopComposeUiTest(width = 640, height = 360) {
            var filters by mutableStateOf(
                MultiplayerFilters(playerLimitRange = 2..12, selectedLabels = setOf(labels.first()))
            )
            setContent {
                TestPanel(
                    filters = filters,
                    onFiltersChange = { filters = it },
                    onReset = { filters = MultiplayerFilters() },
                )
            }
            onNodeWithTag("multiplayer-filter-map").performScrollTo().performTextReplacement("Islands")
            onNodeWithTag("multiplayer-filter-creator").performScrollTo().performTextReplacement("Alice")
            assertEquals("Islands", filters.mapName)
            assertEquals("Alice", filters.creatorName)
            assertEquals(2..12, filters.playerLimitRange)
            assertEquals(setOf(labels.first()), filters.selectedLabels)

            onNodeWithTag("multiplayer-filter-scroll")
                .performScrollToNode(hasTestTag("multiplayer-filter-modded"))
            onNodeWithTag("multiplayer-filter-modded").performScrollTo().assertIsOff().performClick().assertIsOn()
            onNodeWithTag("multiplayer-filter-joinable").performScrollTo().assertIsOff().performClick().assertIsOn()
            assertTrue(filters.hideModdedRooms)
            assertTrue(filters.hideNonJoinableRooms)
            assertEquals("Islands", filters.mapName)
            assertEquals("Alice", filters.creatorName)
            assertEquals(setOf(labels.first()), filters.selectedLabels)

            onNodeWithTag("multiplayer-filter-reset").performTouchInput { click() }
            assertEquals(MultiplayerFilters(), filters)
            onNodeWithTag("multiplayer-filter-modded").performScrollTo().assertIsOff()
            onNodeWithTag("multiplayer-filter-joinable").performScrollTo().assertIsOff()
            onNodeWithTag("multiplayer-filter-scroll").performScrollToNode(hasText(labels.first()))
            onNodeWithText(labels.first()).performScrollTo().assertIsNotSelected()
            onNodeWithTag("multiplayer-filter-scroll").performScrollToIndex(0)
            onNodeWithTag("multiplayer-filter-map").performScrollTo().performTextReplacement("New map")
            assertEquals(MultiplayerFilters(mapName = "New map"), filters, "editing after reset must use the reset state")
            assertActionsInsidePanel()
        }

    @Test
    fun widePanelShowsAllSectionsAndFailedLabelRequestStillAllowsClearingSelection() =
        runDesktopComposeUiTest(width = 780, height = 800) {
            val initialFilters = MultiplayerFilters(
                mapName = "冰封群岛",
                creatorName = "Alice",
                playerLimitRange = 2..12,
                hideNonJoinableRooms = true,
                selectedLabels = setOf(labels[0], labels[1]),
            )
            var filters by mutableStateOf(initialFilters)
            var availableLabels by mutableStateOf<List<String>?>(labels)
            var labelsFailed by mutableStateOf(false)
            setContent {
                TestPanel(
                    filters = filters,
                    availableLabels = availableLabels,
                    labelsFailed = labelsFailed,
                    onFiltersChange = { filters = it },
                    onReset = { filters = MultiplayerFilters() },
                )
            }
            waitForIdle()
            listOf("sectionSearch", "sectionRange", "sectionDisplay", "sectionLabel").forEach { section ->
                onNodeWithText(readI18n("multiplayer.filter.$section"), useUnmergedTree = true).assertIsDisplayed()
            }
            val initialActions = assertActionsInsidePanel()
            save("filter-780x800-zh-overview.png")

            runOnIdle {
                availableLabels = null
                labelsFailed = true
            }
            onNodeWithTag("multiplayer-filter-scroll")
                .performScrollToNode(hasText(readI18n("multiplayer.filter.labelLoadFailed")))
            onNodeWithText(readI18n("multiplayer.filter.labelLoadFailed")).performScrollTo().assertIsDisplayed()
            val clear = onNodeWithText(readI18n("multiplayer.filter.labelClearSelection"))
                .performScrollTo().assertIsDisplayed()
            assertEquals(initialFilters, filters, "a failed label request must preserve the existing filters")
            assertEquals(initialActions, assertActionsInsidePanel(), "a failed request must not move the action bar")
            save("filter-780x800-zh-labels-failed.png")
            clear.performClick()
            assertEquals(initialFilters.copy(selectedLabels = emptySet()), filters)
            onNodeWithText(readI18n("multiplayer.filter.labelClearSelection")).assertDoesNotExist()
            assertActionsInsidePanel()
        }

    @Composable
    private fun TestPanel(
        filters: MultiplayerFilters,
        fontScale: Float = 1f,
        availableLabels: List<String>? = labels,
        labelsLoading: Boolean = false,
        labelsFailed: Boolean = false,
        onFiltersChange: (MultiplayerFilters) -> Unit,
        onReset: () -> Unit,
        onBlacklist: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        KoinContext(appKoin) {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = fontScale)) {
                MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1B211A)).padding(8.dp)) {
                        MultiplayerFilterPanel(
                            filters = filters,
                            availableLabels = availableLabels,
                            labelsLoading = labelsLoading,
                            labelsFailed = labelsFailed,
                            onFiltersChange = onFiltersChange,
                            onReset = onReset,
                            onBlacklist = onBlacklist,
                            onDismiss = onDismiss,
                            modifier = Modifier.fillMaxSize().testTag("multiplayer-filter-panel"),
                        )
                    }
                }
            }
        }
    }

    private fun loadLanguage(language: String) {
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_$language.toml").readText())
    }

    private fun ComposeUiTest.assertActionsInsidePanel(): List<Rect> {
        val panel = onNodeWithTag("multiplayer-filter-panel").fetchSemanticsNode().boundsInRoot
        val scroll = onNodeWithTag("multiplayer-filter-scroll").fetchSemanticsNode().boundsInRoot
        return listOf("multiplayer-filter-blacklist", "multiplayer-filter-reset").map { tag ->
            val node = onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= panel.left && bounds.right <= panel.right, "$tag extends outside the panel: $bounds")
            assertTrue(bounds.top >= panel.top && bounds.bottom <= panel.bottom, "$tag is clipped vertically: $bounds")
            assertTrue(bounds.top >= scroll.bottom, "$tag overlaps the scrollable content: $bounds")
            bounds
        }
    }

    private fun ComposeUiTest.assertTitleAndCloseDoNotOverlap() {
        val title = onNodeWithTag("multiplayer-filter-title").fetchSemanticsNode().boundsInRoot
        val close = onNodeWithContentDescription(readI18n("common.close")).fetchSemanticsNode().boundsInRoot
        assertTrue(title.right <= close.left || title.bottom <= close.top || title.top >= close.bottom,
            "title overlaps the close button: title=$title, close=$close")
    }

    private fun ComposeUiTest.assertTextFits(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty(), "missing text layout: $text")
        assertFalse(layouts.any { layout ->
            layout.didOverflowHeight || (0 until layout.lineCount).any(layout::isLineEllipsized)
        }, "text clipped: $text")
    }

    private fun ComposeUiTest.save(name: String) {
        val target = File("build/reports/multiplayer-filter-ui/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target))
    }
}
