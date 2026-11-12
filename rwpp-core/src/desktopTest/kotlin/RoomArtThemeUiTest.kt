/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.account.RoomIdentityController
import io.github.rwpp.app.LauncherVisibility
import io.github.rwpp.appKoin
import io.github.rwpp.coil.ImageableFetcherFactory
import io.github.rwpp.coil.ImageableKeyer
import io.github.rwpp.config.*
import io.github.rwpp.core.ModSyncController
import io.github.rwpp.core.RoomSnapshotStore
import io.github.rwpp.external.Extension
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.game.Game
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.Player
import io.github.rwpp.game.data.PlayerData
import io.github.rwpp.game.map.FogMode
import io.github.rwpp.game.map.GameMap
import io.github.rwpp.game.map.MapType
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.widget.RWPPTheme
import io.github.rwpp.widget.WindowManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.peanuuutz.tomlkt.Toml
import okhttp3.OkHttpClient
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Real waiting-room components with deterministic engine data; these are Compose, not device, captures. */
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class, ExperimentalCoroutinesApi::class)
class RoomArtThemeUiTest {
    private lateinit var work: File
    private lateinit var game: Game
    private lateinit var room: GameRoom
    private lateinit var localPlayer: Player
    private lateinit var gameMap: GameMap
    private var players: List<Player> = emptyList()
    private val readiness = mutableListOf<PlayerData>()
    private val sentMessages = mutableListOf<String>()
    private val networkAttempts = AtomicInteger()
    private var authority = true
    private var starts = 0
    private var additions = 0
    private var locked = false
    private var roomDetailsReads = 0

