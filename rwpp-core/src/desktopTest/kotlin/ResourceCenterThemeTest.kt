/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.ui.ResourceCenterView
import io.github.rwpp.widget.RWPPTheme
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 从资源中心真实卡片验证旧槽位兼容、管理入口和图片失败回退。 */
@OptIn(ExperimentalTestApi::class)
class ResourceCenterThemeTest {
    private lateinit var themesRoot: File

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin {
            modules(module {
                single { Settings(autoCheckUpdate = false, enableAnimations = false, language = "zh", launcherMusicVolume = 0f) }
            })
        }.koin
        koinInit = true
        logger = LoggerFactory.getLogger("ResourceCenterThemeTest")
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
        themesRoot = Files.createTempDirectory("rwpp-resource-theme-").toFile()
        ArtThemeController.themeRootOverride = themesRoot
    }

    @AfterTest
    fun tearDown() {
        ArtThemeController.apply(null)
        ArtThemeController.themeRootOverride = null
        stopKoin()
        koinInit = false
        themesRoot.deleteRecursively()
        reloadI18n()
    }

    @Test
    fun hiddenResourceCardsKeepFixedManagementEntryReachable() = runDesktopComposeUiTest(width = 400, height = 800) {
        activate("""
            [menu.buttons.resourceBrowser]
            hidden = true
            [menu.buttons.esports]
            hidden = true
            [menu.buttons.extensions]
            hidden = true
        """.trimIndent())
        var managementClicks = 0
        show(onExtensions = { managementClicks++ })
        listOf("resourceBrowser", "esports", "extensions").forEach {
            onNodeWithTag("resource-card-$it").assertDoesNotExist()
        }
        onNodeWithTag("resource-theme-management").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, managementClicks) }
    }

    @Test
    fun resourceSlotsRespectOrderWidthAndClicks() = runDesktopComposeUiTest(width = 1100, height = 800) {
        activate("""
            [menu.layout]
            orientation = "vertical"
            widthPercent = 100
            showBorder = false
            buttonCorner = 0
            [menu.buttons.extensions]
            order = -1
            widthPercent = 50
            [menu.buttons.esports]
            hidden = true
        """.trimIndent())
        val clicks = mutableListOf<String>()
        show(onForum = { clicks += "forum" }, onExtensions = { clicks += "extensions" })
        val management = onNodeWithTag("resource-card-extensions").fetchSemanticsNode().boundsInRoot
        val forum = onNodeWithTag("resource-card-resourceBrowser").fetchSemanticsNode().boundsInRoot
        assertTrue(management.bottom < forum.top)
        assertTrue(abs(management.width * 2 - forum.width) < 2f)
        onNodeWithTag("resource-card-esports").assertDoesNotExist()
        onNodeWithTag("resource-card-extensions").performClick()
        onNodeWithTag("resource-card-resourceBrowser").performClick()
        runOnIdle { assertEquals(listOf("extensions", "forum"), clicks) }
    }

    @Test
    fun buttonImagesKeepDefaultResourceTopology() = runDesktopComposeUiTest(width = 1100, height = 800) {
        activate("", image = true)
        show()
        val forum = onNodeWithTag("resource-card-resourceBrowser").fetchSemanticsNode().boundsInRoot
        val esports = onNodeWithTag("resource-card-esports").fetchSemanticsNode().boundsInRoot
        val management = onNodeWithTag("resource-card-extensions").fetchSemanticsNode().boundsInRoot
        assertTrue(forum.right <= esports.left + 1f)
        assertTrue(esports.bottom < management.top)
        waitUntil(timeoutMillis = 10_000) {
            val image = onNodeWithTag("resource-card-esports").captureToImage().toAwtImage()
            val rgb = image.getRGB(8, image.height / 2)
            val red = rgb.ushr(16) and 0xFF
            val green = rgb.ushr(8) and 0xFF
            val blue = rgb and 0xFF
            blue > red + 20 && blue > green + 10
        }
    }

    @Test
    fun brokenImagesFallBackToThemeSurface() = runDesktopComposeUiTest(width = 600, height = 800) {
        activate("""
            [colors]
            surface = "#345678"
            onSurface = "#FFFFFF"
            [menu.layout]
            orientation = "vertical"
            widthPercent = 100
            buttonCorner = 0
            showBorder = false
            [menu.buttons.resourceBrowser]
            hidden = true
            [menu.buttons.extensions]
            hidden = true
        """.trimIndent(), brokenImage = true)
        var clicks = 0
        show(onEsports = { clicks++ })
        val card = onNodeWithTag("resource-card-esports").assertIsDisplayed()
        waitUntil(timeoutMillis = 10_000) {
            val image = card.captureToImage().toAwtImage()
            image.getRGB(8, image.height / 2) == 0xFF345678.toInt()
        }
        card.performClick()
        runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun weightedGridSlotsHonorNarrowWidthsAndMinimumHitArea() = runDesktopComposeUiTest(width = 1100, height = 800) {
        activate("""
            [menu.layout]
            columns = 2
            widthPercent = 100
            buttonCorner = 0
            showBorder = false
            [menu.buttons.resourceBrowser]
            widthPercent = 80
            [menu.buttons.esports]
            widthPercent = 1
            [menu.buttons.extensions]
            span = 1
        """.trimIndent())
        var esportsClicks = 0
        show(onEsports = { esportsClicks++ })
        val forum = onNodeWithTag("resource-card-resourceBrowser").fetchSemanticsNode().boundsInRoot
        val esports = onNodeWithTag("resource-card-esports").fetchSemanticsNode().boundsInRoot
        val management = onNodeWithTag("resource-card-extensions").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(forum.width - management.width * 0.8f) < 2f)
        assertTrue(abs(esports.width - 48f) < 2f, "narrow card must retain a 48dp hit area: ${esports.width}")
        assertTrue(esports.height >= 48f)
        onNodeWithTag("resource-card-esports").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, esportsClicks) }
    }

    private fun activate(config: String, image: Boolean = false, brokenImage: Boolean = false) {
        val dir = File(themesRoot, "resource-test").apply { mkdirs() }
        File(dir, "theme.toml").writeText("[theme]\nid = \"resource-test\"\nname = \"Resource test\"\n\n$config")
        if (image || brokenImage) {
            val file = File(dir, "buttons/esports.png").apply { parentFile.mkdirs() }
            if (brokenImage) file.writeText("invalid image") else {
                val bitmap = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) bitmap.setRGB(x, y, 0xFF204080.toInt())
                ImageIO.write(bitmap, "png", file)
            }
        }
        ArtThemeController.scan()
        assertTrue(ArtThemeController.apply("resource-test"))
    }

    private fun ComposeUiTest.show(
        onForum: () -> Unit = {},
        onEsports: () -> Unit = {},
        onExtensions: () -> Unit = {},
    ) {
        setContent {
            KoinContext(appKoin) {
                RWPPTheme(default = true) {
                    Box(Modifier.fillMaxSize()) {
                        ResourceCenterView(onExit = {}, onForum, onEsports, onExtensions)
                    }
                }
            }
        }
        waitForIdle()
    }
}
