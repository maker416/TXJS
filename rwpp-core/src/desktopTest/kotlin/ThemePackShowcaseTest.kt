/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.net.LatestVersionProfile
import io.github.rwpp.net.Net
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.isSafeZipEntryName
import io.github.rwpp.ui.LauncherPage
import io.github.rwpp.ui.ResourceCenterView
import io.github.rwpp.ui.UI
import io.github.rwpp.ui.UIProvider
import io.github.rwpp.ui.launcherPage
import io.github.rwpp.ui.resetNavigation
import io.github.rwpp.widget.RWPPTheme
import io.github.rwpp.widget.WindowManager
import kotlinx.coroutines.runBlocking
import net.peanuuutz.tomlkt.Toml
import org.junit.Assume.assumeTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.compose.KoinContext
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

/**
 * 用真实安装器和当前页面验收外部示例包。默认测试跳过；设置 RWJS_THEME_SHOWCASE_PACK 后启用。
 * 所有安装与配置均隔离在临时目录，预览写入包旁的 previews/，不会启用玩家真实主题。
 */
@OptIn(ExperimentalTestApi::class)
class ThemePackShowcaseTest {
    private var initialized = false
    private lateinit var work: File
    private lateinit var pack: File
    private lateinit var output: File
    private val callbacks = mutableListOf<String>()

    @BeforeTest
    fun setup() {
        val path = System.getenv("RWJS_THEME_SHOWCASE_PACK")
        assumeTrue("Set RWJS_THEME_SHOWCASE_PACK to a .rwtheme file to render the showcase", !path.isNullOrBlank())
        pack = File(path!!).canonicalFile
        assertTrue(pack.isFile, "Missing showcase package: $pack")
        output = File(pack.parentFile, "previews").apply { mkdirs() }
        work = Files.createTempDirectory("rwpp-showcase-").toFile()
        runCatching { stopKoin() }
        appKoin = startKoin {
            modules(module {
                single { Settings(autoCheckUpdate = false, enableAnimations = false, language = "zh", launcherMusicVolume = 0f) }
                single<Net> { stub(Net::class.java) }
                single<AppContext> { stub(AppContext::class.java) }
            })
        }.koin
        initialized = true
        koinInit = true
        logger = LoggerFactory.getLogger("ThemePackShowcaseTest")
        AccountSession.resetForTests()
        AccountSession.networkEnabled = false
        FriendsSession.clear()
        UI.latestVersionProfile = LatestVersionProfile("0.0.0", "", false)
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
        ArtThemeController.themeRootOverride = File(work, "themes")
        val installed = runBlocking { ArtThemeController.import(pack) }
        assertTrue(installed.success, "Real installer rejected showcase: ${installed.errors}")
        assertTrue(installed.warnings.isEmpty(), "Showcase should not rely on ignored fields: ${installed.warnings}")
        assertTrue(ArtThemeController.apply(installed.themeId))
        SwingUtilities.invokeAndWait { resetNavigation(LauncherPage.MainMenu) }
    }

    @AfterTest
    fun teardown() {
        if (!initialized) return
        SwingUtilities.invokeAndWait { resetNavigation(LauncherPage.MainMenu) }
        ArtThemeController.apply(null)
        ArtThemeController.themeRootOverride = null
        UI.latestVersionProfile = null
        FriendsSession.clear()
        AccountSession.resetForTests()
        stopKoin()
        koinInit = false
        work.deleteRecursively()
        reloadI18n()
    }

    @Test
    fun packageAssetsAndManifestAreRealAndPortable() {
        var images = 0
        ZipFile(pack).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue("theme.toml" in names)
            assertTrue(names.all(::isSafeZipEntryName))
            assertFalse(names.any { File(it).extension.lowercase() in setOf("ini", "lua", "jar", "class", "py", "kt", "java") },
                "Development scripts and logic resources do not belong in the distributable")
            zip.entries().toList().filter { !it.isDirectory && File(it.name).extension.lowercase() in setOf("png", "jpg", "jpeg") }
                .forEach { entry ->
                    val bitmap = zip.getInputStream(entry).use { ImageIO.read(it) }
                    assertNotNull(bitmap, "Not a real decodable image: ${entry.name}")
                    assertTrue(bitmap.width > 0 && bitmap.height > 0)
                    images++
                }
        }
        assertTrue(images >= 3, "A visual showcase must include actual assets")
        val digest = pack.inputStream().use { stream ->
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                md.update(buffer, 0, size)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        File(pack.parentFile, pack.name + ".sha256").writeText("$digest  ${pack.name}\n")
        File(output, "VALIDATION.txt").writeText(
            "Package: ${pack.name}\nSHA-256: $digest\nImages decoded: $images\n" +
                "Real Compose pages at logical viewports; desktop rendering, not Android device screenshots.\n" +
                "Production theme installer, page layout, overlays and Crop background are used unchanged.\n"
        )
    }

    @Test
    fun desktopHome() = runDesktopComposeUiTest(width = 1280, height = 720) {
        showHome()
        save("home-desktop-1280x720.png")
        verifyHomeActions()
    }

