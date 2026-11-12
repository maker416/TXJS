/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.net.LatestVersionProfile
import io.github.rwpp.net.Net
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.MENU_BUTTON_IDS
import io.github.rwpp.ui.UI
import io.github.rwpp.ui.UIProvider
import io.github.rwpp.ui.LauncherPage
import io.github.rwpp.ui.ResourceCenterView
import io.github.rwpp.ui.launcherPage
import io.github.rwpp.ui.resetNavigation
import io.github.rwpp.ui.navigateBack
import io.github.rwpp.widget.RWPPTheme
import io.github.rwpp.widget.WindowManager
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 在真实主菜单上测量按钮、检查描边像素与点击行为，不启动游戏或发网络请求。 */
@OptIn(ExperimentalTestApi::class)
class MainMenuThemeTest {
    private lateinit var themesRoot: File

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin {
            modules(module {
                single { Settings(autoCheckUpdate = false, enableAnimations = false, language = "zh") }
                single<Net> { stub(Net::class.java) }
                single<AppContext> { stub(AppContext::class.java) }
            })
        }.koin
        koinInit = true
        logger = LoggerFactory.getLogger("MainMenuThemeTest")
        AccountSession.resetForTests()
        AccountSession.networkEnabled = false
        FriendsSession.clear()
        UI.latestVersionProfile = LatestVersionProfile("0.0.0", "", false)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
        themesRoot = Files.createTempDirectory("rwpp-menu-theme-").toFile()
        ArtThemeController.themeRootOverride = themesRoot
    }

    @AfterTest
    fun tearDown() {
        SwingUtilities.invokeAndWait { resetNavigation(LauncherPage.MainMenu) }
        ArtThemeController.apply(null)
        ArtThemeController.themeRootOverride = null
        UI.latestVersionProfile = null
        FriendsSession.clear()
        AccountSession.resetForTests()
        stopKoin()
        koinInit = false
        themesRoot.deleteRecursively()
    }

    @Test
    fun buttonImagesAndStylesKeepModernDashboardSlotsAndClicks() = runDesktopComposeUiTest(width = 1280, height = 720) {
        activate("""
            [menu.layout]
            buttonCorner = 0
            showBorder = false

            [menu.buttons.singlePlayer]
            showBorder = true

            [menu.buttons.mods]
            widthPercent = 80
        """.trimIndent(), withImages = true)
        File(themesRoot, "test/buttons/multiplayer.png").copyTo(File(themesRoot, "test/buttons/singlePlayer.png"))
        ArtThemeController.scan()
        ArtThemeController.apply("test")
        var clicks = 0
        menu(onMods = { clicks++ })
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        val single = card("singlePlayer")
        val multi = card("multiplayer")
        val mods = card("mods")
        val fullCell = card("settings")
        val modsBounds = mods.fetchSemanticsNode().boundsInRoot
        assertEquals(single.fetchSemanticsNode().boundsInRoot.width, multi.fetchSemanticsNode().boundsInRoot.width,
            "仅按钮外观定制必须保留新版并排主卡")
        closeTo(single.fetchSemanticsNode().boundsInRoot.top, multi.fetchSemanticsNode().boundsInRoot.top)
        assertTrue(single.fetchSemanticsNode().boundsInRoot.right < multi.fetchSemanticsNode().boundsInRoot.left)
        closeTo(fullCell.fetchSemanticsNode().boundsInRoot.width * 0.8f, modsBounds.width)
        val resources = card("resources").fetchSemanticsNode().boundsInRoot
        closeTo((resources.right + fullCell.fetchSemanticsNode().boundsInRoot.left) / 2, modsBounds.center.x)
        waitUntil(timeoutMillis = 10_000) { multi.captureToImage().toAwtImage().getRGB(0, 0) == 0xFF204080.toInt() }
        assertEquals(0xFF204080.toInt(), multi.captureToImage().toAwtImage().getRGB(0, 0),
            "有背景图且关闭描边时，直角边缘应保留原图颜色")
        waitUntil(timeoutMillis = 10_000) {
            val image = single.captureToImage().toAwtImage()
            image.getRGB(image.width / 2, 3) == 0xFF204080.toInt()
        }
        val bordered = single.captureToImage().toAwtImage()
        assertTrue(bordered.getRGB(0, bordered.height / 2) != 0xFF204080.toInt(), "单按钮 true 应覆盖全局 false")
        mods.performClick()
        runOnIdle { assertEquals(1, clicks) }
        save("grid-border-overrides.png")
    }

    @Test
    fun verticalWidthUsesWholeRowAndKeepsBorderByDefault() = runDesktopComposeUiTest(width = 1000, height = 800) {
        activate("""
            [menu.layout]
            orientation = "vertical"
            buttonCorner = 0

            [menu.buttons.mods]
            widthPercent = 80
            showBorder = false
        """.trimIndent())
        menu()
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        val multiBounds = card("multiplayer").performScrollTo().fetchSemanticsNode().boundsInRoot
        val modsBounds = card("mods").performScrollTo().fetchSemanticsNode().boundsInRoot
        closeTo(multiBounds.width * 0.8f, modsBounds.width)
        closeTo(multiBounds.center.x, modsBounds.center.x)
        assertTrue(modsBounds.height >= 48f, "竖排卡片仍须保留最小点击高度")
        onNodeWithTag("launcher-theme-manager").assertIsDisplayed()
        save("vertical-width.png")
    }

    @Test
    fun fullSpanWidthAndClampingStayWithinMenu() = runDesktopComposeUiTest(width = 1000, height = 800) {
        activate("""
            [menu.buttons.mods]
            span = 2
            widthPercent = 80

            [menu.buttons.settings]
            span = 99
            widthPercent = 200
        """.trimIndent())
        menu()
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        val multi = card("multiplayer").performScrollTo().fetchSemanticsNode().boundsInRoot
        val mods = card("mods").performScrollTo().fetchSemanticsNode().boundsInRoot
        val settings = card("settings").performScrollTo().fetchSemanticsNode().boundsInRoot
        closeTo(multi.width * 0.8f, mods.width)
        closeTo(multi.width, settings.width)
        closeTo(multi.center.x, mods.center.x)
    }

    @Test
    fun legacyDirectCardsKeepBothCallbacksOnModernDashboard() = runDesktopComposeUiTest(width = 1000, height = 700) {
        activate("""
            [menu.buttons.resourceBrowser]
            order = -2
            [menu.buttons.esports]
            order = -1
        """.trimIndent())
        var forumClicks = 0
        var esportsClicks = 0
        menu(onResourceBrowser = { forumClicks++ }, onEsports = { esportsClicks++ })
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        val forum = card("resourceBrowser").fetchSemanticsNode().boundsInRoot
        val esports = card("esports").fetchSemanticsNode().boundsInRoot
        closeTo(forum.width, esports.width)
        closeTo(forum.top, esports.top)
        assertTrue(forum.right <= esports.left, "旧槽位在新版网格中应为两个独立卡片")
        card("esports").performClick()
        runOnIdle { assertEquals(0, forumClicks); assertEquals(1, esportsClicks) }
        card("resourceBrowser").performClick()
        runOnIdle { assertEquals(1, forumClicks); assertEquals(1, esportsClicks) }
        save("legacy-direct-cards.png")
    }

    @Test
    fun hiddenLegacyCardKeepsForumAndManagerReachable() = runDesktopComposeUiTest(width = 1000, height = 700) {
        activate("""
            [menu.buttons.resourceBrowser]
            order = -2
            [menu.buttons.esports]
            hidden = true
        """.trimIndent())
        var managerClicks = 0
        menu(onExtension = { managerClicks++ })
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        card("resourceBrowser").assertIsDisplayed()
        card("esports").assertDoesNotExist()
        onNodeWithTag("launcher-theme-manager").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, managerClicks) }
    }

    @Test
    fun extremeEnglishLayoutKeepsCardsAndManagerUsable() = runDesktopComposeUiTest(width = 640, height = 320) {
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_en.toml").readText())
        activate("""
            [menu.layout]
            columns = 4
            widthPercent = 100
            spacing = 0
            offsetY = 400
            [menu.buttons.resourceBrowser]
            order = -2147483648
            widthPercent = 1
            [menu.buttons.esports]
            order = 2147483647
        """.trimIndent())
        menu()
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        listOf("resourceBrowser", "esports").forEach { id ->
            val bounds = card(id).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 48f, "极小宽度配置仍须保留可点击卡片：$id")
            assertTrue(bounds.left >= 0f && bounds.right <= 640f)
        }
        onNodeWithTag("launcher-theme-manager").assertIsDisplayed()
        button("menu.home.exit").assertIsDisplayed()
    }

    @Test
    fun defaultDashboardKeepsPrimaryAndUtilityActionsReachable() = runDesktopComposeUiTest(width = 1280, height = 582) {
        val clicks = mutableListOf<String>()
        menu(
            onSinglePlayer = { clicks += "single" }, onMultiplayer = { clicks += "multi" },
            onMods = { clicks += "mods" }, onSettings = { clicks += "settings" },
            onInfo = { clicks += "info" }, onFriends = { clicks += "friends" },
            onResourceBrowser = { clicks += "forum" }, onEsports = { clicks += "esports" },
            onExtension = { clicks += "extensions" },
        )
        val single = button("menu.singlePlayerGame").fetchSemanticsNode().boundsInRoot
        val multi = button("menu.multiplayer").fetchSemanticsNode().boundsInRoot
        closeTo(single.top, multi.top)
        assertTrue(single.right < multi.left)
        val utilityKeys = listOf("menu.home.resources", "menu.modsAndMaps", "menu.settings", "menu.openSourceInfo")
        utilityKeys.forEach { key ->
            button(key).assertIsDisplayed()
            closeTo(button("menu.settings").fetchSemanticsNode().boundsInRoot.top, button(key).fetchSemanticsNode().boundsInRoot.top)
        }
        save("default-dashboard.png")
        listOf("menu.singlePlayerGame", "menu.multiplayer", "menu.modsAndMaps", "menu.settings", "menu.openSourceInfo").forEach {
            button(it).performClick()
        }
        onNodeWithContentDescription(readI18n("menu.friends")).performClick()
        button("menu.home.resources").performSemanticsAction(SemanticsActions.OnClick) { action ->
            action()
        }
        runOnIdle {
            assertEquals(LauncherPage.ResourceCenter, launcherPage)
            navigateBack()
            assertEquals(LauncherPage.MainMenu, launcherPage)
            assertEquals(listOf("single", "multi", "mods", "settings", "info", "friends"), clicks)
        }
    }

    @Test
    fun defaultDashboardFitsPortrait() = runDesktopComposeUiTest(width = 400, height = 800) {
        menu()
        button("menu.singlePlayerGame").assertIsDisplayed()
        button("menu.multiplayer").assertIsDisplayed()
        button("menu.settings").assertIsDisplayed()
        button("menu.home.exit").assertIsDisplayed()
        val single = button("menu.singlePlayerGame").fetchSemanticsNode().boundsInRoot
        val multi = button("menu.multiplayer").fetchSemanticsNode().boundsInRoot
        assertTrue(single.bottom < multi.top)
        save("default-dashboard-portrait.png")
    }

    @Test
    fun shortLandscapeKeepsActionsAboveExit() = runDesktopComposeUiTest(width = 800, height = 360) {
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_en.toml").readText())
        var settingsClicks = 0
        menu(onSettings = { settingsClicks++ })
        button("menu.singlePlayerGame").assertIsDisplayed()
        button("menu.multiplayer").assertIsDisplayed()
        button("menu.settings").assertIsDisplayed()
        button("menu.home.exit").assertIsDisplayed()
        onNodeWithContentDescription(readI18n("menu.friends")).assertIsDisplayed()
        button("menu.settings").performScrollTo().performClick()
        button("menu.openSourceInfo").assertIsDisplayed()
        runOnIdle { assertEquals(1, settingsClicks) }
        val settings = button("menu.settings").fetchSemanticsNode().boundsInRoot
        val exit = button("menu.home.exit").fetchSemanticsNode().boundsInRoot
        assertTrue(settings.bottom <= exit.top)
        save("default-dashboard-small-landscape.png")
    }

    @Test
    fun phoneLandscapeFitsSixCards() = runDesktopComposeUiTest(width = 640, height = 320) {
        menu()
        listOf("menu.singlePlayerGame", "menu.multiplayer", "menu.home.resources", "menu.modsAndMaps",
            "menu.settings", "menu.openSourceInfo", "menu.home.exit").forEach {
            button(it).assertIsDisplayed()
        }
        save("default-dashboard-phone.png")
    }

    @Test
    fun textureThemeKeepsModernDashboardAndResourceCenter() = runDesktopComposeUiTest(width = 1280, height = 582) {
        activate("[colors]\nprimary = \"#00FF96\"")
        menu()
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        val single = button("menu.singlePlayerGame").fetchSemanticsNode().boundsInRoot
        val multi = button("menu.multiplayer").fetchSemanticsNode().boundsInRoot
        closeTo(single.top, multi.top)
        assertTrue(single.right < multi.left)
        button("menu.home.resources").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        runOnIdle { assertEquals(LauncherPage.ResourceCenter, launcherPage) }
        save("texture-theme-dashboard.png")
    }

    @Test
    fun titleThemeKeepsItsImageOnModernDashboard() = runDesktopComposeUiTest(width = 1000, height = 700) {
        activate("[menu]\nshowTitleBadge = false", withTitle = true)
        menu()
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        val logo = onNodeWithContentDescription("RWJS")
        waitUntil(timeoutMillis = 10_000) {
            val image = logo.captureToImage().toAwtImage()
            image.getRGB(image.width / 2, image.height / 2) == 0xFF204080.toInt()
        }
        button("menu.home.resources").assertIsDisplayed()
        save("title-theme-dashboard.png")
    }

    @Test
    fun everyHiddenCardKeepsThemeManagerAndRestoreReachable() = runDesktopComposeUiTest(width = 640, height = 320) {
        activate(MENU_BUTTON_IDS.joinToString("\n") { "[menu.buttons.$it]\nhidden = true\n" })
        var managerClicks = 0
        menu(onExtension = { managerClicks++ })
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        MENU_BUTTON_IDS.forEach { card(it).assertDoesNotExist() }
        onNodeWithTag("launcher-theme-manager").assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(1, managerClicks)
            assertTrue(ArtThemeController.apply(null))
        }
        waitForIdle()
        listOf("singlePlayer", "multiplayer", "resources", "settings").forEach { card(it).assertIsDisplayed() }
        onNodeWithTag("launcher-theme-manager").assertIsDisplayed()
        save("hidden-cards-restored.png")
    }

    @Test
    fun legacyExitOverrideWorksAndModernOverrideTakesPrecedence() = runDesktopComposeUiTest(width = 1000, height = 700) {
        activate("", strings = "[menu]\nexit = \"Legacy exit\"")
        menu()
        onNode(hasClickAction() and hasText("Legacy exit")).assertIsDisplayed()
        runOnIdle {
            activate("", strings = "[menu]\nexit = \"Legacy exit\"\n[menu.home]\nexit = \"Modern exit\"")
        }
        waitForIdle()
        onNode(hasClickAction() and hasText("Modern exit")).assertIsDisplayed()
        onNode(hasClickAction() and hasText("Legacy exit")).assertDoesNotExist()
    }

    @Test
    fun invalidImagesFallBackWithoutLosingModernCards() = runDesktopComposeUiTest(width = 1000, height = 700) {
        activate("[menu.layout]\nbuttonCorner = 0\nshowBorder = false")
        var clicks = 0
        menu(onMultiplayer = { clicks++ })
        val baselineCard = card("multiplayer").captureToImage().toAwtImage()
        val baselineTitle = onNodeWithContentDescription("RWJS").captureToImage().toAwtImage()
        runOnIdle {
            val buttons = File(themesRoot, "test/buttons").apply { mkdirs() }
            File(buttons, "multiplayer.png").writeText("broken image bytes")
            File(themesRoot, "test/title.png").writeText("broken title bytes")
            ArtThemeController.scan()
            assertTrue(ArtThemeController.apply("test"))
        }
        waitUntil(timeoutMillis = 10_000) {
            samePixels(baselineCard, card("multiplayer").captureToImage().toAwtImage()) &&
                samePixels(baselineTitle, onNodeWithContentDescription("RWJS").captureToImage().toAwtImage())
        }
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        card("multiplayer").performClick()
        onNodeWithTag("launcher-theme-manager").assertIsDisplayed()
        runOnIdle { assertEquals(1, clicks) }
        save("broken-images-fallback.png")
    }

    @Test
    fun themeImageSwitchAndDisableRestoreBuiltInDashboard() = runDesktopComposeUiTest(width = 1000, height = 700) {
        menu()
        val baselineCard = card("multiplayer").captureToImage().toAwtImage()
        val baselineTitle = onNodeWithContentDescription("RWJS").captureToImage().toAwtImage()
        runOnIdle {
            activate("[menu.layout]\nbuttonCorner = 0\nshowBorder = false", withImages = true, withTitle = true)
        }
        waitUntil(timeoutMillis = 10_000) { card("multiplayer").captureToImage().toAwtImage().getRGB(0, 0) == 0xFF204080.toInt() }
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        val single = card("singlePlayer").fetchSemanticsNode().boundsInRoot
        val multi = card("multiplayer").fetchSemanticsNode().boundsInRoot
        closeTo(single.top, multi.top)
        runOnIdle { assertTrue(ArtThemeController.apply(null)) }
        waitUntil(timeoutMillis = 10_000) {
            samePixels(baselineCard, card("multiplayer").captureToImage().toAwtImage()) &&
                samePixels(baselineTitle, onNodeWithContentDescription("RWJS").captureToImage().toAwtImage())
        }
        runOnIdle { assertNull(ArtThemeController.activeTheme) }
        card("resources").assertIsDisplayed()
        onNodeWithTag("launcher-theme-manager").assertIsDisplayed()
        save("theme-disabled-dashboard.png")
    }

    @Test
    fun invalidColorKeepsBuiltInTextAndValidColorReachesModernCards() = runDesktopComposeUiTest(width = 1000, height = 700) {
        menu()
        val baselineCard = card("multiplayer").captureToImage().toAwtImage()
        runOnIdle { activate("[colors]\nonSurface = \"invalid\"") }
        waitForIdle()
        assertTrue(samePixels(baselineCard, card("multiplayer").captureToImage().toAwtImage()),
            "非法颜色必须保留新版主页的原有回退外观")
        runOnIdle { activate("[colors]\nonSurface = \"#FFFF00FF\"") }
        waitUntil(timeoutMillis = 10_000) {
            containsColor(card("multiplayer").captureToImage().toAwtImage(), 0xFFFF00FF.toInt())
        }
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
    }

    @Test
    fun unknownButtonLayoutFieldsDoNotChangeDefaultDashboard() = runDesktopComposeUiTest(width = 1000, height = 700) {
        menu()
        val originalSingle = card("singlePlayer").fetchSemanticsNode().boundsInRoot
        val originalMulti = card("multiplayer").fetchSemanticsNode().boundsInRoot
        runOnIdle {
            activate("""
                [menu.buttons.typo]
                hidden = true
                span = -1
            """.trimIndent())
        }
        waitForIdle()
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        val single = card("singlePlayer").fetchSemanticsNode().boundsInRoot
        val multi = card("multiplayer").fetchSemanticsNode().boundsInRoot
        closeTo(originalSingle.width, single.width)
        closeTo(originalMulti.width, multi.width)
        closeTo(single.top, multi.top)
        assertTrue(single.right < multi.left, "未知槽位的布局字段应被忽略，不能切换为定制网格")
        card("resources").assertIsDisplayed()
        card("typo").assertDoesNotExist()
    }

    @Test
    fun explicitlyDeclaredDefaultWidthAndCornerAreApplied() = runDesktopComposeUiTest(width = 1000, height = 800) {
        activate("""
            [menu.layout]
            widthPercent = 65
            columns = 1
            buttonCorner = 20
            showBorder = false
        """.trimIndent(), withImages = true)
        menu()
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        val multi = card("multiplayer").performScrollTo()
        closeTo(650f, multi.fetchSemanticsNode().boundsInRoot.width)
        waitUntil(timeoutMillis = 10_000) {
            val image = multi.captureToImage().toAwtImage()
            image.getRGB(image.width / 2, 0) == 0xFF204080.toInt()
        }
        val image = multi.captureToImage().toAwtImage()
        assertTrue(image.getRGB(0, 0) != 0xFF204080.toInt(), "显式20dp圆角应裁切图片四角")
        assertTrue(image.getRGB(17, 0) != 0xFF204080.toInt(), "显式20dp不能被新版主卡默认15dp圆角替代")
        onNodeWithTag("launcher-theme-manager").assertIsDisplayed()
        save("explicit-default-layout-values.png")
    }

    @Test
    fun resourceCenterUsesPageCardsAndKeepsEveryAction() = runDesktopComposeUiTest(width = 1280, height = 720) {
        val clicks = mutableListOf<String>()
        resources(clicks, includeExtra = true)
        listOf("browser.resourceBrowser", "browser.esportsSite", "menu.extension").forEach { key ->
            button(key).assertIsDisplayed().performClick()
        }
        onNode(hasClickAction() and hasText("Extra tool")).performScrollTo().performClick()
        onNodeWithContentDescription(readI18n("resources.back")).performClick()
        runOnIdle { assertEquals(listOf("forum", "esports", "extensions", "extra", "back"), clicks) }
        save("resource-center.png")
    }

    @Test
    fun resourceCenterFitsPhoneLandscape() = runDesktopComposeUiTest(width = 640, height = 320) {
        resources()
        listOf("browser.resourceBrowser", "browser.esportsSite", "menu.extension").forEach {
            button(it).assertIsDisplayed()
            assertTrue(button(it).fetchSemanticsNode().boundsInRoot.bottom <= 320f)
        }
        onNodeWithContentDescription(readI18n("resources.back")).assertIsDisplayed()
        save("resource-center-phone.png")
    }

    @Test
    fun resourceCenterFitsPortraitAndEnglish() = runDesktopComposeUiTest(width = 400, height = 800) {
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_en.toml").readText())
        resources()
        listOf("browser.resourceBrowser", "browser.esportsSite", "menu.extension").forEach { button(it).performScrollTo().assertIsDisplayed() }
        onNodeWithContentDescription(readI18n("resources.back")).assertIsDisplayed()
        save("resource-center-portrait.png")
    }

    private fun ComposeUiTest.resources(clicks: MutableList<String> = mutableListOf(), includeExtra: Boolean = false) {
        setContent {
            KoinContext(appKoin) {
                RWPPTheme(default = true) {
                    ResourceCenterView(
                        onExit = { clicks += "back" }, onForum = { clicks += "forum" },
                        onEsports = { clicks += "esports" }, onExtensions = { clicks += "extensions" },
                        extraMenus = if (includeExtra) listOf(UIProvider.Menu("Extra tool", null) { clicks += "extra" }) else emptyList(),
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun activate(config: String, withImages: Boolean = false, withTitle: Boolean = false, strings: String? = null) {
        val dir = File(themesRoot, "test").apply { mkdirs() }
        File(dir, "theme.toml").writeText("[theme]\nid = \"test\"\nname = \"测试主题\"\n\n$config")
        if (withImages) {
            val buttons = File(dir, "buttons").apply { mkdirs() }
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, 0xFF204080.toInt())
            ImageIO.write(image, "png", File(buttons, "multiplayer.png"))
        }
        if (withTitle) {
            val image = BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, 0xFF204080.toInt())
            ImageIO.write(image, "png", File(dir, "title.png"))
        }
        if (strings != null) File(dir, "strings_zh.toml").writeText(strings)
        ArtThemeController.scan()
        ArtThemeController.apply("test")
    }

    private fun ComposeUiTest.menu(
        onMods: () -> Unit = {},
        onResourceBrowser: () -> Unit = {},
        onEsports: () -> Unit = {},
        onSinglePlayer: () -> Unit = {},
        onMultiplayer: () -> Unit = {},
        onSettings: () -> Unit = {},
        onInfo: () -> Unit = {},
        onFriends: () -> Unit = {},
        onExtension: () -> Unit = {},
    ) {
        setContent {
            KoinContext(appKoin) {
                RWPPTheme(default = true) {
                    CompositionLocalProvider(LocalWindowManager provides WindowManager.Middle) {
                        Box(Modifier.fillMaxSize().background(Color(53, 57, 53))) {
                            UIProvider().MainMenu(
                                multiplayer = onMultiplayer, singlePlayer = onSinglePlayer, settings = onSettings, mods = onMods,
                                extension = onExtension, resourceBrowser = onResourceBrowser, esports = onEsports,
                                openSourceInfo = onInfo, account = {}, friends = onFriends,
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.button(key: String) = onNode(hasClickAction() and hasText(readI18n(key)))

    private fun ComposeUiTest.card(id: String) = onNodeWithTag("launcher-card-$id")

    private fun samePixels(expected: BufferedImage, actual: BufferedImage): Boolean =
        expected.width == actual.width && expected.height == actual.height &&
            expected.getRGB(0, 0, expected.width, expected.height, null, 0, expected.width)
                .contentEquals(actual.getRGB(0, 0, actual.width, actual.height, null, 0, actual.width))

    private fun containsColor(image: BufferedImage, color: Int): Boolean =
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width).any { it == color }

    private fun closeTo(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < 1.5f, "expected $expected, actual $actual")
    }

    private fun ComposeUiTest.save(name: String) {
        val dir = File("build/theme-ui-screenshots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, name))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
        when (method.name) {
            "getKoin" -> appKoin
            "isDesktop" -> true
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                String::class.java -> ""
                else -> null
            }
        }
    } as T
}
