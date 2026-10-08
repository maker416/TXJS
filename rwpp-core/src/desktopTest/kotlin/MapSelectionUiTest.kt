/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.Game
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.map.GameMap
import io.github.rwpp.game.map.MapType
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.logger
import io.github.rwpp.widget.defaultRWPPColorScheme
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlin.test.*

/** 实际地图选择组件的尺寸、点击、筛选索引与刷新位置回归。 */
@OptIn(ExperimentalTestApi::class)
class MapSelectionUiTest {
    @BeforeTest
    fun setup() {
        logger = LoggerFactory.getLogger("MapSelectionUiTest")
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
    }

    @Test
    fun desktopMapPickerUsesReadableColumnsAndCloseHitTarget() = runDesktopComposeUiTest(width = 1200, height = 800) {
        var closed = 0
        setContent { Browser(onDismiss = { closed++ }) }
        waitForIdle()
        val first = cardBounds("地图 0")
        val second = cardBounds("地图 1")
        assertEquals(first.top, second.top)
        assertTrue(first.width >= 180f)
        assertTrue(second.left > first.right)
        assertEquals(200f, first.height)
        assertCloseClick { closed }
        save("map-picker-desktop.png")
    }

    @Test
    fun phonePortraitStacksToolsAndKeepsMapNamesAndCloseClickable() = runDesktopComposeUiTest(width = 360, height = 720) {
        var closed = 0
        setContent { Browser(onDismiss = { closed++ }) }
        waitForIdle()
        val first = cardBounds("地图 0")
        val second = cardBounds("地图 1")
        assertEquals(first.left, second.left)
        assertTrue(second.top > first.bottom)
        val search = onNodeWithTag("mapSearch").fetchSemanticsNode().boundsInRoot
        val type = onNodeWithTag("mapType").fetchSemanticsNode().boundsInRoot
        assertTrue(search.top >= type.bottom)
        onNodeWithTag("mapSearch").performClick().performTextInput("地图 1")
        onNode(hasText("地图 1") and hasAnyAncestor(hasTestTag("mapGrid")), useUnmergedTree = true).assertIsDisplayed()
        assertCloseClick { closed }
        save("map-picker-phone-portrait.png")
    }

    @Test
    fun phoneLandscapeRetainsGridAndAllToolbarControls() = runDesktopComposeUiTest(width = 800, height = 400) {
        var closed = 0
        setContent { Browser(onDismiss = { closed++ }) }
        waitForIdle()
        assertEquals(cardBounds("地图 0").top, cardBounds("地图 1").top)
        onNodeWithTag("mapType").assertIsDisplayed()
        onNodeWithTag("mapSearch").assertIsDisplayed()
        onNodeWithTag("mapRefresh").assertIsDisplayed()
        onNodeWithText("地图 0", useUnmergedTree = true).assertIsDisplayed()
        assertCloseClick { closed }
        save("map-picker-phone-landscape.png")
    }

    @Test
    fun filteredSelectionReturnsSourceIndexAndRefreshPreservesScrolledPosition() =
        runDesktopComposeUiTest(width = 420, height = 740) {
            var selectedIndex = -1
            var refreshes = 0
            setContent {
                Browser(
                    loadMaps = { type, refresh ->
                        if (refresh) refreshes++
                        testMaps(type)
                    },
                    onSelectedMap = { index, _ -> selectedIndex = index },
                )
            }
            waitForIdle()
            onNodeWithTag("mapGrid").performScrollToIndex(12)
            waitForIdle()
            val before = cardBounds("地图 12")
            onNodeWithTag("mapRefresh").performClick()
            waitForIdle()
            assertEquals(1, refreshes)
            assertEquals(before, cardBounds("地图 12"))
            onNodeWithTag("mapSearch").performTextInput("海岛")
            waitForIdle()
            onNodeWithText("海岛 8", useUnmergedTree = true).performClick()
            assertEquals(8, selectedIndex)
        }

