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
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.use
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.widget.UiMotionTheme
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.defaultRWPPColorScheme
import io.github.rwpp.widget.setUiAnimationsEnabled
import io.github.rwpp.widget.themes
import net.peanuuutz.tomlkt.Toml
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class PublishToListDialogUiTest {
    private lateinit var settings: Settings

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        settings = Settings(enableAnimations = false)
        appKoin = startKoin {
            modules(module {
                single { settings }
            })
        }.koin
        loadLanguage("zh")
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        setI18nOverride(null)
        reloadI18n()
    }

    @Test
    fun disabledMotionSchedulesZeroFramesAndLiveToggleAnimatesInDrawPhase() {
        var parentCompositions = 0
        var dialogState by mutableStateOf<PublishToListUiState>(
            PublishToListUiState.Loading(PublishStep.Publishing),
        )
        ImageComposeScene(width = 640, height = 560) {
            CompositionLocalProvider(LocalWindowManager provides WindowManager.Large) {
                UiMotionTheme(defaultRWPPColorScheme, Typography()) {
                    SideEffect { parentCompositions++ }
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF141814)),
                        contentAlignment = Alignment.Center,
                    ) {
                        PublishToListDialogCard(
                            state = dialogState,
                            defaultRoomName = "公开房-Q52788",
                            roomCode = "Q52788",
                            pendingRoomName = "公开房-Q52788",
                            onDismiss = {},
                            onCancel = {},
                            onCloseButtonClick = {},
                            onPublish = { _, _ -> },
                            onRetry = {},
                        )
                    }
                }
            }
        }.use { scene ->
            scene.frames(0)
            val still = scene.png(120)
            assertFalse(scene.hasInvalidations(), "Disabled publish loading must not schedule frames")
            assertContentEquals(still, scene.png(1500))
            saveBytes("loading_static.png", still)

            settings.setUiAnimationsEnabled(true)
            Snapshot.sendApplyNotifications()
            scene.frames(1600)
            assertTrue(scene.hasInvalidations(), "Enabled publish loading must animate continuously")
            val t1 = scene.png(1800)
            val compositionsAfterStart = parentCompositions
            for (step in 1..25) {
                scene.render((1800 + step * 16) * 1_000_000L).close()
            }
            assertEquals(
                compositionsAfterStart,
                parentCompositions,
                "Master uplink clock must only be read in draw phase, not parent composition",
            )
            val t2 = scene.png(2450)
            assertFalse(t1.contentEquals(t2), "Animated uplink stage must advance smoothly over time")
            saveBytes("loading_broadcasting_t1.png", t1)
            saveBytes("loading_broadcasting_t2.png", t2)

            settings.setUiAnimationsEnabled(false)
            Snapshot.sendApplyNotifications()
            scene.frames(2700)
            val stopped = scene.png(2850)
            assertFalse(scene.hasInvalidations(), "Disabling motion live must stop infinite uplink clock")
            assertContentEquals(stopped, scene.png(4100))

            // 切换到非 Loading 状态后，即使开启动画也应在过渡收束后零无效化
            settings.setUiAnimationsEnabled(true)
            dialogState = PublishToListUiState.SelectRoomType(
                types = listOf("1V1", "3V3", "5V5", "混战", "排位", "模组", "生存"),
                allowCustomRoomName = false,
                modSyncEnabled = false,
            )
            Snapshot.sendApplyNotifications()
            for (ms in 4200L..5000L step 20L) {
                scene.render(ms * 1_000_000L).close()
                Snapshot.sendApplyNotifications()
            }
            assertFalse(scene.hasInvalidations(), "Configure stage should settle with zero lingering invalidations")
        }
    }

    @Test
    fun interactiveFlowCoversCancelSelectPublishSuccessAndFailure() =
        runDesktopComposeUiTest(width = 720, height = 620) {
            settings.setUiAnimationsEnabled(false)
            var state by mutableStateOf<PublishToListUiState>(
                PublishToListUiState.Loading(PublishStep.FetchingTypes),
            )
            var cancelled = 0
            var closed = 0
            var dismissed = 0
            var published: Pair<String, String>? = null
            var retriedStep: PublishStep? = null

            setContent {
                CompositionLocalProvider(LocalWindowManager provides WindowManager.Large) {
                    UiMotionTheme(defaultRWPPColorScheme, Typography()) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color(0xFF141814)),
                            contentAlignment = Alignment.Center,
                        ) {
                            PublishToListDialogCard(
                                state = state,
                                defaultRoomName = "公开房-Q52788",
                                roomCode = "Q52788",
                                pendingRoomName = published?.second.orEmpty(),
                                onDismiss = { dismissed++ },
                                onCancel = { cancelled++ },
                                onCloseButtonClick = { closed++ },
                                onPublish = { type, name -> published = type to name },
                                onRetry = { retriedStep = it },
                            )
                        }
                    }
                }
            }

            // 1. Loading(FetchingTypes) 取消申请
            onNodeWithTag("publish-loading-stage").assertIsDisplayed()
            onNodeWithText("正在申请开放中").assertIsDisplayed()
            onNodeWithText("正在连接列表服务器…").assertIsDisplayed()
            onNodeWithTag("publish-cancel-button").performClick()
            assertEquals(1, cancelled)

            // 2. SelectRoomType 多选标签 + 模组同步自动附加 + 自定义房间名
            state = PublishToListUiState.SelectRoomType(
                types = listOf("1V1", "3V3", "5V5", "混战", "排位", "模组", "生存"),
                allowCustomRoomName = true,
                modSyncEnabled = true,
            )
            waitForIdle()
            onNodeWithTag("publish-select-stage").assertIsDisplayed()
            // 倒序点击「排位」「1V1」，序列化仍按服务端返回顺序保持「1V1|排位|模组同步」
            onNodeWithText("排位").performClick()
            onNodeWithText("1V1").performClick()
            onNodeWithTag("publish-room-name-field").performTextClearance()
            onNodeWithTag("publish-room-name-field").performTextInput("极速铁锈约战")
            saveCapture("11_select_tags_chosen.png")
            onNodeWithTag("publish-submit-button").performClick()
            assertEquals("1V1|排位|模组同步" to "极速铁锈约战", published)

            // 3. Success 确认关闭
            state = PublishToListUiState.Success(roomId = "Q52788", serverId = "srv-1")
            waitForIdle()
            onNodeWithTag("publish-success-stage").assertIsDisplayed()
            onNodeWithText("开放成功").assertIsDisplayed()
            onNodeWithText("确定").performClick()
            assertEquals(1, dismissed)
            onNodeWithTag("publish-close-button").performClick()
            assertEquals(1, closed)

            // 4. Failure 重试保留失败阶段
            state = PublishToListUiState.Failure(
                message = "发布失败：探测房间超时 (code: 504)",
                step = PublishStep.Publishing,
            )
            waitForIdle()
            onNodeWithTag("publish-failure-stage").assertIsDisplayed()
            onNodeWithText("开放失败").assertIsDisplayed()
            onNodeWithText("发布失败：探测房间超时 (code: 504)").assertIsDisplayed()
            onNodeWithText("重试").performClick()
            assertEquals(PublishStep.Publishing, retriedStep)
        }

    @Test
    fun tappingBeaconStageEmitsPingFeedbackWhenMotionEnabled() =
        runDesktopComposeUiTest(width = 640, height = 560) {
            settings.setUiAnimationsEnabled(true)
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalWindowManager provides WindowManager.Large) {
                    UiMotionTheme(defaultRWPPColorScheme, Typography()) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color(0xFF141814)),
                            contentAlignment = Alignment.Center,
                        ) {
                            PublishToListDialogCard(
                                state = PublishToListUiState.Loading(PublishStep.Publishing),
                                defaultRoomName = "公开房-Q52788",
                                roomCode = "Q52788",
                                pendingRoomName = "公开房-Q52788",
                                onDismiss = {},
                                onCancel = {},
                                onCloseButtonClick = {},
                                onPublish = { _, _ -> },
                                onRetry = {},
                            )
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(120)
            onNodeWithText("轻点信标发射探测脉冲").assertIsDisplayed()
            onNodeWithTag("publish-beacon-stage").performClick()
            mainClock.advanceTimeBy(260)
            onNodeWithText("脉冲 ×1").assertIsDisplayed()
            saveCapture("10_loading_ping_ripple.png")
        }

    @Test
    fun rendersVisualGalleryAcrossStatesThemesAndLandscapeViewport() {
        settings.setUiAnimationsEnabled(true)
        renderSnapshot(
            width = 640,
            height = 560,
            colorScheme = defaultRWPPColorScheme,
            state = PublishToListUiState.Loading(PublishStep.FetchingTypes),
            language = "zh",
            timeMs = 950L,
            fileName = "01_loading_connecting_rwpp.png",
        )
        renderSnapshot(
            width = 640,
            height = 560,
            colorScheme = defaultRWPPColorScheme,
            state = PublishToListUiState.Loading(PublishStep.Publishing),
            language = "zh",
            timeMs = 1450L,
            fileName = "02_loading_broadcasting_rwpp.png",
        )
        renderSnapshot(
            width = 640,
            height = 560,
            colorScheme = defaultRWPPColorScheme,
            state = PublishToListUiState.SelectRoomType(
                types = listOf("1V1", "3V3", "5V5", "混战", "排位", "模组", "生存"),
                allowCustomRoomName = false,
                modSyncEnabled = false,
            ),
            language = "zh",
            timeMs = 500L,
            fileName = "03_select_locked_rwpp.png",
        )
        renderSnapshot(
            width = 640,
            height = 600,
            colorScheme = defaultRWPPColorScheme,
            state = PublishToListUiState.SelectRoomType(
                types = listOf("1V1", "3V3", "5V5", "混战", "排位", "模组", "生存"),
                allowCustomRoomName = true,
                modSyncEnabled = true,
            ),
            language = "zh",
            timeMs = 500L,
            fileName = "04_select_modsync_editable_rwpp.png",
        )
        renderSnapshot(
            width = 640,
            height = 520,
            colorScheme = defaultRWPPColorScheme,
            state = PublishToListUiState.Success(roomId = "Q52788", serverId = "srv-8842"),
            language = "zh",
            timeMs = 700L,
            fileName = "05_success_rwpp.png",
        )
        renderSnapshot(
            width = 640,
            height = 520,
            colorScheme = defaultRWPPColorScheme,
            state = PublishToListUiState.Failure(
                message = "发布失败：列表服务器未能连通当前房间，请确认未开启锁房或稍后重试 (code: 504)",
                step = PublishStep.Publishing,
            ),
            language = "zh",
            timeMs = 500L,
            fileName = "06_failure_rwpp.png",
        )
        renderSnapshot(
            width = 740,
            height = 360,
            colorScheme = defaultRWPPColorScheme,
            state = PublishToListUiState.Loading(PublishStep.Publishing),
            language = "zh",
            timeMs = 1100L,
            fileName = "07_landscape_compact_loading.png",
        )
        renderSnapshot(
            width = 640,
            height = 560,
            colorScheme = themes.getValue("Aqua Blue"),
            state = PublishToListUiState.Loading(PublishStep.Publishing),
            language = "zh",
            timeMs = 1200L,
            fileName = "08_aqua_blue_loading.png",
        )
        renderSnapshot(
            width = 640,
            height = 560,
            colorScheme = lightColorScheme(),
            state = PublishToListUiState.SelectRoomType(
                types = listOf("1V1", "3V3", "5V5", "FFA", "Ranked", "Modded"),
                allowCustomRoomName = false,
                modSyncEnabled = true,
            ),
            language = "en",
            timeMs = 500L,
            fileName = "09_light_en_select.png",
        )
    }

    private fun renderSnapshot(
        width: Int,
        height: Int,
        colorScheme: ColorScheme,
        state: PublishToListUiState,
        language: String,
        timeMs: Long,
        fileName: String,
    ) {
        loadLanguage(language)
        ImageComposeScene(width = width, height = height) {
            CompositionLocalProvider(LocalWindowManager provides WindowManager.Large) {
                UiMotionTheme(colorScheme, Typography()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (colorScheme.background.value == lightColorScheme().background.value) {
                                    Color(0xFFE7EAEF)
                                } else {
                                    Color(0xFF121512)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        PublishToListDialogCard(
                            state = state,
                            defaultRoomName = "公开房-Q52788",
                            roomCode = "Q52788",
                            pendingRoomName = "公开房-Q52788",
                            onDismiss = {},
                            onCancel = {},
                            onCloseButtonClick = {},
                            onPublish = { _, _ -> },
                            onRetry = {},
                        )
                    }
                }
            }
        }.use { scene ->
            scene.frames(0)
            saveBytes(fileName, scene.png(timeMs))
        }
    }

    private fun loadLanguage(language: String) {
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_$language.toml").readText())
    }

    private fun ImageComposeScene.frames(startMs: Long) {
        repeat(5) {
            Snapshot.sendApplyNotifications()
            render((startMs + it * 16) * 1_000_000L).close()
        }
    }

    private fun ImageComposeScene.png(timeMs: Long): ByteArray {
        render(timeMs * 1_000_000L).close()
        Snapshot.sendApplyNotifications()
        return render(timeMs * 1_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } }
    }

    private fun saveBytes(name: String, bytes: ByteArray) {
        val target = File("build/reports/publish-to-list/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        target.writeBytes(bytes)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.saveCapture(name: String) {
        val target = File("build/reports/publish-to-list/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target))
    }
}
