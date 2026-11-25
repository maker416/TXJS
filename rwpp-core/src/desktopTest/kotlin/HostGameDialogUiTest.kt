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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.use
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.net.HostCommandPrefix
import io.github.rwpp.net.buildQuickHostCommand
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class HostGameDialogUiTest {
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
    fun protocolSelectionRoomIdParametersAndSubmissionProduceExactQuickHostCommand() =
        runDesktopComposeUiTest(width = 860, height = 740) {
            settings.setUiAnimationsEnabled(false)
            var form by mutableStateOf(HostGameFormState())
            var stage by mutableStateOf(HostDialogStage.Configure)
            var submission by mutableStateOf<HostGameSubmission?>(null)
            var dismissed = 0

            setContent {
                CompositionLocalProvider(LocalWindowManager provides WindowManager.Large) {
                    UiMotionTheme(defaultRWPPColorScheme, Typography()) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color(0xFF141814)),
                            contentAlignment = Alignment.Center,
                        ) {
                            HostGameDialogCard(
                                form = form,
                                stage = stage,
                                modInventory = HostModInventory(enabledLocalCount = 2, hasEnabledMods = true),
                                isAccountLoggedIn = true,
                                onFormChange = { form = it },
                                onStageChange = { stage = it },
                                onRequireAccountLogin = {},
                                onDismiss = { dismissed++ },
                                onSubmit = { submission = it },
                            )
                        }
                    }
                }
            }

            // 默认选中 Q 房间，输入房间号和 4 项规则参数
            onNodeWithTag("host-configure-stage").assertIsDisplayed()
            onNodeWithText("暂未开放，开发中", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("host-protocol-t").assertIsNotEnabled().performTouchInput { click() }
            runOnIdle { assertEquals(HostCommandPrefix.Q, form.prefix) }
            onNodeWithTag("host-field-room-id").performTextInput("55201abc")
            onNodeWithTag("host-field-max-player").performTextInput("250")
            onNodeWithTag("host-field-unit-limit").performTextInput("1500")
            onNodeWithTag("host-field-credits").performTextInput("8000")
            onNodeWithTag("host-field-speed").performTextInput("2")
            waitForIdle()

            assertEquals("55201", form.roomId)
            assertEquals(100, form.maxPlayer)
            assertEquals(1500, form.unitLimit)
            assertEquals(8000, form.credits)
            assertEquals(2, form.speedMultiplier)

            // 切到 R 房间自动清空 roomId 并展示自动分配提示
            onNodeWithTag("host-protocol-r").performClick()
            waitForIdle()
            assertEquals(HostCommandPrefix.R, form.prefix)
            assertEquals("", form.roomId)
            onNodeWithTag("host-room-id-auto").assertIsDisplayed()

            // 切回 Q 房间，重新输入房号并提交保留的规则参数
            onNodeWithTag("host-protocol-q").performClick()
            waitForIdle()
            onNodeWithTag("host-field-room-id").performTextInput("7788")
            onNodeWithTag("host-submit-button").performClick()
            waitForIdle()

            assertEquals(HostDialogStage.Configure, stage)
            val submitted = assertNotNull(submission)
            assertEquals("QC7788P100U1500C8000Z2", buildQuickHostCommand(submitted.options))
            assertFalse(submitted.enableMods)
            assertFalse(submitted.transferMod)

            // 恢复默认按钮一键清空所有自定义参数
            onNodeWithTag("host-reset-params").performClick()
            waitForIdle()
            assertEquals("", form.roomId)
            assertNull(form.maxPlayer)
            assertNull(form.unitLimit)
            assertNull(form.credits)
            assertNull(form.speedMultiplier)

            // 关闭按钮触发 dismiss
            onNodeWithTag("host-close-button").performClick()
            assertEquals(1, dismissed)
        }

    @Test
    fun modTogglesEnforceNetworkHintLoginGuardAndDisclaimerFlow() =
        runDesktopComposeUiTest(width = 860, height = 740) {
            settings.setUiAnimationsEnabled(false)
            var form by mutableStateOf(HostGameFormState())
            var stage by mutableStateOf(HostDialogStage.Configure)
            var loggedIn by mutableStateOf(false)
            var loginPrompts = 0
            var submission by mutableStateOf<HostGameSubmission?>(null)

            setContent {
                CompositionLocalProvider(LocalWindowManager provides WindowManager.Large) {
                    UiMotionTheme(defaultRWPPColorScheme, Typography()) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color(0xFF141814)),
                            contentAlignment = Alignment.Center,
                        ) {
                            HostGameDialogCard(
                                form = form,
                                stage = stage,
                                modInventory = HostModInventory(
                                    enabledLocalCount = 3,
                                    hasEnabledMods = true,
                                    hasNetworkMods = true,
                                    hasEnabledNetworkMods = true,
                                ),
                                isAccountLoggedIn = loggedIn,
                                onFormChange = { form = it },
                                onStageChange = { stage = it },
                                onRequireAccountLogin = { loginPrompts++ },
                                onDismiss = {},
                                onSubmit = { submission = it },
                            )
                        }
                    }
                }
            }

            // 开启启动模组时因存在网络缓存模组，弹出提示阶段
            onNodeWithTag("host-network-mod-banner").assertIsDisplayed()
            onNodeWithTag("host-toggle-enable-mods").performClick()
            waitForIdle()
            assertTrue(form.enableMods)
            assertEquals(HostDialogStage.NetworkModHint, stage)
            onNodeWithTag("host-network-mod-hint-stage").assertIsDisplayed()
            onNodeWithTag("host-notice-confirm").performClick()
            waitForIdle()
            assertEquals(HostDialogStage.Configure, stage)

            // 未登录时点击传输模组触发登录提醒，不直接开启
            onNodeWithTag("host-toggle-transfer-mod").performClick()
            waitForIdle()
            assertEquals(1, loginPrompts)
            assertFalse(form.transferMod)

            // 登录后点击传输模组进入免责声明阶段，确认后才开启
            loggedIn = true
            waitForIdle()
            onNodeWithTag("host-toggle-transfer-mod").performClick()
            waitForIdle()
            assertEquals(HostDialogStage.TransferModDisclaimer, stage)
            onNodeWithTag("host-transfer-disclaimer-stage").assertIsDisplayed()
            onNodeWithTag("host-notice-confirm").performClick()
            waitForIdle()
            assertEquals(HostDialogStage.Configure, stage)
            assertTrue(form.transferMod)

            // 提交模组同步开房
            onNodeWithTag("host-submit-button").performClick()
            waitForIdle()
            val submitted = assertNotNull(submission)
            assertEquals("Qmods", buildQuickHostCommand(submitted.options))
            assertTrue(submitted.enableMods)
            assertTrue(submitted.transferMod)

            // 关闭启动模组自动关闭传输模组
            onNodeWithTag("host-toggle-enable-mods").performClick()
            waitForIdle()
            assertFalse(form.enableMods)
            assertFalse(form.transferMod)
        }

    @Test
    fun wideDesktopViewportClampsWidthAndWrapsHeightNaturally() =
        runDesktopComposeUiTest(width = 1920, height = 1080) {
            settings.setUiAnimationsEnabled(false)
            var form by mutableStateOf(HostGameFormState())
            var stage by mutableStateOf(HostDialogStage.Configure)

            setContent {
                CompositionLocalProvider(LocalWindowManager provides WindowManager.Large) {
                    UiMotionTheme(defaultRWPPColorScheme, Typography()) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color(0xFF141814)),
                            contentAlignment = Alignment.Center,
                        ) {
                            HostGameDialogCard(
                                form = form,
                                stage = stage,
                                modInventory = HostModInventory(enabledLocalCount = 2, hasEnabledMods = true),
                                isAccountLoggedIn = true,
                                onFormChange = { form = it },
                                onStageChange = { stage = it },
                                onRequireAccountLogin = {},
                                onDismiss = {},
                                onSubmit = {},
                            )
                        }
                    }
                }
            }

            waitForIdle()
            val configureBounds = onNodeWithTag("host-game-dialog").getUnclippedBoundsInRoot()
            assertTrue(
                configureBounds.width in 620.dp..636.dp,
                "Expected refined modal width ~628dp on 1920x1080, got ${configureBounds.width}",
            )
            assertTrue(
                configureBounds.height < 760.dp,
                "Expected natural content height without empty bottom void, got ${configureBounds.height}",
            )
            saveCapture("01_desktop_1920x1080_default_q.png")

            stage = HostDialogStage.TransferModDisclaimer
            waitForIdle()
            val confirmBounds = onNodeWithTag("host-game-dialog").getUnclippedBoundsInRoot()
            assertTrue(
                confirmBounds.width in 460.dp..476.dp,
                "Expected compact confirmation width ~468dp on 1920x1080, got ${confirmBounds.width}",
            )
        }

    @Test
    fun landscapeCompactViewportKeepsFooterSubmitAndCloseAlwaysReachable() =
        runDesktopComposeUiTest(width = 740, height = 360) {
            settings.setUiAnimationsEnabled(false)
            var form by mutableStateOf(HostGameFormState())
            var submitted = 0
            var closed = 0

            setContent {
                CompositionLocalProvider(LocalWindowManager provides WindowManager.Middle) {
                    UiMotionTheme(defaultRWPPColorScheme, Typography()) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color(0xFF141814)),
                            contentAlignment = Alignment.Center,
                        ) {
                            HostGameDialogCard(
                                form = form,
                                stage = HostDialogStage.Configure,
                                modInventory = HostModInventory(),
                                isAccountLoggedIn = true,
                                onFormChange = { form = it },
                                onStageChange = {},
                                onRequireAccountLogin = {},
                                onDismiss = { closed++ },
                                onSubmit = { submitted++ },
                            )
                        }
                    }
                }
            }

            waitForIdle()
            onNodeWithTag("host-close-button").assertIsDisplayed()
            onNodeWithTag("host-submit-button").assertIsDisplayed()
            onNodeWithTag("host-field-speed").performScrollTo().assertIsDisplayed()
            onNodeWithTag("host-submit-button").performClick()
            assertEquals(1, submitted)
            onNodeWithTag("host-close-button").performClick()
            assertEquals(1, closed)
            saveCapture("08_landscape_740x360.png")
        }

    @Test
    fun motionTransitionsSettleWithZeroLingeringInvalidations() {
        settings.setUiAnimationsEnabled(false)
        var form by mutableStateOf(HostGameFormState(prefix = HostCommandPrefix.Q))
        var stage by mutableStateOf(HostDialogStage.Configure)

        ImageComposeScene(width = 760, height = 700) {
            CompositionLocalProvider(LocalWindowManager provides WindowManager.Large) {
                UiMotionTheme(defaultRWPPColorScheme, Typography()) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF141814)),
                        contentAlignment = Alignment.Center,
                    ) {
                        HostGameDialogCard(
                            form = form,
                            stage = stage,
                            modInventory = HostModInventory(enabledLocalCount = 3, hasEnabledMods = true),
                            isAccountLoggedIn = true,
                            onFormChange = { form = it },
                            onStageChange = { stage = it },
                            onRequireAccountLogin = {},
                            onDismiss = {},
                            onSubmit = {},
                        )
                    }
                }
            }
        }.use { scene ->
            // 禁用动画时首帧稳定后零刷新
            scene.frames(0)
            val staticPng = scene.png(120)
            assertFalse(scene.hasInvalidations(), "Disabled animations must schedule zero frames")
            assertContentEquals(staticPng, scene.png(1200))

            // 开启动画，切换协议 Q -> T 滑轨过渡并收束
            settings.setUiAnimationsEnabled(true)
            form = form.copy(prefix = HostCommandPrefix.T)
            Snapshot.sendApplyNotifications()
            scene.render(1260L * 1_000_000L).close()
            for (ms in 1280L..1900L step 20L) {
                scene.render(ms * 1_000_000L).close()
                Snapshot.sendApplyNotifications()
            }
            assertFalse(scene.hasInvalidations(), "Protocol glide transition must settle with zero invalidations")

            // 切换阶段 Configure -> TransferModDisclaimer，捕获中段帧并验证收束
            stage = HostDialogStage.TransferModDisclaimer
            Snapshot.sendApplyNotifications()
            for (ms in 1920L..2015L step 15L) {
                scene.render(ms * 1_000_000L).close()
                Snapshot.sendApplyNotifications()
            }
            saveBytes("10_transition_mid_frame.png", scene.png(2020L))

            for (ms in 2040L..2700L step 20L) {
                scene.render(ms * 1_000_000L).close()
                Snapshot.sendApplyNotifications()
            }
            assertFalse(scene.hasInvalidations(), "Stage Fade-Through transition must settle with zero invalidations")
        }
    }

    @Test
    fun rendersVisualGalleryAcrossThemesStatesAndViewports() {
        settings.setUiAnimationsEnabled(false)
        renderSnapshot(
            width = 1280,
            height = 720,
            colorScheme = defaultRWPPColorScheme,
            form = HostGameFormState(
                prefix = HostCommandPrefix.T,
                enableMods = true,
                transferMod = true,
                roomId = "88420",
                maxPlayer = 10,
                unitLimit = 1000,
                credits = 10000,
                speedMultiplier = 2,
            ),
            stage = HostDialogStage.Configure,
            modInventory = HostModInventory(enabledLocalCount = 4, hasEnabledMods = true),
            isAccountLoggedIn = true,
            language = "zh",
            fileName = "02_desktop_1280x720_t_modsync_filled.png",
        )
        renderSnapshot(
            width = 760,
            height = 720,
            colorScheme = defaultRWPPColorScheme,
            form = HostGameFormState(prefix = HostCommandPrefix.R),
            stage = HostDialogStage.Configure,
            modInventory = HostModInventory(
                enabledLocalCount = 1,
                hasEnabledMods = true,
                hasNetworkMods = true,
                hasEnabledNetworkMods = true,
            ),
            isAccountLoggedIn = false,
            language = "zh",
            fileName = "03_desktop_r_network_warning.png",
        )
        renderSnapshot(
            width = 760,
            height = 560,
            colorScheme = defaultRWPPColorScheme,
            form = HostGameFormState(prefix = HostCommandPrefix.Q, enableMods = true),
            stage = HostDialogStage.TransferModDisclaimer,
            modInventory = HostModInventory(enabledLocalCount = 2, hasEnabledMods = true),
            isAccountLoggedIn = true,
            language = "zh",
            fileName = "04_stage_transfer_disclaimer.png",
        )
        renderSnapshot(
            width = 760,
            height = 560,
            colorScheme = defaultRWPPColorScheme,
            form = HostGameFormState(prefix = HostCommandPrefix.T, enableMods = false),
            stage = HostDialogStage.VanillaClearModsConfirm,
            modInventory = HostModInventory(enabledLocalCount = 2, hasEnabledMods = true),
            isAccountLoggedIn = true,
            language = "zh",
            fileName = "05_stage_vanilla_clear_confirm.png",
        )
        renderSnapshot(
            width = 760,
            height = 680,
            colorScheme = themes.getValue("Aqua Blue"),
            form = HostGameFormState(
                prefix = HostCommandPrefix.T,
                enableMods = true,
                transferMod = true,
                roomId = "2026",
                maxPlayer = 8,
            ),
            stage = HostDialogStage.Configure,
            modInventory = HostModInventory(enabledLocalCount = 3, hasEnabledMods = true),
            isAccountLoggedIn = true,
            language = "zh",
            fileName = "06_aqua_blue_theme.png",
        )
        renderSnapshot(
            width = 760,
            height = 680,
            colorScheme = lightColorScheme(),
            form = HostGameFormState(
                prefix = HostCommandPrefix.Q,
                enableMods = true,
                transferMod = false,
                roomId = "4096",
                maxPlayer = 6,
                speedMultiplier = 3,
            ),
            stage = HostDialogStage.Configure,
            modInventory = HostModInventory(enabledLocalCount = 2, hasEnabledMods = true),
            isAccountLoggedIn = true,
            language = "en",
            fileName = "07_light_en_configure.png",
        )
        renderSnapshot(
            width = 360,
            height = 780,
            colorScheme = defaultRWPPColorScheme,
            form = HostGameFormState(prefix = HostCommandPrefix.T, enableMods = true),
            stage = HostDialogStage.Configure,
            modInventory = HostModInventory(enabledLocalCount = 2, hasEnabledMods = true),
            isAccountLoggedIn = true,
            language = "zh",
            fileName = "09_portrait_360x780.png",
        )
    }

    private fun renderSnapshot(
        width: Int,
        height: Int,
        colorScheme: ColorScheme,
        form: HostGameFormState,
        stage: HostDialogStage,
        modInventory: HostModInventory,
        isAccountLoggedIn: Boolean,
        language: String,
        fileName: String,
    ) {
        loadLanguage(language)
        val windowManager = when {
            width < 500 -> WindowManager.Small
            width < 900 -> WindowManager.Middle
            else -> WindowManager.Large
        }
        ImageComposeScene(width = width, height = height) {
            CompositionLocalProvider(LocalWindowManager provides windowManager) {
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
                        HostGameDialogCard(
                            form = form,
                            stage = stage,
                            modInventory = modInventory,
                            isAccountLoggedIn = isAccountLoggedIn,
                            onFormChange = {},
                            onStageChange = {},
                            onRequireAccountLogin = {},
                            onDismiss = {},
                            onSubmit = {},
                        )
                    }
                }
            }
        }.use { scene ->
            scene.frames(0)
            saveBytes(fileName, scene.png(240L))
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
        val target = File("build/reports/host-game-dialog/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        target.writeBytes(bytes)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.saveCapture(name: String) {
        val target = File("build/reports/host-game-dialog/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target))
    }
}
