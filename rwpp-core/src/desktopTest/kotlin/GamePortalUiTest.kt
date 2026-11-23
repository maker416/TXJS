/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import io.github.rwpp.config.MultiplayerPreferences
import io.github.rwpp.config.decodeSavedConfig
import io.github.rwpp.content.PortalExpansionProgress
import io.github.rwpp.content.PortalExpansionStage
import io.github.rwpp.expansion.ExpansionArtBinding
import io.github.rwpp.expansion.ExpansionBinding
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.net.ConnectionFailure
import io.github.rwpp.net.ConnectionFailureReason
import io.github.rwpp.net.games.OnlineGame
import io.github.rwpp.net.localized
import net.peanuuutz.tomlkt.Toml
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GamePortalUiTest {
    private val expansion = ExpansionBinding("1.2", "a".repeat(64), 1024,
        ExpansionArtBinding("11111111-1111-1111-1111-111111111111", "1.0", "b".repeat(64)))
    private val portalGame = OnlineGame("game", "网游测试入口", expansionBinding = expansion,
        expansionSyncEndpoint = "8.8.8.8:5125", portalUrl = "https://game.example", portalRevision = 1)

    @BeforeTest fun setup() {
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
    }
    @AfterTest fun cleanup() { reloadI18n() }

    @Test fun firstDownloadPermissionFitsPortraitWithoutServerSelection() = runDesktopComposeUiTest(width = 412, height = 844) {
        val consent = PortalContentConsent(OnlineGame("game", "网游测试入口"), required = true)
        setContent { MaterialTheme { PortalContentConsentDialog(consent) } }
        onNodeWithTag("game-portal-content-consent").assertIsDisplayed()
        onNodeWithTag("portal-content-accept").assertIsDisplayed()
        onNodeWithTag("portal-content-decline").assertIsDisplayed()
        onNodeWithTag("hall-server-main").assertDoesNotExist()
        val file = File("build/reports/game-portal/consent-portrait.png").apply { parentFile.mkdirs() }
        ImageIO.write(onNodeWithTag("game-portal-content-consent").captureToImage().toAwtImage(), "png", file)
        onNodeWithTag("portal-content-decline").performClick()
        runOnIdle { assertFalse(consent.decision.getCompleted()) }
    }

    @Test fun optionalAppearancePermissionOffersDefaultInLandscape() = runDesktopComposeUiTest(width = 960, height = 432) {
        val consent = PortalContentConsent(OnlineGame("game", "网游测试入口"), required = false)
        setContent { MaterialTheme { PortalContentConsentDialog(consent) } }
        onNodeWithText("使用默认外观").assertIsDisplayed().performClick()
        runOnIdle { assertFalse(consent.decision.getCompleted()) }
    }

    @Test fun preloadingConsentShowsPackageVersionBeforeOpeningHomepage() = runDesktopComposeUiTest(width = 412, height = 844) {
        val game = OnlineGame("game", "网游测试入口",
            expansionBinding = ExpansionBinding("1.2", "a".repeat(64), 1024,
                ExpansionArtBinding("art", "1.0", "b".repeat(64))))
        var opened = false
        setContent { MaterialTheme { PortalExpansionConsentDialog(game, onDecline = {}, onAccept = { opened = true }) } }
        onNodeWithText("1.2", substring = true).assertIsDisplayed()
        runOnIdle { assertFalse(opened) }
        onNodeWithText("同意并打开主页").assertIsDisplayed().performClick()
        runOnIdle { assertTrue(opened) }
    }

    @Test fun onlyAcceptedExpansionReopensAfterRestoringPreferences() = runDesktopComposeUiTest(width = 412, height = 844) {
        var prefs = decodeSavedConfig(MultiplayerPreferences::class, "mapNameFilter = 'custom'")
        var saved: String? = null
        val selected = mutableStateOf<OnlineGame?>(portalGame)
        setContent {
            MaterialTheme {
                selected.value?.let { game ->
                    PortalExpansionEntry(game, "https://platform.example", prefs,
                        savePreferences = { saved = Toml.encodeToString(MultiplayerPreferences.serializer(), prefs) },
                        active = true, onDecline = { selected.value = null }) { Text("玩法主页") }
                }
            }
        }
        onNodeWithText("玩法主页").assertDoesNotExist()
        onNodeWithTag("portal-content-decline").performClick()
        onNodeWithTag("game-portal-content-consent").assertDoesNotExist()
        runOnIdle { assertNull(saved); selected.value = portalGame }
        onNodeWithTag("portal-content-accept").assertIsDisplayed().performClick()
        onNodeWithText("玩法主页").assertIsDisplayed()

        runOnIdle { selected.value = null }
        onNodeWithText("玩法主页").assertDoesNotExist()
        runOnIdle {
            prefs = decodeSavedConfig(MultiplayerPreferences::class, assertNotNull(saved))
            selected.value = portalGame.copy(portalRevision = 2, portalUrl = "https://new.example",
                expansionSyncEndpoint = "1.1.1.1:5126")
        }
        onNodeWithTag("game-portal-content-consent").assertDoesNotExist()
        onNodeWithText("玩法主页").assertIsDisplayed()
    }

    @Test fun consentIsScopedToPlatformGameAndRelease() = runDesktopComposeUiTest(width = 412, height = 844) {
        val prefs = MultiplayerPreferences()
        val game = mutableStateOf(portalGame.copy(expansionSyncEndpoint = null))
        val platform = mutableStateOf("https://platform.example")
        setContent {
            MaterialTheme {
                PortalExpansionEntry(game.value, platform.value, prefs, savePreferences = {}, active = true,
                    onDecline = {}) { Text("玩法主页") }
            }
        }
        onNodeWithText("玩法主页").assertIsDisplayed()
        runOnIdle { game.value = portalGame }
        onNodeWithTag("portal-content-accept").performClick()
        onNodeWithText("玩法主页").assertIsDisplayed()

        for (other in listOf(portalGame.copy(expansionBinding = expansion.copy(version = "1.3")),
            portalGame.copy(expansionBinding = expansion.copy(sha256 = "c".repeat(64))),
            portalGame.copy(appId = "other"))) {
            runOnIdle { game.value = other }
            onNodeWithTag("portal-content-accept").assertIsDisplayed()
            onNodeWithText("玩法主页").assertDoesNotExist()
            runOnIdle { game.value = portalGame }
            onNodeWithText("玩法主页").assertIsDisplayed()
        }
        runOnIdle { platform.value = "https://test-platform.example" }
        onNodeWithTag("portal-content-accept").assertIsDisplayed()
        onNodeWithText("玩法主页").assertDoesNotExist()
    }

    @Test fun syncStatusReportsDownloadRatioAndReloadStageThenDisappears() = runDesktopComposeUiTest(width = 412, height = 844) {
        val status = mutableStateOf<PortalExpansionState?>(
            PortalExpansionState.Syncing(PortalExpansionProgress(PortalExpansionStage.DOWNLOADING, 50, 200)))
        setContent { MaterialTheme { PortalExpansionStatus(status.value, onRetry = {}) } }
        onNodeWithText("正在下载扩展包 · 25%").assertIsDisplayed()
        runOnIdle {
            status.value = PortalExpansionState.Syncing(PortalExpansionProgress(PortalExpansionStage.RELOADING))
        }
        onNodeWithText("正在重载模组和美术资源…").assertIsDisplayed()
        onNodeWithText("25%", substring = true).assertDoesNotExist()
        runOnIdle { status.value = null }
        onNodeWithTag("portal-expansion-sync").assertDoesNotExist()
    }

    @Test fun joinBarShowsTheNativeStageUntilRoomEntryFinishes() = runDesktopComposeUiTest(width = 412, height = 844) {
        val attempt = PortalJoinAttempt("request", "server", 1)
        val state = mutableStateOf(GamePortalUiState())
        val message = mutableStateOf("")
        setContent { MaterialTheme { PortalJoinStatus(state.value, message.value, onCancel = {}, onDismiss = {}) } }

        onNodeWithTag("portal-join-status").assertDoesNotExist()
        runOnIdle { state.value = GamePortalUiState(attempt = attempt, consent = PortalContentConsent(portalGame, true)) }
        onNodeWithTag("portal-join-status").assertDoesNotExist()
        runOnIdle { state.value = GamePortalUiState(attempt = attempt) }
        onNodeWithTag("portal-join-progress").assertIsDisplayed()
        onNodeWithTag("portal-join-cancel").assertIsDisplayed()
        runOnIdle {
            state.value = state.value.copy(ready = OnlineGameJoinRequest(portalGame.appId, attempt.serverId, "1.1.1.1:5123", null))
            message.value = "正在确认房间席位"
        }
        onNodeWithText("正在确认房间席位", substring = true).assertIsDisplayed()
        onNodeWithTag("portal-join-progress").assertIsDisplayed()
        runOnIdle { state.value = GamePortalUiState() }
        onNodeWithTag("portal-join-status").assertDoesNotExist()
    }

    @Test fun joinBarCancelsAndDisplaysDismissibleFailures() = runDesktopComposeUiTest(width = 412, height = 844) {
        val state = mutableStateOf(GamePortalUiState(attempt = PortalJoinAttempt("request", "server", 1)))
        setContent {
            MaterialTheme {
                PortalJoinStatus(state.value, "",
                    onCancel = { state.value = state.value.copy(cancelling = true) },
                    onDismiss = { state.value = GamePortalUiState() })
            }
        }
        onNodeWithTag("portal-join-cancel").performClick()
        runOnIdle { assertTrue(state.value.cancelling) }
        onNodeWithTag("portal-join-progress").assertIsDisplayed()
        runOnIdle { state.value = GamePortalUiState(error = "join_failed") }
        onNodeWithTag("portal-join-status").assertIsDisplayed()
        onNodeWithTag("portal-join-progress").assertDoesNotExist()
        onNodeWithTag("portal-join-dismiss").assertIsDisplayed().performClick()
        onNodeWithTag("portal-join-status").assertDoesNotExist()
    }

    @Test fun joinFailureDetailsOfferAdviceAndCopyTheOriginalMessage() = runDesktopComposeUiTest(width = 412, height = 360) {
        val failure = ConnectionFailure(ConnectionFailureReason.REFUSED, details = "Connection refused\nserver detail")
        var copied: String? = null
        setContent {
            val systemClipboard = LocalClipboardManager.current
            val clipboard = object : ClipboardManager by systemClipboard {
                override fun setText(annotatedString: AnnotatedString) { copied = annotatedString.text }
            }
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                MaterialTheme { PortalJoinStatus(GamePortalUiState(error = "join_failed", failure = failure), "", {}, {}) }
            }
        }
        onNodeWithText(failure.localized().title, substring = true).assertIsDisplayed()
        onNodeWithText(failure.details).assertDoesNotExist()
        onNodeWithText("显示详情").performClick()
        onNodeWithText(failure.localized().suggestion).assertIsDisplayed()
        onNodeWithText(failure.details).performScrollTo().assertIsDisplayed()
        onNodeWithText("复制").performScrollTo().performClick()
        runOnIdle { assertEquals(failure.details, copied) }
        onNodeWithText("收起详情").performClick()
        onNodeWithText(failure.details).assertDoesNotExist()
        onNodeWithTag("portal-join-dismiss").assertIsDisplayed()
    }
}
