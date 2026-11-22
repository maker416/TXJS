/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.net.RoomDescription
import io.github.rwpp.widget.defaultRWPPColorScheme
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class RoomClosureUiTest {
    private val room = RoomDescription(uuid = "closed-room", creator = "维护房间", mapName = "峡谷",
        playerCurrentCount = 0, playerMaxCount = 10, listAvailable = false)

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = false) } }) }.koin
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
    }

    @AfterTest
    fun cleanup() {
        stopKoin()
        reloadI18n()
        setI18nOverride(null)
    }

    @Test
    fun unavailableRoomShowsDeveloperMessageWithoutJoinAction() = runDesktopComposeUiTest(width = 520, height = 720) {
        val message = "服务器维护中，预计今晚 20:00 开放。"
        setContent {
            KoinContext(appKoin) {
                MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                    JoinServerRequestDialog(true, {}, room.copy(closedMessage = message), mutableStateListOf()) {}
                }
            }
        }
        onNodeWithText(message).assertIsDisplayed()
        onNodeWithText("暂不可加入").assertIsDisplayed()
        onNodeWithText("已开始对局").assertDoesNotExist()
        onNodeWithText("加入房间").assertDoesNotExist()
        onNodeWithText("加入").assertDoesNotExist()
    }

    @Test
    fun unavailableRoomWithoutClosureMessageShowsInGameStatus() = runDesktopComposeUiTest(width = 520, height = 720) {
        setContent {
            KoinContext(appKoin) {
                MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                    JoinServerRequestDialog(true, {}, room, mutableStateListOf()) {}
                }
            }
        }
        onNodeWithText("已开始对局").assertIsDisplayed()
        onNodeWithText("暂不可加入").assertDoesNotExist()
        onNodeWithText("加入房间").assertDoesNotExist()
        onNodeWithText("加入").assertDoesNotExist()
    }

    @Test
    fun reopenedRoomHidesStaleClosureMessage() = runDesktopComposeUiTest(width = 520, height = 720) {
        val message = "旧维护提示"
        var joins = 0
        setContent {
            KoinContext(appKoin) {
                MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                    JoinServerRequestDialog(true, {}, room.copy(listAvailable = true, closedMessage = message), mutableStateListOf()) { joins++ }
                }
            }
        }
        onNodeWithText(message).assertDoesNotExist()
        onNodeWithText("加入").assertIsDisplayed().performClick()
        assertEquals(1, joins)
    }
}