    @BeforeTest
    fun setup() {
        work = Files.createTempDirectory("rwpp-room-art-").toFile()
        val thumbnail = png(0xFF247A45.toInt())
        gameMap = object : GameMap {
            override val id = 1
            override val mapName = "Theme test map (8p)"
            override val mapType = MapType.SkirmishMap
            override fun openInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun openImageInputStream() = ByteArrayInputStream(thumbnail)
        }
        localPlayer = player("Local commander", 0, 0, host = true)
        players = listOf(localPlayer, player("Ready guest", 1, 1), player("Computer", 2, 1, ai = true),
            player("Loading guest", 3, 2, ready = false))
        room = stub(GameRoom::class.java) { method, args ->
            when (method) {
                "getPlayers" -> players
                "getAllPlayers" -> players.toTypedArray()
                "getLocalPlayer" -> localPlayer
                "getMaxPlayerCount" -> 8
                "isHost" -> authority
                "isHostServer", "isSinglePlayerGame", "isConnecting", "isStartGame", "isStartingGame" -> false
                "getSelectedMap" -> gameMap
                "getMapType" -> gameMap.mapType
                "getDisplayMapName" -> gameMap.displayName()
                "getLockedRoom" -> locked
                "setLockedRoom" -> { locked = args!![0] as Boolean; Unit }
                "getStartingCredits" -> 4000
                "getStartingUnits", "getAiDifficulty", "getRandomSeed" -> 1
                "getFogMode" -> FogMode.entries.first()
                "getSharedControl", "getNoNukes", "getTeamLock", "getRevealedMap" -> false
                "getAllowSpectators" -> true
                "getIncomeMultiplier", "getGameSpeed" -> 1f
                "getTeamMode" -> null
                "getMods" -> emptyArray<String>()
                "roomDetails" -> { roomDetailsReads++; "Room: Q123456\nMap: Theme test map\nPlayers: 4/8\nCredits: 4000\nFog: Basic" }
                "sendChatMessage" -> { sentMessages += args!![0] as String; Unit }
                "sendSystemMessage", "sendMessageToPlayer", "updateUI", "syncAllPlayer" -> Unit
                "addAI" -> { additions += args?.firstOrNull() as? Int ?: 1; Unit }
                "startGame" -> { starts++; Unit }
                else -> error("Unexpected room access: $method")
            }
        }
        game = stub(Game::class.java) { method, _ ->
            when (method) {
                "getGameRoom" -> room
                "getStartingUnitOptions" -> emptyList<Pair<Int, String>>()
                "getAllMaps", "getAllMapsByMapType" -> listOf(gameMap)
                "isGaming", "isGameCouldContinue" -> false
                "setUserName", "onBanUnits" -> Unit
                else -> error("Unexpected game access: $method")
            }
        }
        val offlineHttp = OkHttpClient.Builder().addInterceptor {
            networkAttempts.incrementAndGet()
            throw IOException("Room art tests must remain offline")
        }.build()
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module {
            single { Settings(autoCheckUpdate = false, enableAnimations = false, language = "zh", launcherMusicVolume = 0f) }
            single { AccountPreferences() }
            single { MultiplayerPreferences() }
            single { PublishedRoomInfo() }
            single { ModPlaytimePreferences(apiUrl = "") }
            single<Game> { game }
            single<Net> { stub(Net::class.java) { method, _ ->
                if (method == "getClient") offlineHttp else error("Unexpected network API access: $method")
            } }
            single<AppContext> { stub(AppContext::class.java) { method, _ ->
                if (method == "isDesktop") true else error("Unexpected application access: $method")
            } }
            single<ConfigIO> { stub(ConfigIO::class.java) { method, args ->
                when (method) {
                    "getKoin" -> appKoin
                    "readConfig", "readSingleConfig" -> null
                    "getGameConfig" -> when (args?.firstOrNull()) {
                        "lastNetworkIP" -> "Q123456"
                        "lastNetworkPlayerName" -> localPlayer.name
                        "teamUnitCapHostedGame" -> 500
                        else -> null
                    }
                    "saveConfig", "saveAllConfig", "saveSingleConfig", "setGameConfig" -> Unit
                    else -> error("Unexpected config access: $method")
                }
            } }
            single<ExternalHandler> { stub(ExternalHandler::class.java) { method, _ ->
                when {
                    method.startsWith("getAllExtensions") -> emptyList<Extension>()
                    method == "getKoin" -> appKoin
                    method == "getUsingResource" -> null
                    else -> error("Unexpected extension access: $method")
                }
            } }
        }) }.koin
        koinInit = true
        logger = LoggerFactory.getLogger("RoomArtThemeUiTest")
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
        AccountSession.resetForTests()
        AccountSession.networkEnabled = false
        FriendsSession.clear()
        runBlocking { RoomIdentityController.resetForTests() }
        ModSyncController.hostSyncRequested = false
        UI.warning = null
        UI.chatMessages = AnnotatedString("[Local] Ready for battle.\n[Guest] The map has loaded.\n[Local] Waiting for all players.")
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
        ArtThemeController.themeRootOverride = File(work, "themes")
        ArtThemeController.apply(null)
        SingletonImageLoader.setUnsafe { context -> ImageLoader.Builder(context).components {
            add(ImageableFetcherFactory()); add(ImageableKeyer())
        }.build() }
        LauncherVisibility.setVisible(true)
        SwingUtilities.invokeAndWait { resetNavigation(LauncherPage.Room); RoomSnapshotStore.resample(game) }
    }

    @AfterTest
    fun cleanup() {
        LauncherVisibility.setVisible(false)
        runBlocking { RoomIdentityController.resetForTests() }
        ArtThemeController.apply(null)
        ArtThemeController.themeRootOverride = null
        UI.warning = null
        UI.chatMessages = AnnotatedString("")
        UI.showAccountView = false
        UI.showFriendsView = false
        FriendsSession.clear()
        AccountSession.resetForTests()
        SwingUtilities.invokeAndWait { resetNavigation(LauncherPage.MainMenu) }
        SingletonImageLoader.reset()
        stopKoin()
        koinInit = false
        work.deleteRecursively()
        reloadI18n()
        LauncherVisibility.setVisible(true)
        Dispatchers.resetMain()
        assertEquals(0, networkAttempts.get(), "A UI theme test attempted HTTP")
    }

    @Test fun narrowRoomKeepsCommittedGeometryAcrossArtworkChanges() = geometry(360, 900, WindowManager.Small)
    @Test fun middleRoomKeepsCommittedGeometryAcrossArtworkChanges() = geometry(800, 600, WindowManager.Middle)
    @Test fun largeRoomKeepsCommittedGeometryAcrossArtworkChanges() = geometry(1280, 1000, WindowManager.Large)

    private fun geometry(width: Int, height: Int, mode: WindowManager) = runDesktopComposeUiTest(width = width, height = height) {
        showRoom(mode)
        onNodeWithTag("room-chat-input", useUnmergedTree = true).performTextInput("Unsent artwork draft")
        onNodeWithTag("room-chat-input", useUnmergedTree = true).assertIsFocused()
        val tags = listOf("room-panel-players", "room-panel-map", "room-panel-chat", "room-panel-actions",
            "room-player-1", "room-cell-name-1", "room-profile-1", "room-action-start", "room-action-options")
        val original = tags.associateWith { onNodeWithTag(it, useUnmergedTree = true).getUnclippedBoundsInRoot() }
        val art = install("geometry-art", """
            [room.layout]
            columns = 1
            widthPercent = 50
            padding = 32
            playerListHeight = 640
            [room.panels.players]
            backgroundColor = "#23456A"
            borderWidth = 0
            corner = 0
            order = 99
            padding = 32
            [room.panels.map]
            backgroundColor = "#65347A"
            borderWidth = 6
            corner = 48
            span = 3
            [room.panels.chat]
            backgroundColor = "#254A37"
            [room.panels.actions]
            backgroundColor = "#72463A"
            [room.player]
            rowHeight = 200
            padding = 40
            nameSize = 50.0
            showSpawn = false
            showPing = false
            columnOrder = ["profile", "name"]
            [room.player.profile]
            width = 180
            height = 180
            iconSize = 80
            [room.buttons.start]
            minHeight = 180
            textSize = 50.0
            order = -100
            backgroundColor = "#923456"
        """.trimIndent())
        runOnIdle { assertTrue(ArtThemeController.apply(art)) }
        onNodeWithTag("room-chat-input", useUnmergedTree = true).assertIsFocused().assertTextContains("Unsent artwork draft")
        original.forEach { (tag, bounds) -> assertEquals(bounds, onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot(), tag) }
        val second = install("geometry-second", "[room.panels.players]\nbackgroundColor = \"#7B4829\"\ncorner = 24\n")
        runOnIdle { assertTrue(ArtThemeController.apply(second)) }
        onNodeWithTag("room-chat-input", useUnmergedTree = true).assertIsFocused().assertTextContains("Unsent artwork draft")
        original.forEach { (tag, bounds) -> assertEquals(bounds, onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot(), tag) }
        save("room-${width}x${height}-artwork.png")
        runOnIdle { assertTrue(ArtThemeController.apply(null)) }
        onNodeWithTag("room-chat-input", useUnmergedTree = true).assertIsFocused().assertTextContains("Unsent artwork draft")
        original.forEach { (tag, bounds) -> assertEquals(bounds, onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot(), "disabled $tag") }
        save("room-${width}x${height}-default.png")
    }

    @Test fun productionPanelsRenderTheirOwnImagesAndRestoreOnDisable() = runDesktopComposeUiTest(width = 1280, height = 1000) {
        showRoom(WindowManager.Large)
        waitForColor("room-panel-map", 0xFF247A45.toInt())
        val before = onNodeWithTag("room-panel-map", useUnmergedTree = true).captureToImage().toAwtImage()
        val marker = 0xFF7139A4.toInt()
        val chatMarker = 0xFF337D64.toInt()
        val id = install("panel-image", """
            [room.panels.map]
            backgroundImage = "room/panel.png"
            backgroundColor = "#123456"
            borderWidth = 0
            corner = 0
            [room.panels.chat]
            backgroundImage = "room/chat.png"
            backgroundColor = "#19344A"
            borderWidth = 0
        """.trimIndent(), mapOf("room/panel.png" to png(marker), "room/chat.png" to png(chatMarker)))
        runOnIdle { assertTrue(ArtThemeController.apply(id)) }
        waitForColor("room-panel-map", marker)
        waitForColor("room-chat-history", chatMarker)
        runOnIdle { assertTrue(ArtThemeController.apply(null)) }
        waitUntil { countColor(onNodeWithTag("room-panel-map", useUnmergedTree = true).captureToImage().toAwtImage(), marker) == 0 }
        waitUntil { countColor(onNodeWithTag("room-chat-history", useUnmergedTree = true).captureToImage().toAwtImage(), chatMarker) == 0 }
        waitForColor("room-panel-map", 0xFF247A45.toInt())
        val after = onNodeWithTag("room-panel-map", useUnmergedTree = true).captureToImage().toAwtImage()
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        assertTrue(before.getRGB(0, 0, before.width, before.height, null, 0, before.width)
            .contentEquals(after.getRGB(0, 0, after.width, after.height, null, 0, after.width)))
    }

    @Test fun repositoryExampleDecoratesTheCommittedDesktopRoom() = repositoryExample(1280, 1000, WindowManager.Large)
    @Test fun repositoryExampleDecoratesTheCommittedCompactRoom() = repositoryExample(800, 700, WindowManager.Middle)

    private fun repositoryExample(width: Int, height: Int, mode: WindowManager) = runDesktopComposeUiTest(width = width, height = height) {
        showRoom(mode)
        val beforeMap = onNodeWithTag("room-panel-map", useUnmergedTree = true).captureToImage().toAwtImage()
        val beforePlayer = onNodeWithTag("room-player-1", useUnmergedTree = true).captureToImage().toAwtImage()
        val source = File("../examples/themes/pelagic-atlas").canonicalFile
        val id = installRepositoryExample(source)
        runOnIdle { assertTrue(ArtThemeController.apply(id)) }
        val style = ArtThemeController.activeTheme!!.spec.room
        val playerImage = File(source, style.player.localImage)
        val mapImage = File(source, style.panels.getValue("map").backgroundImage)
        assertTrue(playerImage.isFile && mapImage.isFile)
        val playerMarker = dominantOpaqueColor(playerImage)
        // Use an actual map texture color other than its configured solid fallback.
        val mapMarker = dominantOpaqueColor(mapImage, setOf(0xFFE8EFF0.toInt()))
        waitForColor("room-player-1", playerMarker)
        waitForColor("room-panel-map", mapMarker, minimum = 10)
        val afterMap = onNodeWithTag("room-panel-map", useUnmergedTree = true).captureToImage().toAwtImage()
        val afterPlayer = onNodeWithTag("room-player-1", useUnmergedTree = true).captureToImage().toAwtImage()
        assertEquals(beforeMap.width, afterMap.width)
        assertEquals(beforeMap.height, afterMap.height)
        assertEquals(beforePlayer.width, afterPlayer.width)
        assertEquals(beforePlayer.height, afterPlayer.height)
        assertEquals(0, countColor(beforeMap, mapMarker))
        assertEquals(0, countColor(beforePlayer, playerMarker))
        save("pelagic-atlas-${width}x${height}.png")
        runOnIdle { assertEquals(0, networkAttempts.get()) }
    }

    @Test fun playerStateArtAndSpawnSlotPriorityFollowRealPlayers() = runDesktopComposeUiTest(width = 700, height = 340) {
        players = players + player("Room host", 4, 1, host = true)
        show { PlayerRows() }
        val localColor = 0xFF274879.toInt()
        val readyColor = 0xFF297347.toInt()
        val aiColor = 0xFF805729.toInt()
        val waitingColor = 0xFF793E2A.toInt()
        val slotColor = 0xFF9B367C.toInt()
        val hostColor = 0xFF72353F.toInt()
        val images = mapOf("room/local.png" to png(localColor), "room/ready.png" to png(readyColor),
            "room/ai.png" to png(aiColor), "room/waiting.png" to png(waitingColor), "room/slot.png" to png(slotColor),
            "room/host.png" to png(hostColor))
        val config = """
            [room.player]
            localImage = "room/local.png"
            hostImage = "room/host.png"
            readyImage = "room/ready.png"
            aiImage = "room/ai.png"
            notReadyImage = "room/waiting.png"
            borderWidth = 0
            corner = 0
        """.trimIndent()
        val stateId = install("player-state", config, images)
        runOnIdle { assertTrue(ArtThemeController.apply(stateId)) }
        listOf(localColor, readyColor, aiColor, waitingColor, hostColor).forEachIndexed { i, color -> waitForColor("room-player-${i + 1}", color) }
        val slotId = install("player-slot", config + "\n[room.player.slotImages]\n\"1\" = \"room/slot.png\"\n", images)
        runOnIdle { assertTrue(ArtThemeController.apply(slotId)) }
        waitForColor("room-player-1", slotColor)
        waitForColor("room-player-2", readyColor)
        assertEquals("room/slot.png", roomPlayerBackground(ArtThemeController.activeTheme!!.spec.room.player, localPlayer, room))
    }

    @Test fun missingStateImagesContinueToFallbackAndCorruptImagesKeepTheConfiguredColor() =
        runDesktopComposeUiTest(width = 700, height = 340) {
            show { PlayerRows() }
            val ready = 0xFF297347.toInt()
            val fallback = 0xFF5B326F.toInt()
            val missing = install("missing-player", """
                [room.player]
                localImage = "room/missing.png"
                readyImage = "room/ready.png"
                backgroundColor = "#5B326F"
                borderWidth = 0
                corner = 0
            """.trimIndent(), mapOf("room/ready.png" to png(ready)))
            runOnIdle { assertTrue(ArtThemeController.apply(missing)) }
            waitForColor("room-player-1", ready)
            val corrupt = install("corrupt-player", """
                [room.player]
                localImage = "room/broken.png"
                backgroundColor = "#5B326F"
                borderWidth = 0
                corner = 0
            """.trimIndent(), mapOf("room/broken.png" to "bad png data".toByteArray()))
            runOnIdle { assertTrue(ArtThemeController.apply(corrupt)) }
            waitForColor("room-player-1", fallback)
            assertEquals(0, countColor(onNodeWithTag("room-player-1", useUnmergedTree = true).captureToImage().toAwtImage(), ready))
        }

    @Test fun namesUseOnlyTheRequestedFontFamilyWeightAndColor() = runDesktopComposeUiTest(width = 700, height = 340) {
        show { PlayerRows() }
        val original = textLayout(localPlayer.name).layoutInput.style
        val font = File("src/commonMain/composeResources/font/Jost-Light.ttf").readBytes()
        val id = install("name-font", """
            [room.player]
            nameFont = "fonts/room.ttf"
            nameWeight = 800
            nameColor = "#B44672"
            nameUseTeamColor = false
            nameSize = 90.0
            nameMaxLines = 9
        """.trimIndent(), mapOf("fonts/room.ttf" to font))
        runOnIdle { assertTrue(ArtThemeController.apply(id)) }
        val styled = textLayout(localPlayer.name).layoutInput.style
        assertEquals(FontWeight(800), styled.fontWeight)
        assertEquals(Color(0xFFB44672), styled.color)
        assertEquals(original.fontSize, styled.fontSize, "Artwork must not resize names")
        assertNotEquals(original.fontFamily, styled.fontFamily)
        val missingFont = install("name-font-missing", "[room.player]\nnameFont = \"fonts/missing.ttf\"\n")
        runOnIdle { assertTrue(ArtThemeController.apply(missingFont)) }
        assertEquals(original.fontFamily, textLayout(localPlayer.name).layoutInput.style.fontFamily)
        runOnIdle { assertTrue(ArtThemeController.apply(null)) }
        assertEquals(original, textLayout(localPlayer.name).layoutInput.style)
    }

    @Test fun profileArtRetainsLocalForeignAndAiClickRules() = runDesktopComposeUiTest(width = 700, height = 340) {
        authority = false
        var profileClicks = 0
        var rowClicks = 0
        show { PlayerRows(onRow = { rowClicks++ }, onProfile = { profileClicks++ }) }
        val marker = 0xFFEC21C2.toInt()
        val id = install("profile-icon", """
            [room.player.profile]
            iconImage = "room/icon.png"
            litImage = "room/lit.png"
            unavailableImage = "room/unavailable.png"
            borderWidth = 0
            corner = 0
        """.trimIndent(), mapOf("room/icon.png" to png(marker), "room/lit.png" to png(0xFF294D73.toInt()),
            "room/unavailable.png" to png(0xFF524439.toInt())))
        runOnIdle { assertTrue(ArtThemeController.apply(id)) }
        waitForColor("room-profile-1", marker, minimum = 10)
        onNodeWithTag("room-profile-1", useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(1, profileClicks); assertEquals(0, rowClicks) }
        onNodeWithTag("room-profile-2", useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(1, profileClicks); assertNotNull(UI.warning); UI.warning = null }
        onNodeWithTag("room-profile-3", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("room-player-3", useUnmergedTree = true).assertIsNotEnabled()
        val corrupt = install("profile-corrupt", """
            [room.player.profile]
            iconImage = "room/icon.png"
            tint = "#1FA3D8"
        """.trimIndent(), mapOf("room/icon.png" to "invalid image".toByteArray()))
        runOnIdle { assertTrue(ArtThemeController.apply(corrupt)) }
        waitUntil(timeoutMillis = 10_000) {
            countColor(onNodeWithTag("room-profile-1", useUnmergedTree = true).captureToImage().toAwtImage(), marker) == 0
        }
        onNodeWithTag("room-profile-1", useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(2, profileClicks); assertEquals(0, rowClicks) }
    }

    @Test fun themedHostActionsKeepChatSendingLongPressAndStartReadinessGate() = runDesktopComposeUiTest(width = 800, height = 700) {
        val id = install("action-art", """
            [room.buttons.start]
            backgroundColor = "#873943"
            contentColor = "#F9ECDA"
            [room.buttons.addAI]
            backgroundImage = "room/action.png"
            [room.buttons.sendChat]
            backgroundImage = "room/action.png"
        """.trimIndent(), mapOf("room/action.png" to png(0xFF42697B.toInt())))
        runOnIdle { assertTrue(ArtThemeController.apply(id)) }
        showRoom(WindowManager.Middle)
        onNodeWithTag("room-action-start", useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(0, starts); assertNotNull(UI.warning); UI.warning = null; readiness.forEach { it.ready = true } }
        onNodeWithTag("room-action-start", useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(1, starts) }
        onNodeWithTag("room-action-addAI", useUnmergedTree = true).performTouchInput { longClick() }
        runOnIdle { assertEquals(10, additions) }
        onNode(hasSetTextAction()).performTextInput("Themed chat")
        onNodeWithTag("room-action-sendChat", useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(listOf("Themed chat"), sentMessages) }
        onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun artworkCannotGiveGuestsHostOnlyActions() = runDesktopComposeUiTest(width = 800, height = 700) {
        authority = false
        SwingUtilities.invokeAndWait { RoomSnapshotStore.resample(game) }
        val id = install("guest-action-art", """
            [room.buttons.start]
            backgroundColor = "#873943"
            [room.buttons.addAI]
            backgroundColor = "#334455"
        """.trimIndent())
        runOnIdle { assertTrue(ArtThemeController.apply(id)) }
        showRoom(WindowManager.Middle)
        onNodeWithTag("room-action-start", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("room-action-addAI", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("room-action-options", useUnmergedTree = true).assertIsDisplayed()
        runOnIdle { assertEquals(0, starts); assertEquals(0, additions) }
    }

    @Composable
    private fun PlayerRows(onRow: (Player) -> Unit = {}, onProfile: (Player) -> Unit = {}) {
        Column { players.forEach { player -> RoomPlayerTableRow(player, room, game, update = false,
            onPlayerClick = onRow, onViewProfile = onProfile) } }
    }

    private fun ComposeUiTest.showRoom(mode: WindowManager) {
        show { CompositionLocalProvider(LocalWindowManager provides mode) { MultiplayerRoomView(onExit = {}) } }
        waitUntil(timeoutMillis = 10_000) { roomDetailsReads > 0 }
    }

    private fun ComposeUiTest.show(content: @Composable () -> Unit) {
        setContent { KoinContext(appKoin) { RWPPTheme(default = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF172630)).testTag("room-art-test-root")) { content() }
        } } }
        waitForIdle()
    }

    private fun ComposeUiTest.textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun ComposeUiTest.waitForColor(tag: String, color: Int, minimum: Int = 100) {
        waitUntil(timeoutMillis = 10_000) {
            countColor(onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toAwtImage(), color) >= minimum
        }
    }

    private fun ComposeUiTest.save(name: String) {
        val output = File("../build/artifacts/room-art-compat/previews").apply { mkdirs() }
        ImageIO.write(onNodeWithTag("room-art-test-root").captureToImage().toAwtImage(), "png", File(output, name))
        File(output, "README.txt").writeText("Real Compose waiting-room UI with offline engine test data. These are logical viewport captures, not Android device or multiplayer-game validation.\n")
    }

    private fun install(id: String, config: String, entries: Map<String, ByteArray> = emptyMap()): String {
        val pack = File(work, "$id.rwtheme")
        ZipOutputStream(pack.outputStream()).use { out ->
            (mapOf("theme.toml" to "[theme]\nid=\"$id\"\nname=\"$id\"\n\n$config\n".toByteArray()) + entries).forEach { (path, bytes) ->
                out.putNextEntry(ZipEntry(path)); out.write(bytes); out.closeEntry()
            }
        }
        val installed = runBlocking { ArtThemeController.import(pack) }
        assertTrue(installed.success, "Installer rejected test theme: ${installed.errors}")
        return installed.themeId!!
    }

    /** Package only repository-owned configuration and bitmap assets through the real installer. */
    private fun installRepositoryExample(source: File): String {
        val config = File(source, "theme.toml")
        assertTrue(config.isFile, "Repository theme example is missing")
        val files = linkedSetOf("theme.toml", "strings_zh.toml", "strings_en.toml")
        files += source.listFiles().orEmpty().filter { it.isFile && it.extension == "png" }.map { it.name }
        files += File(source, "buttons").listFiles().orEmpty().filter { it.isFile && it.extension == "png" }
            .map { "buttons/${it.name}" }
        files += Regex("\"([^\"\\r\\n]+\\.png)\"").findAll(config.readText()).map { it.groupValues[1] }.toList()
        val pack = File(work, "repository-example.rwtheme")
        ZipOutputStream(pack.outputStream()).use { zip -> files.forEach { relative ->
            val file = File(source, relative).canonicalFile
            assertTrue(file.toPath().startsWith(source.toPath()) && file.isFile, "Missing example asset: $relative")
            zip.putNextEntry(ZipEntry(relative)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        } }
        val installed = runBlocking { ArtThemeController.import(pack) }
        assertTrue(installed.success, "Installer rejected the repository example: ${installed.errors}")
        assertTrue(installed.warnings.isEmpty(), "Latest repository example must not use legacy room structure fields: ${installed.warnings}")
        return installed.themeId!!
    }

    private fun dominantOpaqueColor(file: File, excluded: Set<Int> = emptySet()): Int {
        val image = assertNotNull(ImageIO.read(file), "Example image is not decodable: ${file.name}")
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            .filter { (it ushr 24) == 255 && it !in excluded }.groupingBy { it }.eachCount().maxBy { it.value }.key
    }

    private fun player(name: String, spawn: Int, team: Int, ai: Boolean = false, host: Boolean = false, ready: Boolean = true): Player {
        val data = PlayerData(ready).also(readiness::add)
        return stub(Player::class.java) { method, _ -> when (method) {
            "getName" -> name
            "getSpawnPoint" -> spawn
            "getTeam" -> team
            "getColor" -> team
            "getStartingUnit" -> -1
            "getPing" -> if (ai) "-" else "36ms"
            "getPingNumber" -> 36
            "getConnectHexId" -> "room-art-$spawn"
            "getData" -> data
            "isAI" -> ai
            "isRoomHost" -> host
            "isSpectator" -> false
            "getDifficulty" -> 1
            "getClient" -> null
            else -> error("Unexpected player access: $method")
        } }
    }

    private fun png(color: Int): ByteArray {
        val bitmap = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) bitmap.setRGB(x, y, color)
        return ByteArrayOutputStream().also { ImageIO.write(bitmap, "png", it) }.toByteArray()
    }

    private fun countColor(bitmap: BufferedImage, color: Int): Int = bitmap.getRGB(0, 0, bitmap.width, bitmap.height,
        null, 0, bitmap.width).count { it == color }

    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>, value: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args -> when (method.name) {
            "equals" -> instance === args!![0]
            "hashCode" -> System.identityHashCode(instance)
            "toString" -> "RoomArt${type.simpleName}"
            else -> value(method.name, args)
        } } as T
}
