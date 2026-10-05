/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.net.RoomDescription
import io.github.rwpp.widget.defaultRWPPColorScheme
import net.peanuuutz.tomlkt.Toml
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 用真实 Compose 卡片验证空地图回归、同步能力提示和窄屏换行，并输出截图供视觉检查。 */
@OptIn(ExperimentalTestApi::class)
class RoomListCardUiTest {
    private val publicRoom = RoomDescription(
        uuid = "public", creator = "公开房间-R7471", mapName = "",
        playerCurrentCount = 1, playerMaxCount = 10, label = "默认|模组同步",
    )
    private val moddedRoom = publicRoom.copy(
        uuid = "modded", creator = "星际战争 · 新手友好", mapName = "冰封群岛.tmx",
        playerCurrentCount = 3, playerMaxCount = 8, label = "休闲|模组同步",
        mods = """[{"modName":"星际战争"},{"modName":"更多单位"}]""",
    )

    @BeforeTest
    fun setup() = loadLanguage("zh")

    @AfterTest
    fun tearDown() {
        setI18nOverride(null)
        reloadI18n()
    }

    @Test
    fun landscapeListShowsRoomNamesAndQuietSyncBadges() = runDesktopComposeUiTest(width = 720, height = 640) {
        val rooms = listOf(
            publicRoom,
            publicRoom.copy(
                uuid = "ranked", creator = "团队混战-排位赛", mapName = "团队混战-排位赛",
                label = "排位", playerCurrentCount = 0, playerMaxCount = 9_999_999,
            ),
            moddedRoom,
            moddedRoom.copy(
                uuid = "manual", creator = "大型战役 · 自备模组", label = "合作", requiredPassword = true,
            ),
            publicRoom.copy(
                uuid = "full", creator = "周末原版对战", mapName = "峡谷.tmx",
                label = "休闲", playerCurrentCount = 10,
            ),
        ) + listOf("UN混战结盟服务器", "列国排位【单人混战】", "尸如潮水生存", "小块地排位", "娱乐生存")
            .mapIndexed { index, name ->
                publicRoom.copy(
                    uuid = "lobby-$index", creator = name, mapName = if (index == 0) "" else name,
                    label = "排位", playerCurrentCount = 0, playerMaxCount = 9_999_999,
                )
            }
        setContent { TestList(rooms) }
        waitForIdle()
        save("room_list_landscape.png")
        assertTextFits("公开房间-R7471")
        assertTextFits("地图 · 暂未提供")
        assertTextFits("0 人")
        assertTextFits("2 个模组", index = 0)
        onAllNodesWithText("模组同步", useUnmergedTree = true).assertCountEquals(2)
        onNodeWithText("已满员", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("9999999", substring = true, useUnmergedTree = true).assertDoesNotExist()
        val first = cardBounds("公开房间-R7471")
        val second = cardBounds("团队混战-排位赛")
        val last = cardBounds("周末原版对战")
        assertEquals(first.top, second.top)
        assertEquals(first.height, second.height)
        assertTrue(second.left > first.right)
        assertEquals(first.width, last.width, "last card must retain its column width")
    }

    @Test
    fun narrowListWrapsBadgesAndKeepsTitleAndPlayerCountReadable() =
        runDesktopComposeUiTest(width = 320, height = 700) {
            val longRoom = moddedRoom.copy(
                creator = "欢迎萌新加入的星际战争合作房间 · 周末一起玩",
                label = "休闲|合作|新手友好|模组同步", requiredPassword = true,
            )
            val legacyRoom = publicRoom.copy(
                uuid = "legacy", creator = "老列表房间", label = "休闲",
                listAvailable = false, listAvailabilityKnown = false,
            )
            setContent { TestList(listOf(publicRoom, longRoom, legacyRoom)) }
            waitForIdle()
            save("room_list_portrait.png")
            assertTextFits("公开房间-R7471")
            assertTextFits("1/10")
            assertTextFits("3/8")
            assertTextFits("地图 · 冰封群岛")
            assertTextFits("需要密码")
            assertTextFits("状态未确定")
            onAllNodesWithText("模组同步", useUnmergedTree = true).assertCountEquals(2)
        }

    @Test
    fun englishLargeFontRetainsSyncFlagAndClickWhenNameAndMapAreMissing() =
        runDesktopComposeUiTest(width = 320, height = 500) {
            loadLanguage("en")
            var clicks = 0
            val unknownRoom = publicRoom.copy(creator = "", roomOwner = "Unnamed", requiredPassword = true)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.3f)) {
                    TestList(listOf(unknownRoom), light = true, onClick = { clicks++ })
                }
            }
            waitForIdle()
            save("room_list_english_large_font.png")
            assertTextFits("Unnamed room")
            assertTextFits("Map · Not provided")
            assertTextFits("Mod Sync")
            assertTextFits("Password required")
            onNodeWithText("Unnamed room", useUnmergedTree = true).performClick()
            assertEquals(1, clicks)
        }

    @Test
    fun wideGridUsesThreeColumnsAndReflowsAfterResizeAndRefresh() =
        runDesktopComposeUiTest(width = 1024, height = 640) {
            var viewport by mutableStateOf(1000.dp)
            var rooms by mutableStateOf(List(8) { publicRoom.copy(uuid = "room-$it", creator = "房间 $it") })
            var selected: String? = null
            setContent {
                Box(Modifier.width(viewport).fillMaxHeight()) {
                    TestList(rooms, onClick = { selected = it.uuid })
                }
            }
            waitForIdle()
            val first = cardBounds("房间 0")
            val second = cardBounds("房间 1")
            val third = cardBounds("房间 2")
            assertEquals(first.top, second.top)
            assertEquals(first.top, third.top)
            assertTrue(second.left > first.right && third.left > second.right)
            assertEquals(second.width, cardBounds("房间 7").width)
            save("room_grid_three_columns.png")
            onNodeWithText("房间 2", useUnmergedTree = true).performClick()
            assertEquals("room-2", selected)

            viewport = 360.dp
            rooms = rooms.drop(1).take(3)
            waitForIdle()
            val narrowFirst = cardBounds("房间 1")
            val narrowSecond = cardBounds("房间 2")
            assertEquals(narrowFirst.left, narrowSecond.left)
            assertTrue(narrowSecond.top > narrowFirst.bottom)
            onNodeWithText("房间 2", useUnmergedTree = true).performClick()
            assertEquals("room-2", selected)
        }

    @Test
    fun finalGridRowCanScrollAboveBottomActions() = runDesktopComposeUiTest(width = 720, height = 360) {
        val rooms = List(40) { publicRoom.copy(uuid = "room-$it", creator = "房间 $it") }
        setContent {
            MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                Scaffold(bottomBar = { Box(Modifier.fillMaxWidth().height(64.dp)) { Text("底部操作") } }) { padding ->
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val columns = roomCardColumnCount(maxWidth, LocalDensity.current.fontScale)
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 12.dp),
                        ) {
                            roomCardRows(rooms, columns) {}
                        }
                    }
                }
            }
        }
        onNode(hasScrollToIndexAction()).performScrollToIndex((rooms.size - 1) / roomCardColumnCount(720.dp))
        waitForIdle()
        assertTrue(cardBounds("房间 39").bottom <= 360f - 64f, "bottom actions overlap the final card")
    }

    private fun loadLanguage(language: String) {
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_$language.toml").readText())
    }

    @Composable
    private fun TestList(rooms: List<RoomDescription>, light: Boolean = false, onClick: (RoomDescription) -> Unit = {}) {
        MaterialTheme(colorScheme = if (light) lightColorScheme() else defaultRWPPColorScheme) {
            BoxWithConstraints(
                Modifier.fillMaxSize().background(if (light) Color(0xFFF1F4F0) else Color(0xFF353935))
                    .padding(12.dp),
            ) {
                val columns = roomCardColumnCount(maxWidth, LocalDensity.current.fontScale)
                LazyColumn(Modifier.fillMaxSize()) {
                    roomCardRows(rooms, columns, onRoomClick = onClick)
                }
            }
        }
    }

    private fun ComposeUiTest.cardBounds(name: String) =
        onNode(hasClickAction() and hasText(name)).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.assertTextFits(text: String, index: Int = 0) {
        val node = onAllNodesWithText(text, useUnmergedTree = true)[index]
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty(), "missing text layout: $text")
        // 字形宽度的像素取整可能使 didOverflowWidth 为 true；核实实际省略与垂直裁切。
        assertFalse(
            layouts.any { layout ->
                layout.didOverflowHeight || (0 until layout.lineCount).any(layout::isLineEllipsized)
            },
            "text clipped: $text",
        )
    }

    private fun ComposeUiTest.save(name: String) {
        val target = File("build/reports/room-list-ui/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target))
    }
}
