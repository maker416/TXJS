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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.net.LatestVersionProfile
import io.github.rwpp.net.Net
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.ui.UI
import io.github.rwpp.ui.UIProvider
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
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun gridWidthAndBorderOverridesPreserveSlotsAndClicks() = runDesktopComposeUiTest(width = 1000, height = 700) {
        activate("""
            [menu.layout]
            buttonCorner = 0
            showBorder = false

            [menu.buttons.singlePlayer]
            showBorder = true

            [menu.buttons.mods]
            widthPercent = 80
        """.trimIndent(), withImages = true)
        var clicks = 0
        menu { clicks++ }
        val single = button("menu.singlePlayerGame")
        val multi = button("menu.multiplayer")
        val mods = button("menu.modsAndMaps")
        val resources = button("browser.resourceBrowser")
        val modsBounds = mods.fetchSemanticsNode().boundsInRoot
        val sameColumnBounds = button("menu.openSourceInfo").fetchSemanticsNode().boundsInRoot
        assertEquals(single.fetchSemanticsNode().boundsInRoot.width, multi.fetchSemanticsNode().boundsInRoot.width,
            "只设置描边时，单人按钮必须继续占满整行")
        closeTo(resources.fetchSemanticsNode().boundsInRoot.width * 0.8f, modsBounds.width)
        closeTo(sameColumnBounds.center.x, modsBounds.center.x)
        assertTrue(redAtLeftEdge(single.captureToImage().toAwtImage()) > 100, "单按钮 true 应覆盖全局 false")
        waitUntil(timeoutMillis = 10_000) { multi.captureToImage().toAwtImage().getRGB(0, 0) == 0xFF204080.toInt() }
        assertEquals(0xFF204080.toInt(), multi.captureToImage().toAwtImage().getRGB(0, 0),
            "有背景图且关闭描边时，直角边缘应保留原图颜色")
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
        val mods = button("menu.modsAndMaps")
        val multi = button("menu.multiplayer")
        val modsBounds = mods.fetchSemanticsNode().boundsInRoot
        val multiBounds = multi.fetchSemanticsNode().boundsInRoot
        closeTo(multiBounds.width * 0.8f, modsBounds.width)
        closeTo(multiBounds.center.x, modsBounds.center.x)
        assertTrue(redAtLeftEdge(multi.captureToImage().toAwtImage()) > 100, "旧主题默认保留描边")
        assertTrue(redAtLeftEdge(mods.captureToImage().toAwtImage()) < 100, "单按钮 false 应覆盖全局 true")
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
        val multi = button("menu.multiplayer").fetchSemanticsNode().boundsInRoot
        val mods = button("menu.modsAndMaps").fetchSemanticsNode().boundsInRoot
        val settings = button("menu.settings").fetchSemanticsNode().boundsInRoot
        closeTo(multi.width * 0.8f, mods.width)
        closeTo(multi.width, settings.width)
        closeTo(multi.center.x, mods.center.x)
    }

    private fun activate(config: String, withImages: Boolean = false) {
        val dir = File(themesRoot, "test").apply { mkdirs() }
        File(dir, "theme.toml").writeText("[theme]\nid = \"test\"\nname = \"测试主题\"\n\n$config")
        if (withImages) {
            val buttons = File(dir, "buttons").apply { mkdirs() }
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, 0xFF204080.toInt())
            ImageIO.write(image, "png", File(buttons, "multiplayer.png"))
        }
        ArtThemeController.scan()
        ArtThemeController.apply("test")
    }

    private fun ComposeUiTest.menu(onMods: () -> Unit = {}) {
        setContent {
            KoinContext(appKoin) {
                RWPPTheme(default = true) {
                    CompositionLocalProvider(LocalWindowManager provides WindowManager.Middle) {
                        Box(Modifier.fillMaxSize().background(Color(53, 57, 53))) {
                            UIProvider().MainMenu(
                                multiplayer = {}, singlePlayer = {}, settings = {}, mods = onMods,
                                extension = {}, resourceBrowser = {}, openSourceInfo = {}, account = {}, friends = {},
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.button(key: String) = onNode(hasClickAction() and hasText(readI18n(key)))

    private fun redAtLeftEdge(image: BufferedImage) = image.getRGB(0, image.height / 2).ushr(16) and 0xFF

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
