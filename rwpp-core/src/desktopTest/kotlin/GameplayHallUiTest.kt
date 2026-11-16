/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.widget.UiMotionTheme
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.setUiAnimationsEnabled
import net.peanuuutz.tomlkt.Toml
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class GameplayHallUiTest {
    private lateinit var settings: Settings

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        settings = Settings(enableAnimations = false)
        appKoin = startKoin { modules(module { single { settings } }) }.koin
        reloadI18n()
        language("zh")
        SwingUtilities.invokeAndWait {
            UI.showAccountView = false
            UI.showFriendsView = false
            resetNavigation(LauncherPage.MainMenu)
        }
    }

    @AfterTest
    fun cleanup() {
        SwingUtilities.invokeAndWait { resetNavigation(LauncherPage.MainMenu) }
        stopKoin()
        reloadI18n()
    }

    @Test
    fun desktopCategoriesSearchFavoritesAndRealDestination() = runDesktopComposeUiTest(width = 1280, height = 720) {
        var destination: LauncherPage? = null
        show(onOpen = { destination = it })
        onNodeWithTag("hall-featured").assertIsDisplayed()
        save("desktop.png")
        onNodeWithTag("hall-category-extended").performClick()
        onNodeWithTag("hall-favorite-royale").performClick()
        onNodeWithTag("hall-category-favorites").performClick()
        onNodeWithTag("hall-card-royale").assertIsDisplayed()
        onNodeWithTag("hall-card-pokemon").assertDoesNotExist()
        onNodeWithTag("hall-category-all").performClick()
        onNodeWithTag("hall-search").performTextInput("生存挑战")
        onNodeWithTag("hall-card-survival").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(LauncherPage.Survival, destination) }
        onNodeWithTag("hall-search").performTextReplacement("nothing-matches")
        onNodeWithText("没有找到匹配的玩法，试试其他关键词或筛选。").assertIsDisplayed()
        onNodeWithTag("hall-reset").performClick()
        onNodeWithTag("hall-card-royale").assertIsDisplayed()
        onNodeWithTag("hall-filter-status").performClick()
        onNode(hasText("可进入") and hasAnyAncestor(isPopup())).performClick()
        onNodeWithTag("hall-card-royale").assertDoesNotExist()
        onNodeWithTag("hall-card-multiplayer").assertIsDisplayed()
        onNodeWithTag("hall-filter-tag").performClick()
        onNode(hasText("PVE") and hasAnyAncestor(isPopup())).performClick()
        onNodeWithTag("hall-card-multiplayer").assertDoesNotExist()
        onNodeWithTag("hall-card-missions").assertIsDisplayed()
    }

    @Test
    fun phoneLandscapeAndPreviewStayUsable() = runDesktopComposeUiTest(width = 960, height = 432) {
        var closed = 0
        show(onExit = { closed++ })
        onNodeWithTag("hall-back").assertIsDisplayed()
        onNodeWithTag("hall-featured-open").assertIsDisplayed()
        save("landscape.png")
        onNodeWithTag("hall-featured-1").performClick()
        onNodeWithTag("hall-featured-open").performClick()
        onNodeWithTag("hall-detail").assertIsDisplayed()
        onNodeWithText("知道了").performClick()
        onNodeWithTag("hall-back").performClick()
        runOnIdle { assertEquals(1, closed) }
    }

    @Test
    fun portraitAndEnglishHaveReachableContent() = runDesktopComposeUiTest(width = 412, height = 844) {
        language("en")
        show()
        onNodeWithTag("hall-back").assertIsDisplayed()
        onNodeWithTag("hall-search").assertIsDisplayed()
        onNodeWithTag("hall-featured-open").assertIsDisplayed()
        save("portrait-en.png")
        onNodeWithTag("hall-search").performTextInput("Classic")
        onNodeWithTag("hall-content").performScrollToNode(hasTestTag("hall-card-multiplayer"))
        onNodeWithTag("hall-card-multiplayer").assertIsDisplayed()
    }

    @Test
    fun hallNavigationRestoresFiltersAndRespectsLiveAnimationToggle() = runDesktopComposeUiTest(width = 960, height = 640) {
        mainClock.autoAdvance = false
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                Box(Modifier.fillMaxSize()) {
                    if (launcherPage == LauncherPage.MainMenu) Text("home")
                    GameplayHallPageHost {
                        GameplayHallView({ closePage(LauncherPage.GameplayHall) }, { navigateTo(it) })
                    }
                }
            }
        }
        runOnIdle { navigateTo(LauncherPage.GameplayHall) }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("gameplay-hall").assertIsDisplayed()
        onNodeWithTag("hall-category-original").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("hall-card-multiplayer").performSemanticsAction(SemanticsActions.OnClick) { it() }
        mainClock.advanceTimeBy(48)
        runOnIdle { assertEquals(LauncherPage.Multiplayer, launcherPage) }
        onNodeWithTag("gameplay-hall").assertDoesNotExist()
        runOnIdle { navigateBack() }
        mainClock.advanceTimeBy(48)
        onNodeWithTag("hall-featured").assertDoesNotExist()
        onNodeWithTag("hall-card-multiplayer").assertIsDisplayed()
        onNodeWithTag("hall-back").performSemanticsAction(SemanticsActions.OnClick) { it() }
        mainClock.advanceTimeBy(48)
        onNodeWithText("home").assertIsDisplayed()
        runOnIdle {
            settings.setUiAnimationsEnabled(true)
            navigateTo(LauncherPage.GameplayHall)
        }
        mainClock.advanceTimeBy(500)
        onNodeWithTag("gameplay-hall").assertIsDisplayed()
        onNodeWithTag("hall-back").performSemanticsAction(SemanticsActions.OnClick) { it() }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("gameplay-hall").assertExists() // 动画保留绘制。
        runOnIdle { settings.setUiAnimationsEnabled(false) }
        mainClock.advanceTimeBy(32)
        onNodeWithTag("gameplay-hall").assertDoesNotExist()
    }

    private fun language(code: String) {
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_$code.toml").readText())
    }

    private fun ComposeUiTest.show(onExit: () -> Unit = {}, onOpen: (LauncherPage) -> Unit = {}) {
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                CompositionLocalProvider(LocalWindowManager provides WindowManager.Middle) {
                    GameplayHallView(onExit, onOpen)
                }
            }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        val file = File("build/reports/gameplay-hall/$name").apply { parentFile.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }
}
