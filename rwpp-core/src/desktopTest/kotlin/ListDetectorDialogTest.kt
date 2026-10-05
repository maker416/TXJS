/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.Game
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.Player
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.defaultRWPPColorScheme
import net.peanuuutz.tomlkt.Toml
import org.koin.core.context.startKoin
import org.koin.compose.KoinContext
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ListDetectorDialogTest {
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
    fun hostSeesExplanationAndCanKickWithOneClick() = runDesktopComposeUiTest(width = 480, height = 500) {
        var kicks = 0
        setContent {
            KoinContext(appKoin) {
                MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                    CompositionLocalProvider(LocalWindowManager provides WindowManager.Small) {
                        ListDetectorDialog(true, canKick = true, onDismiss = {}, onKick = { kicks++ })
                    }
                }
            }
        }
        onNodeWithText("列表探测器").assertIsDisplayed()
        onNodeWithText("如果超过 30 秒还没走", substring = true).assertIsDisplayed()
        onNodeWithText("一键踢出").assertIsEnabled().performClick()
        assertEquals(1, kicks)
        val output = File("build/reports/list-detector/dialog_host.png").apply { parentFile.mkdirs() }
        ImageIO.write(onNodeWithTag("listDetectorDialog").captureToImage().toAwtImage(), "png", output)
    }

    @Test
    fun probeKeepsItsSlotAndInfoBadgeOpensTheExplanation() = runDesktopComposeUiTest(width = 480, height = 500) {
        val detector = stub(Player::class.java) {
            when (it) {
                "getName" -> "列表探测器"
                "getColor", "getStartingUnit" -> -1
                "getSpawnPoint", "getTeam" -> 1
                "getPing" -> "-"
                "isAI", "isSpectator" -> false
                else -> error("Unexpected probe call: $it")
            }
        }
        val room = stub(GameRoom::class.java) {
            when (it) {
                "isHost" -> true
                else -> error("Unexpected room call: $it")
            }
        }
        val game = stub(Game::class.java) {
            if (it == "getGameRoom") room else error("Unexpected game call: $it")
        }
        appKoin.loadModules(listOf(module { single<Game> { game } }))
        var opened by mutableStateOf(false)
        var rowClicks = 0
        setContent {
            KoinContext(appKoin) {
                MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                    CompositionLocalProvider(LocalWindowManager provides WindowManager.Small) {
                        Column {
                            RoomPlayerTableRow(detector, room, game, update = false, compact = true,
                                onPlayerClick = { rowClicks++ }, onViewProfile = { opened = true })
                            ListDetectorDialog(opened, canKick = true, onDismiss = { opened = false }, onKick = {})
                        }
                    }
                }
            }
        }
        onNodeWithText("列表探测器").assertIsDisplayed()
        onNodeWithText("2").assertIsDisplayed()
        onNodeWithText("B").assertIsDisplayed()
        onNodeWithContentDescription("列表探测器").assertIsDisplayed().performClick()
        onNodeWithText("如果超过 30 秒还没走", substring = true).assertIsDisplayed()
        assertEquals(0, rowClicks, "badge click must not also trigger the player row")
    }

    @Test
    fun memberCanReadTheExplanationButCannotKickOnANarrowScreen() =
        runDesktopComposeUiTest(width = 320, height = 500) {
            setContent {
                KoinContext(appKoin) {
                    MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                        CompositionLocalProvider(LocalWindowManager provides WindowManager.Small) {
                            ListDetectorDialog(true, canKick = false, onDismiss = {}, onKick = { error("member cannot kick") })
                        }
                    }
                }
            }
            onNodeWithText("一键踢出").assertIsDisplayed().assertIsNotEnabled()
            onNodeWithText("仅房主可在等待室", substring = true).assertIsDisplayed()
            onNodeWithText("关闭").assertIsDisplayed()
            val output = File("build/reports/list-detector/dialog_member.png").apply { parentFile.mkdirs() }
            ImageIO.write(onNodeWithTag("listDetectorDialog").captureToImage().toAwtImage(), "png", output)
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>, value: (String) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "equals" -> instance === args!![0]
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> type.simpleName
                else -> value(method.name)
            }
        } as T
}