    @Test
    fun failedEnumerationShowsErrorAndRefreshCanRecover() = runDesktopComposeUiTest {
        var attempts = 0
        setContent {
            Browser(loadMaps = { type, _ ->
                attempts++
                if (attempts == 1) error("map directory unavailable")
                testMaps(type)
            })
        }
        waitForIdle()
        onNodeWithText("失败: map directory unavailable", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("mapRefresh").performClick()
        waitForIdle()
        assertEquals(2, attempts)
        onNodeWithText("地图 0", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun unavailableInitialTypeFallsBackToAllowedType() = runDesktopComposeUiTest {
        var loaded: MapType? = null
        setContent {
            Browser(
                mapTypes = listOf(MapType.SkirmishMap),
                initialType = MapType.CustomMap,
                loadMaps = { type, _ -> loaded = type; testMaps(type) },
            )
        }
        waitForIdle()
        assertEquals(MapType.SkirmishMap, loaded)
        onNodeWithText("遭遇战地图", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun portraitPlayerPanelRemainsBoundedInsideTheRoomScrollContainer() =
        runDesktopComposeUiTest(width = 360, height = 720) {
            val room = stub(GameRoom::class.java)
            val game = stub(Game::class.java)
            setContent {
                KoinContext(appKoin) {
                    MaterialTheme {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            CompactPlayerPanel(
                                players = emptyList(), room = room, game = game, update = false,
                                playerListState = rememberLazyListState(), rowShape = RoundedCornerShape(6.dp),
                                onPlayerClick = {}, enableAnimations = false, expandVertically = false,
                            )
                        }
                    }
                }
            }
            waitForIdle()
            val height = onNodeWithTag("compactRoomPlayers").fetchSemanticsNode().boundsInRoot.height
            assertTrue(height in 140f..320f)
        }

    @Test
    fun roomChatSendsOnlyOnEnterDownAndKeepsEditingKeysWorking() = runDesktopComposeUiTest(width = 700, height = 200) {
        val sent = mutableListOf<String>()
        var message by mutableStateOf("")
        setContent {
            KoinContext(appKoin) {
                MaterialTheme {
                    RoomChatMessageTextField(
                        chatMessage = message,
                        focusRequester = remember { FocusRequester() },
                        onChatMessageChange = { message = it },
                        onSend = { sent += it },
                        modifier = Modifier.testTag("chat"),
                    )
                }
            }
        }
        onNodeWithTag("chat").performClick().performTextInput("abc")
        onNodeWithTag("chat").performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.Backspace) }
        assertEquals("ac", message)
        onNodeWithTag("chat").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        assertEquals(listOf("ac"), sent)
        assertEquals("", message)
        onNodeWithTag("chat").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("ac"), sent)
    }

    @Composable
    private fun Browser(
        onDismiss: () -> Unit = {},
        mapTypes: List<MapType> = MapType.entries,
        initialType: MapType = MapType.SavedGame,
        loadMaps: (MapType, Boolean) -> List<GameMap> = { type, _ -> testMaps(type) },
        onSelectedMap: (Int, GameMap) -> Unit = { _, _ -> },
    ) {
        KoinContext(appKoin) {
            MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                Box(Modifier.fillMaxSize().background(Color(0xFF353935)), contentAlignment = Alignment.Center) {
                    MapSelectionContent(mapTypes, 0, initialType, loadMaps, onDismiss, onSelectedMap)
                }
            }
        }
    }

    private fun testMaps(type: MapType): List<GameMap> = List(24) { index ->
        object : GameMap {
            override val id = index
            override val mapName = if (index == 8) "海岛 8" else "地图 $index"
            override val mapType = type
            override fun openInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun openImageInputStream() = null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            error("Unexpected engine access from empty player panel: ${method.name}")
        } as T

    private fun ComposeUiTest.cardBounds(name: String) =
        onNode(hasClickAction() and hasText(name)).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.assertCloseClick(closed: () -> Int) {
        val close = onNodeWithContentDescription("关闭")
        val bounds = close.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= 48f && bounds.height >= 48f)
        close.performTouchInput { click() }
        assertEquals(1, closed())
    }

    private fun ComposeUiTest.save(name: String) {
        val target = File("build/reports/ui-stability/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target))
    }
}
