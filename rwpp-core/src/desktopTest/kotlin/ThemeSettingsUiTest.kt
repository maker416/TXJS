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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.*
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.i18n.GameI18nResolver
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.ui.SettingsView
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
import kotlin.test.*

/** 在真实设置页验证清除、保存与页面重建，并渲染宽屏/窄屏用于检查布局。 */
@OptIn(ExperimentalTestApi::class)
class ThemeSettingsUiTest {
    private lateinit var settings: Settings
    private lateinit var themeRoot: File
    private var saved: Settings? = null

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        settings = Settings(backgroundImagePath = "C:/wallpaper.png", enableAnimations = false, language = "zh")
        saved = null
        logger = LoggerFactory.getLogger("ThemeSettingsUiTest")
        appKoin = startKoin {
            modules(module {
                single { settings }
                single { AccountPreferences() }
                single { ModPlaytimePreferences(apiUrl = "") }
                single<AppContext> { stub(AppContext::class.java) }
                single<GameI18nResolver> { stub(GameI18nResolver::class.java) }
                single<ConfigIO> {
                    stub(ConfigIO::class.java) { name, args ->
                        when (name) {
                            "getGameConfig" -> false
                            "saveConfig", "saveAllConfig" -> {
                                saved = (args?.firstOrNull() as? Settings ?: settings).copy()
                                Unit
                            }
                            else -> null
                        }
                    }
                }
                single<ExternalHandler> {
                    stub(ExternalHandler::class.java) { name, args ->
                        if (name == "openFileChooser") {
                            @Suppress("UNCHECKED_CAST")
                            (args!!.last() as (File) -> Unit)(File("replacement.png"))
                        }
                        null
                    }
                }
            })
        }.koin
        koinInit = true
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
        themeRoot = Files.createTempDirectory("rwpp-settings-theme-").toFile()
        ArtThemeController.themeRootOverride = themeRoot
    }

    @AfterTest
    fun teardown() {
        ArtThemeController.apply(null)
        ArtThemeController.themeRootOverride = null
        stopKoin()
        koinInit = false
        themeRoot.deleteRecursively()
        reloadI18n()
    }

    @Test
    fun clearPersistsAcrossRecreationAndChoosingImageEnablesItAgain() = runDesktopComposeUiTest(width = 1100, height = 1300) {
        val revision = mutableStateOf(0)
        var appliedPath: String? = null
        setContent {
            KoinContext(appKoin) {
                RWPPTheme(default = true) {
                    CompositionLocalProvider(LocalWindowManager provides WindowManager.Middle) {
                        Box(Modifier.fillMaxSize().background(Color(53, 57, 53))) {
                            key(revision.value) {
                                SettingsView({}, "RWPP", {}, { appliedPath = it }, {})
                            }
                        }
                    }
                }
            }
        }
        themeTab()
        saveScreenshot("settings-theme-wide.png")
        onNodeWithText(readI18n("settings.clearBackgroundImage")).performClick()
        runOnIdle {
            assertNull(settings.backgroundImagePath)
            assertFalse(settings.backgroundImageEnabled)
            assertFalse(assertNotNull(saved).backgroundImageEnabled)
            assertNull(saved?.backgroundImagePath)
            assertEquals("", appliedPath)
            revision.value++
        }
        themeTab()
        onNodeWithText(readI18n("settings.clearBackgroundImage")).assertIsNotEnabled()
        onNodeWithText(readI18n("settings.chooseBackgroundImage")).performClick()
        waitForIdle()
        onNodeWithContentDescription(readI18n("settings.saveChanges")).performClick()
        runOnIdle {
            assertTrue(settings.backgroundImageEnabled)
            assertEquals(File("replacement.png").canonicalPath, settings.backgroundImagePath)
            assertEquals(settings.backgroundImagePath, appliedPath)
            assertTrue(assertNotNull(saved).backgroundImageEnabled)
        }
    }

    @Test
    fun themeBackgroundCanBeRemovedOnNarrowScreenWithoutDisablingThemePack() = runDesktopComposeUiTest(width = 380, height = 900) {
        val dir = File(themeRoot, "sample").apply { mkdirs() }
        File(dir, "theme.toml").writeText("[theme]\nid = \"sample\"\nname = \"测试主题\"")
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", File(dir, "background.png"))
        ArtThemeController.scan()
        ArtThemeController.apply("sample")
        settings.backgroundImagePath = null
        setContent {
            KoinContext(appKoin) {
                RWPPTheme(default = true) {
                    CompositionLocalProvider(LocalWindowManager provides WindowManager.Small) {
                        Box(Modifier.fillMaxSize().background(Color(53, 57, 53))) {
                            SettingsView({}, "RWPP", {}, {}, {})
                        }
                    }
                }
            }
        }
        themeTab()
        val choose = onNodeWithText(readI18n("settings.chooseBackgroundImage"))
        val clear = onNodeWithText(readI18n("settings.clearBackgroundImage"))
        clear.performScrollTo()
        saveScreenshot("settings-theme-narrow.png")
        choose.assertIsDisplayed()
        clear.assertIsDisplayed().assertIsEnabled()
        val chooseBounds = choose.fetchSemanticsNode().boundsInRoot
        val clearBounds = clear.fetchSemanticsNode().boundsInRoot
        assertTrue(chooseBounds.right <= 380 && clearBounds.right <= 380)
        assertTrue(chooseBounds.bottom <= clearBounds.top || chooseBounds.right <= clearBounds.left)
        clear.performClick()
        runOnIdle {
            assertFalse(settings.backgroundImageEnabled)
            assertEquals("sample", settings.selectedThemePack)
            assertEquals("sample", ArtThemeController.activeTheme?.id)
        }
        onNodeWithText(readI18n("settings.restoreThemeBackground")).performScrollTo().performClick()
        runOnIdle {
            assertTrue(settings.backgroundImageEnabled)
            assertEquals("sample", settings.selectedThemePack)
        }
    }

    private fun ComposeUiTest.themeTab() {
        onNodeWithText(readI18n("settings.theme")).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.saveScreenshot(name: String) {
        val folder = File("build/theme-ui-screenshots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(folder, name))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>, handler: (String, Array<out Any?>?) -> Any? = { _, _ -> null }): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            handler(method.name, args) ?: when (method.name) {
                "getKoin" -> appKoin
                "isDesktop" -> true
                "i18n" -> when (args?.firstOrNull()?.toString()) {
                    "menus.settings.heading.graphics" -> "图像"
                    "menus.settings.heading.gameplay" -> "游戏"
                    "menus.settings.heading.audio" -> "音频"
                    "menus.settings.heading.developer" -> "开发者"
                    "menus.settings.heading.networking" -> "网络"
                    else -> args?.firstOrNull()?.toString() ?: ""
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    String::class.java -> ""
                    else -> null
                }
            }
        } as T
}
