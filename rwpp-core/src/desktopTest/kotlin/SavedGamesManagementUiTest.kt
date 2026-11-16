/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.map.GameMap
import io.github.rwpp.game.map.MapType
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.logger
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.defaultRWPPColorScheme
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/** 使用真实临时存档目录验证管理操作，避免 UI 回归测试依赖引擎或玩家的存档。 */
@OptIn(ExperimentalTestApi::class)
class SavedGamesManagementUiTest {
    private lateinit var root: File

    @BeforeTest
    fun setup() {
        logger = LoggerFactory.getLogger("SavedGamesManagementUiTest")
        root = Files.createTempDirectory("rwjs-saved-games-ui-").toFile()
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = false) } }) }.koin
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
    }

    @AfterTest
    fun cleanup() {
        stopKoin()
        setI18nOverride(null)
        reloadI18n()
        root.deleteRecursively()
    }

    @Test
    fun searchAndTimeOrderKeepTheExactLoadFilename() = runDesktopComposeUiTest(width = 1050, height = 750) {
        val old = saveFile("早期战役.rwsave", 1_600_000_000_000L)
        val latest = saveFile("第二章.v1.2.rwsave", 1_700_000_000_000L)
        var selected: GameMap? = null
        show(onLoad = { selected = it })
        awaitTag("saveLoad:${latest.name}")
        assertTrue(top("saveLoad:${latest.name}") < top("saveLoad:${old.name}"))
        onNodeWithTag("saveSort").performClick()
        assertTrue(top("saveLoad:${old.name}") < top("saveLoad:${latest.name}"))
        onNodeWithTag("saveSearch").performTextInput("v1.2")
        onNodeWithTag("saveLoad:${old.name}").assertDoesNotExist()
        onNodeWithTag("saveLoad:${latest.name}").performClick()
        waitUntil { selected != null }
        assertEquals(latest.name, selected!!.mapName)
        assertEquals("", selected!!.getMapSuffix())
        assertEquals(MapType.SavedGame, selected!!.mapType)
    }

    @Test
    fun refreshFindsFilesCreatedAfterThePageOpened() = runDesktopComposeUiTest(width = 900, height = 700) {
        saveFile("首次存档.rwsave")
        show()
        awaitTag("saveLoad:首次存档.rwsave")
        saveFile("局内刚刚保存.rwsave", System.currentTimeMillis())
        onNodeWithTag("saveLoad:局内刚刚保存.rwsave").assertDoesNotExist()
        onNodeWithTag("saveRefresh").performClick()
        awaitTag("saveLoad:局内刚刚保存.rwsave")
        onNodeWithTag("saveLoad:局内刚刚保存.rwsave").assertIsDisplayed()
    }

    @Test
    fun renameMovesItsMapAndDuplicateNameKeepsBothSaves() = runDesktopComposeUiTest(width = 900, height = 700) {
        val original = saveFile("原名.v2.rwsave")
        val companion = File(root, original.name + ".map").apply { writeText("map companion") }
        val existing = saveFile("已存在.rwsave")
        show()
        awaitTag("saveRename:${original.name}")
        onNodeWithTag("saveRename:${original.name}").performClick()
        onNodeWithTag("saveName").performTextReplacement("新名.v2")
        onNodeWithText(readI18n("mod.cancel")).performClick()
        assertTrue(original.exists())
        onNodeWithTag("saveRename:${original.name}").performClick()
        onNodeWithTag("saveName").performTextReplacement("新名.v2")
        onNodeWithTag("saveRenameConfirm").performClick()
        awaitTag("saveRename:新名.v2.rwsave")
        assertFalse(original.exists())
        assertFalse(companion.exists())
        assertTrue(File(root, "新名.v2.rwsave.map").isFile)
        onNodeWithTag("saveRename:新名.v2.rwsave").performClick()
        onNodeWithTag("saveName").performTextReplacement("已存在")
        onNodeWithTag("saveRenameConfirm").performClick()
        awaitTag("saveFailure")
        assertTrue(File(root, "新名.v2.rwsave").exists())
        assertEquals("saved progress", existing.readText())
        onNodeWithTag("saveLoad:${existing.name}").assertIsDisplayed()
    }

    @Test
    fun deleteWaitsForConfirmationAndRemovesTheCompanionMap() = runDesktopComposeUiTest(width = 900, height = 700) {
        val save = saveFile("待删除.rwsave")
        val companion = File(root, save.name + ".map").apply { writeText("map companion") }
        show()
        awaitTag("saveDelete:${save.name}")
        onNodeWithTag("saveDelete:${save.name}").performClick()
        assertTrue(save.exists())
        onNodeWithText(readI18n("mod.cancel")).performClick()
        assertTrue(save.exists())
        onNodeWithTag("saveDelete:${save.name}").performClick()
        onNodeWithTag("saveDeleteConfirm").performClick()
        waitUntil { onAllNodesWithText(readI18n("saves.empty")).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(save.exists())
        assertFalse(companion.exists())
        onNodeWithTag("saveLoad:${save.name}").assertDoesNotExist()
    }

    @Test
    fun failedLoadKeepsTheSaveVisibleAndAllowsRetry() = runDesktopComposeUiTest(width = 900, height = 700) {
        val save = saveFile("损坏档案.rwsave")
        var attempts = 0
        show(onLoad = { attempts++; error("fixture load failed") })
        awaitTag("saveLoad:${save.name}")
        onNodeWithTag("saveLoad:${save.name}").performClick()
        awaitTag("saveFailure")
        onNodeWithText(readI18n("common.failed") + ": " + readI18n("saves.loadFailed")).assertIsDisplayed()
        assertTrue(save.exists())
        onNodeWithTag("saveLoad:${save.name}").assertIsEnabled().performClick()
        waitUntil { attempts == 2 }
    }

    @Test
    fun portraitShowsAllActionsAndTheThirdManagementTab() = runDesktopComposeUiTest(width = 360, height = 720) {
        val save = saveFile("沙盒进度.2026.rwsave")
        var selectedTab: ModsMapsTab? = null
        show(onTabChange = { selectedTab = it })
        awaitTag("saveLoad:${save.name}")
        assertControlsDisplayed(save)
        onNodeWithText(readI18n("saves.tab")).performClick()
        assertEquals(ModsMapsTab.Saves, selectedTab)
        onNodeWithText(readI18n("maps.switchToMaps")).performClick()
        assertEquals(ModsMapsTab.Maps, selectedTab)
        onNodeWithText(readI18n("maps.switchToMods")).performClick()
        assertEquals(ModsMapsTab.Mods, selectedTab)
        screenshot("saved-games-portrait.png")
    }

    @Test
    fun smallLandscapeKeepsToolbarAndSaveActionsReachable() = runDesktopComposeUiTest(width = 800, height = 400) {
        val save = saveFile("继续之前的战役.rwsave")
        show()
        awaitTag("saveLoad:${save.name}")
        assertControlsDisplayed(save)
        val viewport = onNodeWithTag("savedGamesPage").fetchSemanticsNode().boundsInRoot
        val loadBounds = onNodeWithTag("saveLoad:${save.name}").fetchSemanticsNode().boundsInRoot
        assertTrue(loadBounds.bottom <= viewport.bottom)
        screenshot("saved-games-landscape.png")
    }

    private fun saveFile(name: String, modified: Long = 1_700_000_000_000L): File = File(root, name).apply {
        writeText("saved progress")
        check(setLastModified(modified))
    }

    private fun ComposeUiTest.show(
        onLoad: suspend (GameMap) -> Unit = {},
        onTabChange: (ModsMapsTab) -> Unit = {},
    ) {
        setContent {
            KoinContext(appKoin) {
                MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                    CompositionLocalProvider(LocalWindowManager provides WindowManager.Small) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            SavedGamesManagementView(onLoadSavedGame = onLoad, onTabChange = onTabChange, root = root)
                        }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.awaitTag(tag: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    private fun ComposeUiTest.top(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun ComposeUiTest.assertControlsDisplayed(save: File) {
        listOf("saveSearch", "saveSort", "saveRefresh", "saveImport", "saveLoad:${save.name}",
            "saveRename:${save.name}", "saveDelete:${save.name}").forEach {
            onNodeWithTag(it).assertIsDisplayed()
        }
        onNodeWithText(readI18n("saves.tab")).assertIsDisplayed()
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val file = File("build/test-artifacts/$name").apply { parentFile.mkdirs() }
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file))
    }
}
