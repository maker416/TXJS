/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.ui.ExtensionView
import io.github.rwpp.widget.RWPPTheme
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

/** 从真实扩展入口验证美术包操作，并留存横屏/窄屏布局截图。 */
@OptIn(ExperimentalTestApi::class)
class ExtensionsUiTest {
    private lateinit var root: File
    private lateinit var settings: Settings
    private lateinit var chosenFile: File

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        root = Files.createTempDirectory("rwpp-extensions-ui-").toFile()
        settings = Settings(enableAnimations = false, language = "zh", launcherMusicVolume = 0f)
        logger = LoggerFactory.getLogger("ExtensionsUiTest")
        appKoin = startKoin {
            modules(module {
                single { settings }
                single<ExternalHandler> {
                    Proxy.newProxyInstance(ExternalHandler::class.java.classLoader, arrayOf(ExternalHandler::class.java)) { _, method, args ->
                        if (method.name == "openFileChooser") {
                            @Suppress("UNCHECKED_CAST")
                            (args!!.last() as (File) -> Unit)(chosenFile)
                        }
                        null
                    } as ExternalHandler
                }
            })
        }.koin
        koinInit = true
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
        ArtThemeController.themeRootOverride = File(root, "themes").apply { mkdirs() }
        ArtThemeController.scan()
    }

    @AfterTest
    fun teardown() {
        ArtThemeController.apply(null)
        ArtThemeController.themeRootOverride = null
        stopKoin()
        koinInit = false
        root.deleteRecursively()
        reloadI18n()
    }

    @Test
    fun extensionEntryShowsPacksAndSupportsEnableDisableAndRestore() = runDesktopComposeUiTest(width = 1100, height = 700) {
        seed("forest", "林间行动", "#97BC62")
        seed("ocean", "深海前线", "#70B8D8")
        seed("sunset", "落日余晖", "#DCA376")
        showExtensions()
        onNodeWithText(readI18n("extension.extension")).assertIsDisplayed()
        onNodeWithText(readI18n("themes.manageEntry"), substring = true).assertIsDisplayed()
        onNodeWithText(readI18n("extension.resource")).assertDoesNotExist()
        onNodeWithText(readI18n("mod.apply")).assertDoesNotExist()
        screenshot("extensions-wide.png")
        onNodeWithTag("theme-toggle-forest").performScrollTo().performClick()
        runOnIdle { assertEquals("forest", settings.selectedThemePack) }
        onNodeWithTag("theme-toggle-forest").performClick()
        runOnIdle { assertNull(settings.selectedThemePack) }
        onNodeWithTag("theme-toggle-ocean").performScrollTo().performClick()
        onNodeWithTag("theme-restore-default").assertIsDisplayed().performClick()
        runOnIdle { assertNull(ArtThemeController.activeTheme); assertNull(settings.selectedThemePack) }
    }

    @Test
    fun narrowEmptyStateImportsAndDeletesPackWithConfirmation() = runDesktopComposeUiTest(width = 380, height = 820) {
        chosenFile = File(root, "sample.RWTHEME")
        ZipOutputStream(chosenFile.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("theme.toml"))
            out.write("[theme]\nid = \"sample\"\nname = \"我的美术包\"".toByteArray())
            out.closeEntry()
        }
        showExtensions()
        onNodeWithText(readI18n("themes.emptyTitle")).assertIsDisplayed()
        screenshot("extensions-empty-narrow.png")
        onNodeWithText(readI18n("themes.importFirst")).assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 10_000) { ArtThemeController.installedThemes.size == 1 }
        onNodeWithText(readI18n("common.close")).performClick()
        onNodeWithTag("theme-toggle-sample").performScrollTo().performClick()
        runOnIdle { assertEquals("sample", settings.selectedThemePack) }
        onNodeWithTag("theme-restore-default").assertIsDisplayed()
        screenshot("extensions-active-narrow.png")
        val restore = onNodeWithTag("theme-restore-default").fetchSemanticsNode().boundsInRoot
        assertTrue(restore.left >= 0 && restore.right <= 380)
        onNodeWithTag("theme-delete-sample").performScrollTo().performClick()
        onNodeWithText(readI18n("common.cancel")).performClick()
        runOnIdle { assertEquals(1, ArtThemeController.installedThemes.size) }
        onNodeWithTag("theme-delete-sample").performClick()
        onNodeWithText(readI18n("themes.delete")).performClick()
        runOnIdle { assertTrue(ArtThemeController.installedThemes.isEmpty()); assertNull(settings.selectedThemePack) }
        onNodeWithText(readI18n("themes.emptyTitle")).assertIsDisplayed()
    }

    @Test
    fun phoneLandscapeEmptyStateNeedsNoScrolling() = runDesktopComposeUiTest(width = 872, height = 360) {
        showExtensions()
        onNodeWithText(readI18n("themes.emptyTitle")).assertIsDisplayed()
        onNodeWithText(readI18n("themes.importFirst")).assertIsDisplayed()
        onNodeWithTag("theme-import").assertIsDisplayed()
        assertFullyVisible(onNodeWithText(readI18n("themes.importFirst")))
        onAllNodesWithTag("theme-list").assertCountEquals(0)
        screenshot("extensions-phone-empty.png")
    }

    @Test
    fun phoneLandscapeKeepsActionsVisibleWhileBrowsingPacks() = runDesktopComposeUiTest(width = 872, height = 360) {
        seed("first", "A 遗忘战境材质包：很长的名称也不挤占操作区域", "#35BE8A")
        showExtensions()
        // 单个包打开即可操作，不靠 performScrollTo 掩盖首屏布局问题。
        onNodeWithTag("theme-toggle-first").assertIsDisplayed().performClick()
        onNodeWithTag("theme-restore-default").assertIsDisplayed()
        onNodeWithTag("theme-toggle-first").assertIsDisplayed()
        assertFullyVisible(onNodeWithTag("theme-card-first"))
        screenshot("extensions-phone-single.png")
        val importBounds = onNodeWithTag("theme-import").fetchSemanticsNode().boundsInRoot
        val refreshBounds = onNodeWithTag("theme-refresh").fetchSemanticsNode().boundsInRoot
        val restoreBounds = onNodeWithTag("theme-restore-default").fetchSemanticsNode().boundsInRoot
        runOnIdle {
            repeat(7) { seed("extra-$it", "B $it 美术包", "#97BC62") }
        }
        onNodeWithTag("theme-list").performScrollToNode(hasTestTag("theme-card-extra-6"))
        onNodeWithTag("theme-import").assertIsDisplayed()
        onNodeWithTag("theme-refresh").assertIsDisplayed()
        onNodeWithTag("theme-restore-default").assertIsDisplayed()
        assertEquals(importBounds, onNodeWithTag("theme-import").fetchSemanticsNode().boundsInRoot)
        assertEquals(refreshBounds, onNodeWithTag("theme-refresh").fetchSemanticsNode().boundsInRoot)
        assertEquals(restoreBounds, onNodeWithTag("theme-restore-default").fetchSemanticsNode().boundsInRoot)
        screenshot("extensions-phone-scrolled.png")
        onNodeWithTag("theme-restore-default").performClick()
        runOnIdle { assertNull(settings.selectedThemePack) }
    }

    private fun seed(id: String, name: String, primary: String) {
        val dir = File(ArtThemeController.themeRootOverride, id).apply { mkdirs() }
        File(dir, "theme.toml").writeText("""
            [theme]
            id = "$id"
            name = "$name"
            author = "RWJS"
            version = "1.0"
            description = "为战场增添新的色彩，打造自己的启动器外观。"
            [colors]
            primary = "$primary"
        """.trimIndent())
        ArtThemeController.scan()
    }

    private fun ComposeUiTest.showExtensions() {
        setContent {
            KoinContext(appKoin) {
                RWPPTheme(default = true) {
                    Box(Modifier.fillMaxSize().background(Color(53, 57, 53))) { ExtensionView {} }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.assertFullyVisible(node: SemanticsNodeInteraction) {
        val viewport = onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom, "控件不应超出屏幕上下边界：$bounds / $viewport")
        assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right, "控件不应超出屏幕左右边界：$bounds / $viewport")
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val folder = File("build/extensions-ui-screenshots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(folder, name))
    }
}
