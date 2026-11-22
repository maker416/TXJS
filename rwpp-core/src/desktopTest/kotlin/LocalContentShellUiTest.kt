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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.customMapDir
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.logger
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.defaultRWPPColorScheme
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory

/**
 * 验证本地内容页（模组 / 地图 / 存档）在移动端竖屏、横屏与桌面端下的整体连贯性：
 * - 顶栏容器、分段切换器、关闭按钮与底栏在三标签间保持零偏移
 * - 会话内切换标签不丢状态、不闪烁回载
 * - 存档页在移动端竖屏与横屏下可同时展示清晰操作与完整列表
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LocalContentShellUiTest {
    private lateinit var fixtureRoot: File
    private lateinit var mods: List<Mod>
    private val createdMapFiles = mutableListOf<File>()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
        runCatching { stopKoin() }
        fixtureRoot = Files.createTempDirectory("rwpp-local-content-shell-").toFile()
        mods = List(8) { index -> FixtureMod(index + 1, index < 4) }
        logger = LoggerFactory.getLogger("LocalContentShellUiTest")
        appKoin = startKoin {
            modules(module {
                single { Settings(enableAnimations = false) }
                single<ModManager> {
                    stub(ModManager::class.java) { name, _ ->
                        when (name) {
                            "getAllMods" -> mods
                            "getKoin" -> appKoin
                            else -> error("Unexpected ModManager access: $name")
                        }
                    }
                }
                single<Game> {
                    stub(Game::class.java) { name, _ ->
                        when (name) {
                            "getAllMaps" -> emptyList<Any>()
                            "getKoin" -> appKoin
                            else -> error("Unexpected Game access: $name")
                        }
                    }
                }
                single<AppContext> {
                    stub(AppContext::class.java) { name, args ->
                        when (name) {
                            "isDesktop" -> false
                            "isAndroid" -> true
                            "externalStoragePath" -> File(fixtureRoot, args!![0] as String).absolutePath
                            "internalStoragePath" -> File(fixtureRoot, "internal/${args!![0]}").absolutePath
                            "getKoin" -> appKoin
                            else -> error("Unexpected AppContext access: $name")
                        }
                    }
                }
                single<ExternalHandler> {
                    stub(ExternalHandler::class.java) { name, _ ->
                        when (name) {
                            "getKoin" -> appKoin
                            else -> error("Unexpected ExternalHandler access: $name")
                        }
                    }
                }
            })
        }.koin

        populateMaps()
        populateSaves()
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
        UI.pendingModsMapsTab = ModsMapsTab.Mods
        UI.warning = null
    }

    @AfterTest
    fun cleanup() {
        UI.warning = null
        UI.pendingModsMapsTab = null
        createdMapFiles.forEach { it.delete() }
        createdMapFiles.clear()
        stopKoin()
        setI18nOverride(null)
        reloadI18n()
        fixtureRoot.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test
    fun portraitThreeTabsKeepHeaderAndBottomBarAligned() = verifyShellContinuity(
        name = "portrait",
        width = 360,
        height = 720,
        windowManager = WindowManager.Small,
    )

    @Test
    fun landscapeThreeTabsKeepHeaderAndBottomBarAligned() = verifyShellContinuity(
        name = "landscape",
        width = 800,
        height = 400,
        windowManager = WindowManager.Small,
    )

    @Test
    fun desktopThreeTabsKeepHeaderAndBottomBarAligned() = verifyShellContinuity(
        name = "desktop",
        width = 1280,
        height = 720,
        windowManager = WindowManager.Large,
    )

    @Test
    fun animatedTabSwitchingCompletesSmoothlyWithAlignedChrome() = runDesktopComposeUiTest(width = 800, height = 400) {
        appKoin.get<Settings>().enableAnimations = true
        setContent {
            KoinContext(appKoin) {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalWindowManager provides WindowManager.Small,
                ) {
                    MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF1A201A))) {
                            ModsAndMapsView(onLoadSavedGame = {}, onExit = {})
                        }
                    }
                }
            }
        }
        awaitTag("mod-row-1")
        val modsChrome = captureChromeBounds()
        onNodeWithTag("local-content-tab-Saves").performClick()
        awaitTag("saveLoad:沙漠要塞·重装反击.rwsave")
        val savesChrome = captureChromeBounds()
        assertChromeAligned("Animated Mods -> Saves", modsChrome, savesChrome)
        onNodeWithTag("local-content-tab-Maps").performClick()
        awaitTag("mapsGrid")
        val mapsChrome = captureChromeBounds()
        assertChromeAligned("Animated Saves -> Maps", savesChrome, mapsChrome)
    }

    private fun verifyShellContinuity(
        name: String,
        width: Int,
        height: Int,
        windowManager: WindowManager,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            KoinContext(appKoin) {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalWindowManager provides windowManager,
                ) {
                    MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF1A201A))) {
                            ModsAndMapsView(onLoadSavedGame = {}, onExit = {})
                        }
                    }
                }
            }
        }

        // 1. 模组页
        awaitTag("mod-row-1")
        val modsChrome = captureChromeBounds()
        saveShot("local-content-$name-mods.png")

        // 2. 切到地图页：验证顶栏、分段器、关闭按钮、底栏完全无跳动
        onNodeWithTag("local-content-tab-Maps").performClick()
        awaitTag("mapsGrid")
        val mapsChrome = captureChromeBounds()
        assertChromeAligned("Mods -> Maps ($name)", modsChrome, mapsChrome)
        saveShot("local-content-$name-maps.png")

        // 3. 切到存档页：验证顶栏、分段器、关闭按钮、底栏完全无跳动，且多个存档立即可见
        onNodeWithTag("local-content-tab-Saves").performClick()
        awaitTag("saveLoad:沙漠要塞·重装反击.rwsave")
        val savesChrome = captureChromeBounds()
        assertChromeAligned("Maps -> Saves ($name)", mapsChrome, savesChrome)
        onNodeWithTag("saveLoad:冰原前线·双线防守.rwsave").assertIsDisplayed()
        saveShot("local-content-$name-saves.png")

        // 4. 在存档页筛选并切回模组页，再切回存档页：保留筛选与排序状态且零闪烁
        onNodeWithTag("saveSearch").performTextInput("沙漠")
        waitForIdle()
        onNodeWithTag("saveLoad:沙漠要塞·重装反击.rwsave").assertIsDisplayed()
        onNodeWithTag("saveLoad:冰原前线·双线防守.rwsave").assertDoesNotExist()

        onNodeWithTag("local-content-tab-Mods").performClick()
        waitForIdle()
        onNodeWithTag("mod-row-1").assertIsDisplayed()

        onNodeWithTag("local-content-tab-Saves").performClick()
        waitForIdle()
        onNodeWithTag("saveLoad:沙漠要塞·重装反击.rwsave").assertIsDisplayed()
        onNodeWithTag("saveLoad:冰原前线·双线防守.rwsave").assertDoesNotExist()
    }

    private data class ChromeBounds(
        val header: Rect,
        val segmented: Rect,
        val close: Rect,
        val bottomBar: Rect,
    )

    private fun ComposeUiTest.captureChromeBounds(): ChromeBounds = ChromeBounds(
        header = onNodeWithTag("local-content-header").fetchSemanticsNode().boundsInRoot,
        segmented = onNodeWithTag("local-content-segmented").fetchSemanticsNode().boundsInRoot,
        close = onNodeWithTag("local-content-close").fetchSemanticsNode().boundsInRoot,
        bottomBar = onNodeWithTag("local-content-bottom-bar").fetchSemanticsNode().boundsInRoot,
    )

    private fun assertChromeAligned(label: String, expected: ChromeBounds, actual: ChromeBounds) {
        assertRectAlmostEqual("$label header", expected.header, actual.header)
        assertRectAlmostEqual("$label segmented", expected.segmented, actual.segmented)
        assertRectAlmostEqual("$label close", expected.close, actual.close)
        assertRectAlmostEqual("$label bottomBar", expected.bottomBar, actual.bottomBar)
    }

    private fun assertRectAlmostEqual(label: String, expected: Rect, actual: Rect) {
        val tol = 1.0f
        assertTrue(
            abs(expected.left - actual.left) <= tol &&
                abs(expected.top - actual.top) <= tol &&
                abs(expected.right - actual.right) <= tol &&
                abs(expected.bottom - actual.bottom) <= tol,
            "$label jumped: expected=$expected actual=$actual",
        )
    }

    private fun ComposeUiTest.awaitTag(tag: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()
    }

    private fun ComposeUiTest.saveShot(name: String) {
        val target = File("build/reports/local-content-ui/$name").apply { parentFile.mkdirs() }
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target))
    }

    private fun populateMaps() {
        val dir = File(customMapDir).apply { mkdirs() }
        val specs = listOf(
            Triple("[p4]苍穹群岛", java.awt.Color(42, 118, 152), java.awt.Color(156, 206, 174)),
            Triple("[p8]霜雪峡谷", java.awt.Color(218, 232, 240), java.awt.Color(148, 176, 198)),
            Triple("[p6]熔岩高地", java.awt.Color(122, 46, 32), java.awt.Color(232, 142, 64)),
            Triple("[p2]铁壁要塞", java.awt.Color(58, 74, 54), java.awt.Color(122, 152, 92)),
            Triple("[p10]巨型海峡争霸战", java.awt.Color(30, 68, 108), java.awt.Color(74, 140, 128)),
            Triple("[p4]夜幕霓虹港", java.awt.Color(28, 30, 56), java.awt.Color(98, 76, 156)),
        )
        specs.forEachIndexed { index, (name, c1, c2) ->
            val tmx = File(dir, "$name.tmx").apply {
                writeText("<map width='100' height='100'><!-- ${"x".repeat((index + 1) * 8000)} --></map>")
            }
            val thumb = File(dir, "${name}_map.png")
            val img = BufferedImage(160, 100, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.paint = GradientPaint(0f, 0f, c1, 160f, 100f, c2)
            g.fillRect(0, 0, 160, 100)
            g.color = java.awt.Color(255, 255, 255, 70)
            g.drawRect(14, 14, 132, 72)
            g.dispose()
            ImageIO.write(img, "png", thumb)
            createdMapFiles += tmx
            createdMapFiles += thumb
        }
    }

    private fun populateSaves() {
        val dir = File(fixtureRoot, "saves").apply { mkdirs() }
        val internalDir = File(fixtureRoot, "internal/saves").apply { mkdirs() }
        val baseTime = 1_760_000_000_000L
        listOf(
            Triple("沙漠要塞·重装反击.rwsave", baseTime + 500_000L, 1_480_000),
            Triple("冰原前线·双线防守.rwsave", baseTime + 400_000L, 620_000),
            Triple("海岛核电极限运营 v2.4.rwsave", baseTime + 300_000L, 2_350_000),
            Triple("战役第七关·午夜突袭.rwsave", baseTime + 200_000L, 384_000),
        ).forEach { (name, time, size) ->
            File(dir, name).apply {
                writeBytes(ByteArray(size))
                check(setLastModified(time))
            }
        }
        File(internalDir, "旧版迁移存档.rwsave").apply {
            writeBytes(ByteArray(210_000))
            check(setLastModified(baseTime + 100_000L))
        }
    }

    private inner class FixtureMod(override val id: Int, override var isEnabled: Boolean) : Mod {
        override val name = when (id) {
            1 -> "星际远征 · 大型单位扩展包"
            2 -> "重装钢铁洪流 v3.8"
            3 -> "现代海空联合战区"
            4 -> "高清战术地形材质增强"
            5 -> "深海战线 · 潜艇与航母扩展"
            6 -> "末日生化危机生存包"
            7 -> "复古经典红警单位合集"
            else -> "测试沙盒实验单位 $id"
        }
        override val description = "用于验证本地内容统一外壳与布局的测试模组。"
        override val minVersion = "1.15"
        override val errorMessage: String? = null
        override val path = File(fixtureRoot, "shell-mod-$id.rwmod").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("mod-info.txt"))
                zip.write("[mod]\ntitle=$name\ndescription=$description\n".toByteArray())
                zip.closeEntry()
            }
        }.absolutePath
        override fun getRamUsed() = "${id * 16} MB"
        override fun getSize() = 2_097_152L
        override fun getBytes() = ByteArray(0)
    }

    private fun <T> stub(type: Class<T>, result: (String, Array<out Any?>?) -> Any?): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "LocalContentShellFixture(${type.simpleName})"
                else -> result(method.name, args)
            }
        } as T
    }
}
