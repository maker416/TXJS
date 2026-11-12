/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.logger
import io.github.rwpp.modDir
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.defaultRWPPColorScheme
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
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 用真实模组页验证手机横屏密度、窄窗口筛选及详情，避免只验证抽出的卡片。 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ModsLayoutUiTest {
    private lateinit var fixtureRoot: File
    private lateinit var fixtures: List<Mod>
    private var chosenImport: File? = null
    private val importedTargets = mutableListOf<File>()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
        runCatching { stopKoin() }
        fixtureRoot = Files.createTempDirectory("rwpp-mod-layout-").toFile()
        fixtures = List(12) { index -> FixtureMod(index + 1, index < 6) }
        logger = LoggerFactory.getLogger("ModsLayoutUiTest")
        appKoin = startKoin {
            modules(module {
                single { Settings(enableAnimations = false) }
                single<ModManager> {
                    stub(ModManager::class.java) { name, _ ->
                        when (name) {
                            "getAllMods" -> fixtures
                            "getKoin" -> appKoin
                            else -> error("Unexpected ModManager access: $name")
                        }
                    }
                }
                single<AppContext> {
                    stub(AppContext::class.java) { name, args ->
                        when (name) {
                            "isDesktop" -> false
                            "isAndroid" -> true
                            "externalStoragePath" -> File(fixtureRoot, args!![0] as String).absolutePath
                            "getKoin" -> appKoin
                            else -> error("Unexpected AppContext access: $name")
                        }
                    }
                }
                single<ExternalHandler> {
                    stub(ExternalHandler::class.java) { name, args ->
                        when (name) {
                            "openFileChooser" -> {
                                @Suppress("UNCHECKED_CAST")
                                val onChoose = args!![1] as (File) -> Unit
                                onChoose(requireNotNull(chosenImport))
                                Unit
                            }
                            "getKoin" -> appKoin
                            else -> error("Unexpected ExternalHandler access: $name")
                        }
                    }
                }
            })
        }.koin
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
        UI.warning = null
    }

    @AfterTest
    fun cleanup() {
        UI.warning = null
        stopKoin()
        setI18nOverride(null)
        reloadI18n()
        importedTargets.forEach { it.delete() }
        fixtureRoot.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test
    fun phoneLandscapeShowsThreeWholeRowsInEachColumn() = checkLandscape(720, 360, 3)

    @Test
    fun shortPhoneLandscapeShowsTwoWholeRowsInEachColumn() = checkLandscape(640, 320, 2)

    @Test
    fun widePhoneLandscapeKeepsThreeWholeRowsInEachColumn() = checkLandscape(873, 393, 3)

    @Test
    fun desktopKeepsBothColumnsAndActionsUsable() = checkLandscape(1280, 720, 3)

    private fun checkLandscape(width: Int, height: Int, wholeRows: Int) =
        runDesktopComposeUiTest(width = width, height = height) {
            setContent { TestPage() }
            awaitInitialFiles()
            val enabled = bounds("mods-list-enabled")
            val disabled = bounds("mods-list-disabled")
            assertTrue(enabled.right <= disabled.left, "enabled and disabled columns overlap")
            assertEquals(enabled.top, disabled.top, "columns should share the same list baseline")
            assertEquals(enabled.bottom, disabled.bottom, "columns should share the same usable height")
            (1..wholeRows).forEach { index ->
                assertWholeRowVisible(index, enabled)
                assertWholeRowVisible(index + 6, disabled)
            }
            assertActionsFit(width, height)
            val summary = bounds("memory-summary")
            assertTrue(summary.bottom <= enabled.top, "memory summary must remain in the top bar")
            onNodeWithTag("memory-details").assertDoesNotExist()
            save("mods-${width}x$height.png")
        }

    @Test
    fun narrowWindowSearchAndEnabledFiltersKeepWorkingAfterToggling() =
        runDesktopComposeUiTest(width = 360, height = 720) {
            setContent { TestPage() }
            awaitInitialFiles()
            onNodeWithTag("mods-list-all").assertIsDisplayed()
            onNodeWithTag("mods-list-enabled").assertDoesNotExist()
            assertTrue(bounds("mod-row-1").width >= 300f, "narrow windows need a full width list")
            listOf("All", "Enabled", "Disabled").forEach {
                assertInsideViewport("mods-filter-$it", 360, 720)
            }
            assertActionsFit(360, 720)
            save("mods-360x720.png")

            onNodeWithTag("mods-search-button").performClick()
            onNodeWithTag("mods-search-field").performTextInput("星际远征")
            waitForIdle()
            onNodeWithTag("mod-row-1").assertIsDisplayed()
            onNodeWithTag("mod-row-2").assertDoesNotExist()
            onNodeWithTag("mods-search-field").performTextClearance()

            onNodeWithTag("mods-filter-Enabled").performClick()
            waitForIdle()
            onNodeWithTag("mod-row-1").assertIsDisplayed()
            onNodeWithTag("mod-row-7").assertDoesNotExist()
            onNodeWithTag("mod-switch-1").assertIsOn().performClick()
            waitForIdle()
            onNodeWithTag("mod-row-1").assertDoesNotExist()

            onNodeWithTag("mods-filter-Disabled").performClick()
            waitForIdle()
            onNodeWithTag("mod-row-1").assertIsDisplayed()
            onNodeWithTag("mod-switch-1").assertIsOff()
            onNodeWithTag("mod-row-2").assertDoesNotExist()
        }

    @Test
    fun shortLandscapeDetailsShowFullDescriptionAndKeepDeleteReachable() =
        runDesktopComposeUiTest(width = 720, height = 360) {
            setContent { TestPage() }
            awaitInitialFiles()
            onNodeWithTag("mod-row-7").performClick()
            waitForIdle()
            onNodeWithTag("mod-details").assertIsDisplayed()
            onNodeWithTag("mod-delete").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            onNodeWithText(description(7), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithText(description(7), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertFalse(layouts.any { it.hasVisualOverflow }, "detail text must not be ellipsized")
            save("mods-details-720x360.png", dialog = true)
            onNodeWithTag("mod-delete").assertIsDisplayed()
            onNodeWithText(readI18n("common.close")).performClick()
            waitForIdle()
            onNodeWithTag("mod-details").assertDoesNotExist()
            onNodeWithTag("mod-row-7").assertIsDisplayed()
        }

    @Test
    fun importingAndImportingAgainRevealDisabledModOutsideEnabledSearch() =
        runDesktopComposeUiTest(width = 360, height = 720) {
            val source = createRwmod(File(fixtureRoot, "layout-import-${System.nanoTime()}.rwmod"),
                "导入后可见的模组", "默认禁用的导入回归模组")
            chosenImport = source
            val target = File(modDir, source.name)
            importedTargets += target
            val importedId = -(target.absolutePath.hashCode() and 0x7FFFFFFF) - 1
            setContent { TestPage() }
            awaitInitialFiles()
            onNodeWithTag("mods-search-button").performClick()

            listOf("mod.importSuccess", "mod.importAlreadyExists").forEach { noticeKey ->
                onNodeWithTag("mods-filter-Enabled").performClick()
                onNodeWithTag("mods-search-field").performTextReplacement("星际远征")
                waitForIdle()
                onNodeWithTag("mod-row-1").assertIsDisplayed()
                onNodeWithTag("mod-row-$importedId").assertDoesNotExist()
                runOnIdle { UI.warning = null }
                onNodeWithText(readI18n("mod.importAction")).performClick()
                val expected = readI18n(noticeKey, I18nType.RWPP, source.name)
                waitUntil(timeoutMillis = 5_000) { UI.warning?.reason == expected }
                waitForIdle()

                onNodeWithTag("mods-filter-All").assertIsSelected()
                assertEquals("", onNodeWithTag("mods-search-field").fetchSemanticsNode()
                    .config[SemanticsProperties.EditableText].text)
                onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("mod-row-$importedId"))
                onNodeWithTag("mod-row-$importedId").assertIsDisplayed()
                onNodeWithTag("mod-switch-$importedId").assertIsOff()
                assertTrue(target.isFile, "import must publish the chosen rwmod")
            }
        }

    @Test
    fun disableAllRemainsAvailableInMoreAndApplyRequiresReload() =
        runDesktopComposeUiTest(width = 360, height = 720) {
            var exits = 0
            setContent { TestPage(onExit = { exits++ }) }
            awaitInitialFiles()
            onNodeWithTag("mods-more").performClick()
            onNodeWithText(readI18n("mod.disableAll")).performClick()
            onNodeWithTag("mods-filter-Enabled").performClick()
            waitForIdle()
            onNodeWithTag("mod-row-1").assertDoesNotExist()
            onNodeWithTag("mod-row-7").assertDoesNotExist()
            onNodeWithTag("mods-filter-Disabled").performClick()
            onNodeWithTag("mod-switch-1").assertIsOff()
            onNodeWithTag("mods-apply").performClick()
            runOnIdle {
                assertEquals(readI18n("mod.applyNeedReload"), UI.warning?.reason)
                assertEquals(0, exits, "apply must not leave the page before disabled units are reloaded")
            }
        }

    private fun ComposeUiTest.awaitInitialFiles() {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("mod-row-1").fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()
    }

    private fun ComposeUiTest.assertWholeRowVisible(id: Int, list: Rect) {
        val row = onNodeWithTag("mod-row-$id")
        row.assertIsDisplayed()
        val full = row.getUnclippedBoundsInRoot()
        assertTrue(full.bottom - full.top >= 48.dp, "row $id lost its touch target: $full")
        assertTrue(full.top.value >= list.top - 1f, "row $id starts above list viewport: $full / $list")
        assertTrue(full.bottom.value <= list.bottom + 1f, "row $id is clipped below list viewport: $full / $list")
        assertTrue(full.left.value >= list.left - 1f && full.right.value <= list.right + 1f,
            "row $id overflows its column: $full / $list")
    }

    private fun ComposeUiTest.assertActionsFit(width: Int, height: Int) {
        assertInsideViewport("mods-actions", width, height)
        listOf("mods-reload", "mods-apply", "mods-more").forEach {
            assertInsideViewport(it, width, height)
            onNodeWithTag(it).assertHeightIsAtLeast(48.dp)
        }
        val reload = bounds("mods-reload")
        val apply = bounds("mods-apply")
        assertTrue(reload.right <= apply.left, "reload and apply must not overlap")
    }

    private fun ComposeUiTest.assertInsideViewport(tag: String, width: Int, height: Int) {
        onNodeWithTag(tag).assertIsDisplayed()
        val node = onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertTrue(node.left.value >= -1f && node.top.value >= -1f &&
            node.right.value <= width + 1f && node.bottom.value <= height + 1f,
            "$tag is outside ${width}x$height: $node")
    }

    private fun ComposeUiTest.bounds(tag: String): Rect =
        onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.save(name: String, dialog: Boolean = false) {
        val target = File("build/reports/mods-ui/$name").apply { parentFile.mkdirs() }
        val roots = onAllNodes(isRoot())
        val screenshot = if (dialog) roots[roots.fetchSemanticsNodes().lastIndex].captureToImage()
            else onRoot().captureToImage()
        ImageIO.write(screenshot.toAwtImage(), "png", target)
    }

    @Composable
    private fun TestPage(onExit: () -> Unit = {}) {
        KoinContext(appKoin) {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            ModsView(onExit, selectedTab = ModsMapsTab.Mods, onTabChange = {}, embedded = true)
                            ExitButton(onExit)
                        }
                    }
                }
            }
        }
    }

    private inner class FixtureMod(override val id: Int, override var isEnabled: Boolean) : Mod {
        override val name = when (id) {
            1 -> "星际远征 · 超长名称与大型单位扩展测试模组"
            7 -> "深海战线 · 自定义单位与战役扩展测试模组"
            else -> "${if (isEnabled) "启用" else "禁用"}模组 $id"
        }
        override val description = description(id)
        override val minVersion = "1.15"
        override val errorMessage: String? = null
        override val path = createRwmod(File(fixtureRoot, "fixture-$id.rwmod"), name, description).absolutePath
        override fun getRamUsed() = "${id * 12} MB"
        override fun getSize() = 1_048_576L
        override fun getBytes() = ByteArray(0)
    }

    private fun description(id: Int) =
        "模组 $id 的完整说明：包含多种自定义单位、建筑和战役地图。" +
            "此段故意保留较长的介绍，验证列表保持紧凑，而详情能够滚动阅读全部内容。" +
            "支持多人合作和遭遇战，选择模组后请重新加载以应用单位配置。"

    private fun createRwmod(file: File, title: String, description: String): File = file.apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mod-info.txt"))
            zip.write("[mod]\ntitle=$title\ndescription=$description\n".toByteArray())
            zip.closeEntry()
        }
    }

    private fun <T> stub(type: Class<T>, result: (String, Array<out Any?>?) -> Any?): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ModsLayoutFixture(${type.simpleName})"
                else -> result(method.name, args)
            }
        } as T
    }
}