    @Test
    fun phoneLandscapeHome() = runDesktopComposeUiTest(width = 640, height = 360) {
        showHome()
        save("home-landscape-640x360.png")
        verifyHomeActions()
        save("home-landscape-scrolled-640x360.png")
    }

    @Test
    fun phonePortraitHome() = runDesktopComposeUiTest(width = 400, height = 800) {
        showHome()
        save("home-portrait-400x800.png")
        verifyHomeActions()
        save("home-portrait-scrolled-400x800.png")
    }

    @Test
    fun englishDesktopHome() = runDesktopComposeUiTest(width = 1280, height = 720) {
        useEnglish()
        showHome()
        save("home-desktop-en-1280x720.png")
        verifyHomeActions()
    }

    @Test
    fun englishPhoneHome() = runDesktopComposeUiTest(width = 640, height = 360) {
        useEnglish()
        showHome()
        save("home-landscape-en-640x360.png")
        verifyHomeActions()
    }

    @Test
    fun desktopResourceCenter() = runDesktopComposeUiTest(width = 1280, height = 720) {
        showResources()
        save("resources-desktop-1280x720.png")
        verifyResourceActions()
    }

    @Test
    fun phoneResourceCenter() = runDesktopComposeUiTest(width = 400, height = 800) {
        showResources()
        save("resources-portrait-400x800.png")
        verifyResourceActions()
    }

    private fun useEnglish() {
        appKoin.get<Settings>().language = "en"
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_en.toml").readText())
        ArtThemeController.applyStringOverrides()
    }

    private fun ComposeUiTest.showHome() {
        content {
            UIProvider().MainMenu(
                multiplayer = { callbacks += "multiplayer" }, singlePlayer = { callbacks += "singlePlayer" },
                settings = { callbacks += "settings" }, mods = { callbacks += "mods" },
                extension = { callbacks += "manager" }, resourceBrowser = { callbacks += "resourceBrowser" },
                esports = { callbacks += "esports" }, openSourceInfo = { callbacks += "openSourceInfo" }, account = {}, friends = {},
            )
        }
    }

    private fun ComposeUiTest.showResources() {
        content {
            ResourceCenterView(onExit = {}, onForum = { callbacks += "resourceBrowser" },
                onEsports = { callbacks += "esports" }, onExtensions = { callbacks += "manager" })
        }
    }

    private fun ComposeUiTest.content(page: @androidx.compose.runtime.Composable () -> Unit) {
        setContent {
            KoinContext(appKoin) {
            RWPPTheme(default = true) {
                CompositionLocalProvider(LocalWindowManager provides WindowManager.Middle) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black, Color(0xFF023020), Color.Black)))) {
                        val background = ArtThemeController.activeTheme?.backgroundFile
                        val painter = remember(background) { background?.let { ImageIO.read(it)?.toPainter() } }
                        if (painter != null) Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        page()
                    }
                }
            }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.verifyHomeActions() {
        onNodeWithTag("launcher-main-menu").assertIsDisplayed()
        for (id in listOf("singlePlayer", "multiplayer", "resources", "mods", "settings", "openSourceInfo")) {
            if (ArtThemeController.activeTheme?.spec?.menu?.buttons?.get(id)?.hidden == true) continue
            val card = onNodeWithTag("launcher-card-$id").performScrollTo().assertIsDisplayed()
            val bounds = card.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 48f && bounds.height >= 48f, "Unusable hit area for $id: $bounds")
            if (id == "resources") card.performSemanticsAction(SemanticsActions.OnClick) { it() }
            else card.performClick()
            if (id != "resources") runOnIdle { assertTrue(id in callbacks, "Missing callback: $id") }
        }
        runOnIdle { assertEquals(LauncherPage.ResourceCenter, launcherPage) }
        onNodeWithTag("launcher-theme-manager").assertIsDisplayed().performClick()
        runOnIdle { assertTrue("manager" in callbacks) }
    }

    private fun ComposeUiTest.verifyResourceActions() {
        for (id in listOf("resourceBrowser", "esports", "extensions")) {
            if (ArtThemeController.activeTheme?.spec?.menu?.buttons?.get(id)?.hidden == true) continue
            onNodeWithTag("resource-card-$id").performScrollTo().assertIsDisplayed().performClick()
            runOnIdle { assertTrue((if (id == "extensions") "manager" else id) in callbacks) }
        }
        onNodeWithTag("resource-theme-management").assertIsDisplayed().performClick()
        runOnIdle { assertTrue("manager" in callbacks) }
    }

    private fun ComposeUiTest.save(name: String) {
        // 给本地图片解码和真实页面绘制一个稳定窗口；截图本身来自未经替换的根节点。
        val started = System.nanoTime()
        var previous: IntArray? = null
        var stable = 0
        waitUntil(timeoutMillis = 15_000) {
            val snapshot = onRoot().captureToImage().toAwtImage()
            val pixels = snapshot.getRGB(0, 0, snapshot.width, snapshot.height, null, 0, snapshot.width)
            stable = if (previous?.contentEquals(pixels) == true) stable + 1 else 0
            previous = pixels
            stable >= 3 && System.nanoTime() - started > 1_000_000_000L
        }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, name))
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
