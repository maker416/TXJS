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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.account.RoomIdentityController
import io.github.rwpp.app.LauncherVisibility
import io.github.rwpp.appKoin
import io.github.rwpp.coil.AccountAvatar
import io.github.rwpp.coil.AccountAvatarKeyer
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
import io.github.rwpp.net.account.AccountApiClient
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.net.roomid.RoomIdentityClient
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.RoomPlayerCardSpec
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.FileSystem
import okio.buffer
import okio.source
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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
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

    @Test fun playerCardsRenderPackContentAndRetainIndependentPlayerActions() = runDesktopComposeUiTest(width = 700, height = 520) {
        var rowClicks = 0
        var profileClicks = 0
        show { PlayerRows(onRow = { rowClicks++ }, onProfile = { profileClicks++ }) }
        val originalRow = onNodeWithTag("room-player-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val id = install("static-player-cards", playerCardConfig())
        runOnIdle { assertTrue(ArtThemeController.apply(id)) }

        onNodeWithText("房主", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("战绩: 128胜 / 23负", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("进攻专家", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("AI 难度: 困难", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("本地覆盖", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText(localPlayer.name, useUnmergedTree = true).assertIsDisplayed()
        players.forEach { player ->
            onNodeWithTag("room-player-avatar-${player.spawnPoint + 1}", useUnmergedTree = true).assertIsDisplayed()
        }
        val cardRow = onNodeWithTag("room-player-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(cardRow.bottom - cardRow.top > originalRow.bottom - originalRow.top)
        onNodeWithTag("room-player-info-1-0-0", useUnmergedTree = true).performTouchInput { click() }
        runOnIdle { assertEquals(1, rowClicks); assertEquals(0, profileClicks) }
        onNodeWithTag("room-profile-1", useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(1, rowClicks); assertEquals(1, profileClicks) }
        savePlayerCards("static-player-cards.png")

        runOnIdle { assertTrue(ArtThemeController.apply(null)) }
        onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("room-player-info-1-0", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("战绩: 128胜 / 23负", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(originalRow, onNodeWithTag("room-player-1", useUnmergedTree = true).getUnclippedBoundsInRoot())
        onNodeWithTag("room-profile-1", useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(1, rowClicks); assertEquals(2, profileClicks) }
    }

    @Test fun missingAndCorruptPlayerCardAvatarsUseVisibleFallbackAfterThemeSwitch() =
        runDesktopComposeUiTest(width = 700, height = 520) {
            show { PlayerRows() }
            val imageColor = 0xFF37B4D8.toInt()
            val fallbackColor = 0xFFE72CC6.toInt()
            fun config(image: String) = """
                [room.player.card]
                enabled = true
                [room.player.card.avatar]
                tint = "#E72CC6"
                borderWidth = 0
                [room.player.card.content]
                avatarImage = "$image"
                [[room.player.card.content.rows]]
                cells = [{ text = "头像资源回退" }]
            """.trimIndent()
            val valid = install("card-avatar-valid", config("room/avatar.png"), mapOf("room/avatar.png" to png(imageColor)))
            runOnIdle { assertTrue(ArtThemeController.apply(valid)) }
            waitForColor("room-player-avatar-1", imageColor)

            val missing = install("card-avatar-missing", config("room/missing.png"))
            runOnIdle { assertTrue(ArtThemeController.apply(missing)) }
            waitForColor("room-player-avatar-1", fallbackColor, minimum = 5)
            assertEquals(0, countColor(onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).captureToImage().toAwtImage(), imageColor))
            val corrupt = install("card-avatar-corrupt", config("room/broken.png"), mapOf("room/broken.png" to "bad png data".toByteArray()))
            runOnIdle { assertTrue(ArtThemeController.apply(corrupt)) }
            waitForColor("room-player-avatar-1", fallbackColor, minimum = 5)
            waitForColor("room-player-avatar-3", fallbackColor, minimum = 5)
            assertEquals(0, countColor(onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).captureToImage().toAwtImage(), imageColor))
            onNodeWithTag("room-player-info-1-0", useUnmergedTree = true).assertIsDisplayed()
            savePlayerCards("avatar-resource-fallback.png")
        }

    @Test fun accountAvatarWinsOverSlotDefaultsAndThemeChangesUntilLogout() = runDesktopComposeUiTest(width = 700, height = 520) {
        val accountColor = 0xFF367CE9.toInt()
        val firstDefault = 0xFF37B4D8.toInt()
        val secondDefault = 0xFFE49228.toInt()
        val requests = fakeAccountAvatars { png(accountColor) }
        runOnIdle { AccountSession.applyPreview(previewAvatarUser(hasAvatar = true)) }
        val first = install("account-avatar-default-one", accountAvatarCardConfig("room/default.png"),
            mapOf("room/default.png" to png(firstDefault)))
        runOnIdle { assertTrue(ArtThemeController.apply(first)) }
        show { PlayerRows() }
        waitForColor("room-player-avatar-1", accountColor)
        onNodeWithTag("room-player-account-avatar-1", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("room-player-account-avatar-3", useUnmergedTree = true).assertDoesNotExist()
        waitForColor("room-player-avatar-3", firstDefault)

        val second = install("account-avatar-default-two", accountAvatarCardConfig("room/default.png"),
            mapOf("room/default.png" to png(secondDefault)))
        runOnIdle { assertTrue(ArtThemeController.apply(second)) }
        waitForColor("room-player-avatar-3", secondDefault)
        waitForColor("room-player-avatar-1", accountColor)
        val avatar = onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).captureToImage().toAwtImage()
        assertEquals(0, countColor(avatar, firstDefault))
        assertEquals(0, countColor(avatar, secondDefault))
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.userId == 42L && it.hasAvatar }, "AI and unverified guests must not request account avatars")
        savePlayerCards("account-avatar-priority.png")

        runOnIdle { AccountSession.applyLoggedOutPreview() }
        waitForColor("room-player-avatar-1", secondDefault)
        onNodeWithTag("room-player-account-avatar-1", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("room-player-default-avatar-1", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, countColor(onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).captureToImage().toAwtImage(), accountColor))
    }

    @Test fun verifiedRemoteAccountAvatarWinsUntilTheRoomContainsDuplicateNames() =
        runDesktopComposeUiTest(width = 700, height = 520) {
            val defaultColor = 0xFF37B4D8.toInt()
            val remoteColor = 0xFFD64CA2.toInt()
            val remote = players[1]
            val avatarRequests = fakeAccountAvatars { avatar ->
                png(if (avatar.userId == 84L) remoteColor else 0xFF367CE9.toInt())
            }
            val identityRequests = CopyOnWriteArrayList<String>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                identityRequests += request.url.encodedPath
                val body = when (request.url.encodedPath) {
                    "/api/v1/capabilities" -> """{"room_id_membership_verified":true}"""
                    "/api/v1/roomid/lookup" -> if (request.url.queryParameter("player_name") == remote.name) {
                        """{"users":[{"user_id":84,"username":"verified-guest","nickname":"Ready guest"}]}"""
                    } else """{"users":[]}"""
                    "/api/v1/users/lookup" -> {
                        assertEquals("verified-guest", request.url.queryParameter("username"))
                        """{"user":{"id":84,"username":"verified-guest","nickname":"Ready guest","has_avatar":true}}"""
                    }
                    "/api/v1/roomid/publish" -> """{"ok":true}"""
                    else -> error("Unexpected identity request: ${request.url.encodedPath}")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            runOnIdle {
                AccountSession.applyPreview(previewAvatarUser(hasAvatar = false))
                AccountSession.networkEnabled = true
                AccountSession.bindClient(AccountApiClient("https://identity-test.invalid", "preview-app", http))
                RoomIdentityController.clientOverride = RoomIdentityClient(listOf("https://identity-test.invalid"), http)
                RoomIdentityController.onJoiningRoom("Q123456", null)
            }
            runBlocking { RoomIdentityController.prefetchPlayerCards(room) }
            assertEquals(84L, assertNotNull(RoomIdentityController.playerAccountFor(remote, room)).id)
            val config = accountAvatarCardConfig("room/default.png") + """

                [room.player.card.slots."2"]
                avatarImage = "room/default.png"
                [[room.player.card.slots."2".rows]]
                cells = [{ text = "远端玩家槽位默认" }]
            """.trimIndent()
            val id = install("verified-remote-avatar", config, mapOf("room/default.png" to png(defaultColor)))
            runOnIdle { assertTrue(ArtThemeController.apply(id)) }
            show { PlayerRows() }
            waitForColor("room-player-avatar-2", remoteColor)
            onNodeWithTag("room-player-account-avatar-2", useUnmergedTree = true).assertIsDisplayed()
            waitForColor("room-player-avatar-1", defaultColor)
            waitForColor("room-player-avatar-3", defaultColor)
            onNodeWithTag("room-player-account-avatar-3", useUnmergedTree = true).assertDoesNotExist()
            assertTrue(avatarRequests.any { it.userId == 84L && it.hasAvatar })
            assertTrue(avatarRequests.all { it.userId == 84L }, "Only the verified guest has an account avatar")
            assertTrue("/api/v1/users/lookup" in identityRequests)
            savePlayerCards("verified-remote-account-avatar.png")

            SwingUtilities.invokeAndWait {
                players = players + player(remote.name, 4, 0)
                RoomSnapshotStore.resample(game)
            }
            waitForColor("room-player-avatar-2", defaultColor)
            onNodeWithTag("room-player-account-avatar-2", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(0, countColor(onNodeWithTag("room-player-avatar-2", useUnmergedTree = true).captureToImage().toAwtImage(), remoteColor))
            savePlayerCards("duplicate-name-default-avatar.png")
        }

    @Test fun absentAccountAvatarUsesDefaultAndUploadsInvalidateTheVersionUntilDeletion() =
        runDesktopComposeUiTest(width = 700, height = 520) {
            val defaultColor = 0xFF37B4D8.toInt()
            val firstAccountColor = 0xFF367CE9.toInt()
            val updatedAccountColor = 0xFFE75DA3.toInt()
            val bytes = AtomicReference(png(firstAccountColor))
            val requests = fakeAccountAvatars { bytes.get() }
            val mutations = bindAvatarMutationResponses()
            runOnIdle { AccountSession.applyPreview(previewAvatarUser(hasAvatar = false)) }
            val id = install("account-avatar-upload", accountAvatarCardConfig("room/default.png"),
                mapOf("room/default.png" to png(defaultColor)))
            runOnIdle { assertTrue(ArtThemeController.apply(id)) }
            show { PlayerRows() }
            waitForColor("room-player-avatar-1", defaultColor)
            onNodeWithTag("room-player-account-avatar-1", useUnmergedTree = true).assertDoesNotExist()
            assertTrue(requests.isEmpty())

            val startingVersion = AccountSession.avatarVersion
            runBlocking { AccountSession.uploadAvatar(bytes.get(), "first.png", "image/png") }
            waitForColor("room-player-avatar-1", firstAccountColor)
            assertTrue(requests.any { it.version == startingVersion + 1 && it.hasAvatar })
            bytes.set(png(updatedAccountColor))
            runBlocking { AccountSession.uploadAvatar(bytes.get(), "updated.png", "image/png") }
            waitForColor("room-player-avatar-1", updatedAccountColor)
            assertTrue(requests.any { it.version == startingVersion + 2 && it.hasAvatar }, "An upload must invalidate the account avatar cache")
            assertEquals(0, countColor(onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).captureToImage().toAwtImage(), firstAccountColor))

            runBlocking { AccountSession.deleteAvatar() }
            waitForColor("room-player-avatar-1", defaultColor)
            assertEquals(startingVersion + 3, AccountSession.avatarVersion)
            assertFalse(AccountSession.user!!.hasAvatar)
            onNodeWithTag("room-player-account-avatar-1", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(listOf("POST", "POST", "DELETE"), mutations.toList())
            savePlayerCards("account-avatar-deleted-default.png")
        }

    @Test fun failedAndCorruptAccountAvatarsRestoreTheThemeDefaultWithoutStalePixels() =
        runDesktopComposeUiTest(width = 700, height = 520) {
            val defaultColor = 0xFF37B4D8.toInt()
            val accountColor = 0xFF367CE9.toInt()
            val bytes = AtomicReference<ByteArray?>(png(accountColor))
            val requests = fakeAccountAvatars { bytes.get() }
            bindAvatarMutationResponses()
            runOnIdle { AccountSession.applyPreview(previewAvatarUser(hasAvatar = true)) }
            val id = install("account-avatar-errors", accountAvatarCardConfig("room/default.png"),
                mapOf("room/default.png" to png(defaultColor)))
            runOnIdle { assertTrue(ArtThemeController.apply(id)) }
            show { PlayerRows() }
            waitForColor("room-player-avatar-1", accountColor)

            bytes.set(null)
            runBlocking { AccountSession.uploadAvatar(png(accountColor), "unavailable.png", "image/png") }
            val failedVersion = AccountSession.avatarVersion
            waitUntil { requests.any { it.version == failedVersion } }
            waitForColor("room-player-avatar-1", defaultColor)
            assertEquals(0, countColor(onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).captureToImage().toAwtImage(), accountColor))
            bytes.set("invalid image bytes".toByteArray())
            runBlocking { AccountSession.uploadAvatar(png(accountColor), "corrupt.png", "image/png") }
            val corruptVersion = AccountSession.avatarVersion
            waitUntil { requests.any { it.version == corruptVersion } }
            waitForColor("room-player-avatar-1", defaultColor)
            onNodeWithTag("room-player-default-avatar-1", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(0, countColor(onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).captureToImage().toAwtImage(), accountColor))
            savePlayerCards("account-avatar-error-default.png")
        }

    @Test fun playerCardsKeepDesktopListScrollableAndChatDraftOnThemeChanges() =
        playerCardRoom(1280, 1000, WindowManager.Large)

    @Test fun playerCardsKeepMiddleListScrollableAndChatDraftOnThemeChanges() =
        playerCardRoom(800, 700, WindowManager.Middle)

    @Test fun playerCardsKeepNarrowListScrollableAndChatDraftOnThemeChanges() =
        playerCardRoom(360, 900, WindowManager.Small)

    @Test fun vanillaCompactPlayersUse52dpSlotsAnd40dpAvatarsWithoutAnArtPack() = vanillaPlayerDimensions(compact = true)

    @Test fun vanillaDesktopPlayersUse64dpSlotsAnd48dpAvatarsWithoutAnArtPack() = vanillaPlayerDimensions(compact = false)

    private fun vanillaPlayerDimensions(compact: Boolean) = runDesktopComposeUiTest(width = 700, height = 320) {
        var avatarTint = 0
        show {
            avatarTint = MaterialTheme.colorScheme.primary.toArgb()
            PlayerRows(compact = compact)
        }
        assertNull(ArtThemeController.activeTheme)
        players.forEach { player ->
            val slot = player.spawnPoint + 1
            val row = onNodeWithTag("room-player-background-$slot", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val content = onNodeWithTag("room-player-$slot", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val avatar = onNodeWithTag("room-player-avatar-$slot", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals((if (compact) 52 else 64).dp, row.bottom - row.top, "Default slot height $slot")
            assertEquals((if (compact) 48 else 54).dp, content.bottom - content.top, "Default content height $slot")
            assertEquals((if (compact) 40 else 48).dp, avatar.right - avatar.left, "Default avatar width $slot")
            assertEquals(avatar.right - avatar.left, avatar.bottom - avatar.top)
            assertEquals((if (compact) 4 else 3).dp, avatar.top - content.top, "Avatar top breathing space $slot")
            assertEquals((if (compact) 4 else 3).dp, content.bottom - avatar.bottom, "Avatar bottom breathing space $slot")
            val playerRow = hasAnyAncestor(hasTestTag("room-player-$slot"))
            val nameText = onNode(hasText(player.name) and playerRow, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val spawnText = onNode(hasText(slot.toString()) and playerRow, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val rowCenter = (content.top + content.bottom) / 2
            val nameCenter = (nameText.top + nameText.bottom) / 2
            val spawnCenter = (spawnText.top + spawnText.bottom) / 2
            assertTrue(kotlin.math.abs((nameCenter - rowCenter).value) <= 1f,
                "Default player name must be vertically centered in slot $slot: $nameCenter vs $rowCenter")
            assertTrue(kotlin.math.abs((spawnCenter - rowCenter).value) <= 1f,
                "Default spawn text must be vertically centered in slot $slot: $spawnCenter vs $rowCenter")
            assertTrue(kotlin.math.abs((nameCenter - spawnCenter).value) <= 1f,
                "Default name and spawn text must align vertically in slot $slot: $nameCenter vs $spawnCenter")
            val avatarImage = onNodeWithTag("room-player-avatar-$slot", useUnmergedTree = true).captureToImage().toAwtImage()
            val foregroundPixels = countColor(avatarImage, avatarTint)
            assertTrue(foregroundPixels > 8, "Default avatar must contain a visible foreground icon in slot $slot")
            assertTrue(foregroundPixels < avatarImage.width * avatarImage.height / 2,
                "Default avatar icon and background must remain distinguishable in slot $slot")
            onNodeWithTag("room-player-info-$slot-0", useUnmergedTree = true).assertDoesNotExist()
        }
        savePlayerCards(if (compact) "vanilla-compact-52dp-40dp.png" else "vanilla-desktop-64dp-48dp.png")
    }

    @Test fun desktopCapsuleClipsAccountAvatarsToCircleAndRestoresAfterThemeRemoval() =
        runDesktopComposeUiTest(width = 700, height = 320) {
            val marker = 0xFFEC21C2.toInt()
            fakeAccountAvatars { png(marker) }
            runOnIdle { AccountSession.applyPreview(previewAvatarUser(hasAvatar = true)) }
            show { PlayerRows() }
            waitForColor("room-player-avatar-1", marker)
            val originalBounds = onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val originalName = onNodeWithTag("room-cell-name-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val original = playerAvatarPixels(1)
            assertCircleAvatar(original, marker)
            savePlayerCards("desktop-capsule-circle-account-avatar.png")

            val id = install("square-avatar-roundrect-row", """
                [room.player]
                corner = 8
                [room.player.card.avatar]
                corner = 0
                borderWidth = 0
            """.trimIndent())
            runOnIdle { assertTrue(ArtThemeController.apply(id)) }
            waitForColor("room-player-avatar-1", marker)
            val square = playerAvatarPixels(1)
            assertEquals(marker, square.getRGB(square.width / 2, square.height / 2))
            cornerPixels(square, 2).forEach { pixel ->
                assertEquals(marker, pixel, "An explicit square avatar must retain its corners inside the roundrect row")
            }
            assertTrue(countColor(square, marker) > countColor(original, marker),
                "A square theme avatar must preserve more image area than the vanilla circle")
            assertEquals(originalBounds, onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).getUnclippedBoundsInRoot())
            assertEquals(originalName, onNodeWithTag("room-cell-name-1", useUnmergedTree = true).getUnclippedBoundsInRoot())
            savePlayerCards("theme-square-account-avatar-roundrect-row.png")

            runOnIdle { assertTrue(ArtThemeController.apply(null)) }
            waitForColor("room-player-avatar-1", marker)
            val restored = playerAvatarPixels(1)
            assertCircleAvatar(restored, marker)
            assertEquals(countColor(original, marker), countColor(restored, marker),
                "Removing the theme must restore the original circle clipping")
            assertEquals(originalBounds, onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).getUnclippedBoundsInRoot())
            assertEquals(originalName, onNodeWithTag("room-cell-name-1", useUnmergedTree = true).getUnclippedBoundsInRoot())
        }

    @Test fun androidRoundedPlayerRowsKeepRoundedSquareAccountAvatars() =
        runDesktopComposeUiTest(width = 700, height = 320) {
            val marker = 0xFFEC21C2.toInt()
            var rowShape by mutableStateOf<Shape>(CircleShape)
            fakeAccountAvatars { png(marker) }
            runOnIdle { AccountSession.applyPreview(previewAvatarUser(hasAvatar = true)) }
            show { PlayerRows(compact = true, rowShape = rowShape) }
            waitForColor("room-player-avatar-1", marker)
            val bounds = onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val name = onNodeWithTag("room-cell-name-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val circle = playerAvatarPixels(1)
            assertCircleAvatar(circle, marker)

            runOnIdle { rowShape = RoundedCornerShape(8.dp) }
            val rounded = playerAvatarPixels(1)
            assertEquals(marker, rounded.getRGB(rounded.width / 2, rounded.height / 2))
            cornerPixels(rounded, 0).forEach { pixel ->
                assertNotEquals(marker, pixel, "Android avatars must keep their rounded corners")
            }
            cornerPixels(rounded, rounded.width / 8).forEach { pixel ->
                assertEquals(marker, pixel, "An 8dp corner must retain image pixels removed by a circle")
            }
            assertTrue(countColor(rounded, marker) > countColor(circle, marker) + rounded.width * rounded.height / 10,
                "The Android rounded square must keep more source image than the PC circle")
            assertEquals(bounds, onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).getUnclippedBoundsInRoot())
            assertEquals(name, onNodeWithTag("room-cell-name-1", useUnmergedTree = true).getUnclippedBoundsInRoot())
            savePlayerCards("android-roundrect-account-avatar.png")
        }

    @Test fun playerRowClipsSquareAccountImagesAtItsOuterArc() =
        runDesktopComposeUiTest(width = 700, height = 320) {
            val marker = 0xFFEC21C2.toInt()
            fakeAccountAvatars { png(marker) }
            runOnIdle { AccountSession.applyPreview(previewAvatarUser(hasAvatar = true)) }
            val id = install("clip-square-avatar-at-player-arc", """
                [room.player]
                corner = 48
                borderWidth = 0
                [room.player.card.avatar]
                corner = 0
                borderWidth = 0
            """.trimIndent())
            runOnIdle { assertTrue(ArtThemeController.apply(id)) }
            show { PlayerRows() }
            waitForColor("room-player-avatar-1", marker)
            val image = playerAvatarPixels(1)
            assertEquals(marker, image.getRGB(image.width / 2, image.height / 2))
            assertNotEquals(marker, image.getRGB(1, 1), "The parent row must clip pixels beyond its top-left arc")
            assertNotEquals(marker, image.getRGB(1, image.height - 2), "The parent row must clip pixels beyond its bottom-left arc")
            assertEquals(marker, image.getRGB(image.width - 2, 1), "Pixels within the parent row must remain visible")
            assertEquals(marker, image.getRGB(image.width - 2, image.height - 2))
            savePlayerCards("parent-player-arc-clips-square-avatar.png")
        }

    @Test fun vanillaCompactWaitingRoomShowsAccountAvatarsAndAlignedColumns() =
        runDesktopComposeUiTest(width = 1000, height = 700) {
            val accountColor = 0xFF367CE9.toInt()
            val requests = fakeAccountAvatars { png(accountColor) }
            runOnIdle { AccountSession.applyPreview(previewAvatarUser(hasAvatar = true)) }
            showRoom(WindowManager.Middle)
            assertNull(ArtThemeController.activeTheme)
            waitForColor("room-player-avatar-1", accountColor)
            onNodeWithTag("room-player-account-avatar-1", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("room-player-avatar-fallback-3", useUnmergedTree = true).assertIsDisplayed()
            val avatar = onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val row = onNodeWithTag("room-player-background-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(40.dp, avatar.right - avatar.left)
            assertEquals(52.dp, row.bottom - row.top)
            assertPlayerSpawnColumnAligned()
            assertTrue(requests.all { it.userId == 42L })
            savePlayerCards("vanilla-room-1000x700-account-avatar.png")
        }

    @Test fun staticInformationExpandsAvatarsWithinTheCompactLimitAndKeepsColumnsUniform() =
        staticAvatarExpansion(compact = true)

    @Test fun staticInformationExpandsAvatarsWithinTheDesktopLimitAndKeepsColumnsUniform() =
        staticAvatarExpansion(compact = false)

    private fun staticAvatarExpansion(compact: Boolean) = runDesktopComposeUiTest(width = 700, height = 650) {
        show { PlayerRows(compact = compact) }
        val originalName = onNodeWithTag("room-cell-name-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val id = install("adaptive-avatar", """
            [room.player.card]
            enabled = true
            [room.player.card.avatar]
            size = 64
            [[room.player.card.content.rows]]
            cells = [{ text = "战绩信息" }]
            [[room.player.card.content.rows]]
            cells = [{ text = "更多静态信息" }]
            [[room.player.card.content.rows]]
            cells = [{ text = "第三行信息" }]
            [room.player.card.slots."2"]
            rows = []
        """.trimIndent())
        runOnIdle { assertTrue(ArtThemeController.apply(id)) }
        val expandedAvatar = onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val singleAvatar = onNodeWithTag("room-player-avatar-2", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals((if (compact) 56 else 64).dp, expandedAvatar.right - expandedAvatar.left)
        assertEquals((if (compact) 40 else 48).dp, singleAvatar.right - singleAvatar.left)
        val firstName = onNodeWithTag("room-cell-name-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val secondName = onNodeWithTag("room-cell-name-2", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(firstName.left, secondName.left, "Different avatar sizes must share one table column")
        assertEquals(firstName.right - firstName.left, secondName.right - secondName.left)
        savePlayerCards(if (compact) "adaptive-compact-avatar-limit.png" else "adaptive-desktop-avatar-limit.png")
        runOnIdle { assertTrue(ArtThemeController.apply(null)) }
        onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("room-player-info-1-0", useUnmergedTree = true).assertDoesNotExist()
        val restoredName = onNodeWithTag("room-cell-name-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(originalName, restoredName)
    }

    @Test fun temporaryStatusHeightDoesNotResizeAvatarsOrShiftTheirColumn() =
        runDesktopComposeUiTest(width = 700, height = 320) {
            var expanded by mutableStateOf(false)
            show {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).testTag("temporary-status-row")) {
                    RoomPlayerCardBody(RoomPlayerCardSpec(), localPlayer, room, compact = true) {
                        Column(Modifier.weight(1f).testTag("temporary-status-name")) {
                            Text(localPlayer.name)
                            if (expanded) repeat(4) { Text("正在同步：临时状态 $it") }
                        }
                    }
                }
            }
            val beforeAvatar = onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val beforeName = onNodeWithTag("temporary-status-name", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val beforeRow = onNodeWithTag("temporary-status-row", useUnmergedTree = true).getUnclippedBoundsInRoot()
            runOnIdle { expanded = true }
            val afterAvatar = onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val afterName = onNodeWithTag("temporary-status-name", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val afterRow = onNodeWithTag("temporary-status-row", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(40.dp, afterAvatar.right - afterAvatar.left)
            assertEquals(beforeAvatar.right - beforeAvatar.left, afterAvatar.right - afterAvatar.left)
            assertEquals(beforeAvatar.bottom - beforeAvatar.top, afterAvatar.bottom - afterAvatar.top)
            assertEquals(beforeName.left, afterName.left)
            assertEquals(beforeName.right - beforeName.left, afterName.right - afterName.left)
            assertTrue(afterRow.bottom - afterRow.top > beforeRow.bottom - beforeRow.top)
            savePlayerCards("temporary-status-stable-avatar.png")
            runOnIdle { expanded = false }
            assertEquals(beforeRow, onNodeWithTag("temporary-status-row", useUnmergedTree = true).getUnclippedBoundsInRoot())
        }

    @Test fun doubledFontScaleKeepsCompactAvatarsWithin56dpAndHeaderAligned() =
        runDesktopComposeUiTest(width = 1000, height = 900) {
            val id = install("large-font-avatar", playerCardConfig()
                .replace("[room.player.card.avatar]", "[room.player.card.avatar]\nsize = 64"))
            runOnIdle { assertTrue(ArtThemeController.apply(id)) }
            show {
                val originalDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(originalDensity.density, fontScale = 2f),
                    LocalWindowManager provides WindowManager.Middle,
                ) { MultiplayerRoomView(onExit = {}) }
            }
            waitUntil(timeoutMillis = 10_000) { roomDetailsReads > 0 }
            val avatar = onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(56.dp, avatar.right - avatar.left)
            assertEquals(56.dp, avatar.bottom - avatar.top)
            onNodeWithTag("room-player-info-1-0", useUnmergedTree = true).assertIsDisplayed()
            assertPlayerSpawnColumnAligned()
            savePlayerCards("compact-font-scale-2-avatar-limit.png")
        }

    @Test fun disablingBothCardFeaturesPreservesSuppliedBusinessCellGeometry() =
        runDesktopComposeUiTest(width = 700, height = 320) {
            val defaults = RoomPlayerCardSpec()
            val disabled = defaults.copy(enabled = false, avatar = defaults.avatar.copy(enabled = false))
            show {
                Column {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).testTag("bare-business-row")) {
                        Box(Modifier.weight(1f).height(18.dp).testTag("bare-business-cell"))
                        Box(Modifier.width(30.dp).height(18.dp))
                    }
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).testTag("disabled-features-row")) {
                        RoomPlayerCardBody(disabled, localPlayer, room, compact = true) {
                            Box(Modifier.weight(1f).height(18.dp).testTag("disabled-features-cell"))
                            Box(Modifier.width(30.dp).height(18.dp))
                        }
                    }
                }
            }
            val bareRow = onNodeWithTag("bare-business-row").getUnclippedBoundsInRoot()
            val disabledRow = onNodeWithTag("disabled-features-row").getUnclippedBoundsInRoot()
            val bareCell = onNodeWithTag("bare-business-cell").getUnclippedBoundsInRoot()
            val disabledCell = onNodeWithTag("disabled-features-cell").getUnclippedBoundsInRoot()
            assertEquals(18.dp, disabledRow.bottom - disabledRow.top, "Disabled features must not add a minimum height")
            assertEquals(bareRow.bottom - bareRow.top, disabledRow.bottom - disabledRow.top)
            assertEquals(bareCell.left, disabledCell.left, "Disabled features must not reserve an avatar column")
            assertEquals(bareCell.right - bareCell.left, disabledCell.right - disabledCell.left)
            onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("room-player-info-1-0", useUnmergedTree = true).assertDoesNotExist()
        }

    @Test fun disablingStaticInformationKeepsTheThemeDefaultAvatarVisible() =
        runDesktopComposeUiTest(width = 700, height = 320) {
            val defaultColor = 0xFF37B4D8.toInt()
            val id = install("avatar-without-static-info", accountAvatarCardConfig("room/default.png")
                .replace("enabled = true", "enabled = false"), mapOf("room/default.png" to png(defaultColor)))
            runOnIdle { assertTrue(ArtThemeController.apply(id)) }
            show { PlayerRows(compact = true) }
            waitForColor("room-player-avatar-1", defaultColor)
            onNodeWithTag("room-player-default-avatar-1", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("room-player-info-1-0", useUnmergedTree = true).assertDoesNotExist()
            val avatar = onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val row = onNodeWithTag("room-player-background-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(40.dp, avatar.right - avatar.left)
            assertEquals(52.dp, row.bottom - row.top)
            savePlayerCards("theme-default-avatar-without-static-info.png")
        }

    @Test fun hidingPlayerCardAvatarsKeepsInfoAndReturnsAvatarSpaceToEngineColumns() = runDesktopComposeUiTest(width = 700, height = 520) {
        show { PlayerRows() }
        val originalName = onNodeWithTag("room-cell-name-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val withAvatar = install("card-with-avatar", playerCardConfig()
            .replace("[room.player.card.avatar]", "[room.player.card.avatar]\nsize = 64"))
        runOnIdle { assertTrue(ArtThemeController.apply(withAvatar)) }
        val avatarName = onNodeWithTag("room-cell-name-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(avatarName.right - avatarName.left < originalName.right - originalName.left)

        val withoutAvatar = install("card-without-avatar", playerCardConfig()
            .replace("[room.player.card.avatar]", "[room.player.card.avatar]\nenabled = false"))
        runOnIdle { assertTrue(ArtThemeController.apply(withoutAvatar)) }
        onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("room-player-info-1-0", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("战绩: 128胜 / 23负", useUnmergedTree = true).assertIsDisplayed()
        val hiddenAvatarName = onNodeWithTag("room-cell-name-1", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(hiddenAvatarName.left < originalName.left)
        assertTrue(hiddenAvatarName.right - hiddenAvatarName.left > originalName.right - originalName.left)
        savePlayerCards("player-cards-without-avatars.png")
        runOnIdle { assertTrue(ArtThemeController.apply(null)) }
        onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(originalName, onNodeWithTag("room-cell-name-1", useUnmergedTree = true).getUnclippedBoundsInRoot())
    }

    @Test fun repositoryPlayerCardExampleRendersGreenHostAndAiCards() = runDesktopComposeUiTest(width = 1000, height = 700) {
        val source = File("../examples/themes/room-player-cards").canonicalFile
        val id = installRepositoryExample(source)
        runOnIdle { assertTrue(ArtThemeController.apply(id)) }
        showRoom(WindowManager.Middle)
        onNodeWithText("房主", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("战绩：128胜 / 23负", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("进攻专家", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("room-player-avatar-fallback-1", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("room-player-avatar-fallback-3", useUnmergedTree = true).assertIsDisplayed()
        waitForColor("room-player-avatar-3", 0xFFA8D562.toInt(), minimum = 5)
        savePlayerCards("green-player-cards.png")
        runOnIdle { assertEquals(0, networkAttempts.get()) }
    }

    private fun playerCardRoom(width: Int, height: Int, mode: WindowManager) =
        runDesktopComposeUiTest(width = width, height = height) {
            players = players + (4..7).map { index ->
                player("Commander ${index + 1} with a very long display name", index, index % 2, ai = index == 5)
            }
            SwingUtilities.invokeAndWait { RoomSnapshotStore.resample(game) }
            showRoom(mode)
            val input = onNodeWithTag("room-chat-input", useUnmergedTree = true)
            input.performTextInput("Unsent player card draft")
            val panelBefore = onNodeWithTag("room-panel-players", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val id = install("scrollable-player-cards", playerCardConfig() + """

                [[room.player.card.slots."8".rows]]
                cells = [{ text = "最后一个玩家" }, { text = "额外信息仍可见" }]
            """.trimIndent())
            runOnIdle { assertTrue(ArtThemeController.apply(id)) }
            input.assertIsFocused().assertTextContains("Unsent player card draft")
            val panelAfter = onNodeWithTag("room-panel-players", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(panelBefore.right - panelBefore.left, panelAfter.right - panelAfter.left,
                "Player cards must retain the room panel width")
            onNodeWithTag("room-player-avatar-1", useUnmergedTree = true).assertIsDisplayed()
            assertPlayerSpawnColumnAligned()
            savePlayerCards("room-${width}x${height}-player-cards.png")

            val list = onNodeWithTag(if (mode == WindowManager.Large) "desktopRoomPlayers" else "compactRoomPlayers",
                useUnmergedTree = true)
            list.performScrollToNode(hasTestTag("room-player-8"))
            onNodeWithText("最后一个玩家", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("room-player-avatar-8", useUnmergedTree = true).assertIsDisplayed()
            input.assertIsFocused().assertTextContains("Unsent player card draft")
            savePlayerCards("room-${width}x${height}-player-cards-scrolled.png")

            runOnIdle { assertTrue(ArtThemeController.apply(null)) }
            input.assertIsFocused().assertTextContains("Unsent player card draft")
            onNodeWithTag("room-player-avatar-8", useUnmergedTree = true).assertExists()
            onNodeWithTag("room-player-info-8-0", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("room-player-8", useUnmergedTree = true).assertExists()
        }

    private fun playerCardConfig() = """
        [room.player]
        backgroundColor = "#181E15"
        borderColor = "#9ED351"
        corner = 8
        [room.player.card]
        enabled = true
        [room.player.card.avatar]
        backgroundColor = "#293920"
        borderColor = "#9ED351"
        tint = "#9ED351"
        [[room.player.card.content.rows]]
        cells = [{ text = "普通成员", contentColor = "#9ED351" }, { text = "战绩: 12胜 / 3负" }]
        [[room.player.card.local.rows]]
        cells = [{ text = "本地覆盖" }]
        [[room.player.card.ai.rows]]
        cells = [{ text = "进攻专家", contentColor = "#EDAEF4", backgroundColor = "#632A6E" }, { text = "AI 难度: 困难" }]
        [[room.player.card.slots."1".rows]]
        cells = [{ text = "房主", contentColor = "#FFF2B3", backgroundColor = "#725515" }, { text = "战绩: 128胜 / 23负" }]
    """.trimIndent()

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
    private fun PlayerRows(
        onRow: (Player) -> Unit = {},
        onProfile: (Player) -> Unit = {},
        compact: Boolean = false,
        rowShape: Shape = CircleShape,
    ) {
        Column { players.forEach { player -> RoomPlayerTableRow(player, room, game, update = false,
            onPlayerClick = onRow, onViewProfile = onProfile, compact = compact, rowShape = rowShape) } }
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

    private fun ComposeUiTest.assertPlayerSpawnColumnAligned() {
        val header = onNodeWithText(readI18n("multiplayer.room.colSpawn"), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val value = onNode(hasText("1") and hasAnyAncestor(hasTestTag("room-player-1")),
            useUnmergedTree = true).getUnclippedBoundsInRoot()
        val headerCenter = (header.left + header.right) / 2
        val valueCenter = (value.left + value.right) / 2
        assertTrue(kotlin.math.abs((headerCenter - valueCenter).value) <= 1.5f,
            "Header and player spawn column must align: $headerCenter vs $valueCenter")
    }

    private fun ComposeUiTest.waitForColor(tag: String, color: Int, minimum: Int = 100) {
        waitUntil(timeoutMillis = 10_000) {
            countColor(onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toAwtImage(), color) >= minimum
        }
    }

    /** Capture the whole scene first so these pixels include ancestor row clipping. */
    private fun ComposeUiTest.playerAvatarPixels(slot: Int): BufferedImage {
        val root = onNodeWithTag("room-art-test-root")
        val rootBounds = root.getUnclippedBoundsInRoot()
        val avatarBounds = onNodeWithTag("room-player-avatar-$slot", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val screenshot = root.captureToImage().toAwtImage()
        val scale = screenshot.width / (rootBounds.right - rootBounds.left).value
        val left = kotlin.math.round((avatarBounds.left - rootBounds.left).value * scale).toInt()
        val top = kotlin.math.round((avatarBounds.top - rootBounds.top).value * scale).toInt()
        val width = kotlin.math.round((avatarBounds.right - avatarBounds.left).value * scale).toInt()
        val height = kotlin.math.round((avatarBounds.bottom - avatarBounds.top).value * scale).toInt()
        return screenshot.getSubimage(left, top, width, height)
    }

    private fun cornerPixels(image: BufferedImage, inset: Int): List<Int> = listOf(
        image.getRGB(inset, inset), image.getRGB(image.width - inset - 1, inset),
        image.getRGB(inset, image.height - inset - 1), image.getRGB(image.width - inset - 1, image.height - inset - 1),
    )

    private fun assertCircleAvatar(image: BufferedImage, marker: Int) {
        assertEquals(marker, image.getRGB(image.width / 2, image.height / 2), "The account image center must remain visible")
        cornerPixels(image, 0).forEach { assertNotEquals(marker, it, "A circular avatar must clip all four corners") }
        cornerPixels(image, image.width / 8).forEach {
            assertNotEquals(marker, it, "Circle clipping must remove diagonal pixels retained by an 8dp rounded square")
        }
    }

    private fun ComposeUiTest.save(name: String) {
        val output = File("../build/artifacts/room-art-compat/previews").apply { mkdirs() }
        ImageIO.write(onNodeWithTag("room-art-test-root").captureToImage().toAwtImage(), "png", File(output, name))
        File(output, "README.txt").writeText("Real Compose waiting-room UI with offline engine test data. These are logical viewport captures, not Android device or multiplayer-game validation.\n")
    }

    private fun ComposeUiTest.savePlayerCards(name: String) {
        val output = File("../build/artifacts/room-player-cards/previews").apply { mkdirs() }
        ImageIO.write(onNodeWithTag("room-art-test-root").captureToImage().toAwtImage(), "png", File(output, name))
        File(output, "README.txt").writeText("Real Compose waiting-room UI with offline engine test data. Player card text is static theme content. These are logical viewport captures, not Android device or multiplayer-game validation.\n")
    }

    private fun previewAvatarUser(hasAvatar: Boolean) = AccountUser(
        id = 42, username = "commander", nickname = localPlayer.name, hasAvatar = hasAvatar,
    )

    private fun accountAvatarCardConfig(defaultImage: String) = """
        [room.player.card]
        enabled = true
        [room.player.card.content]
        avatarImage = "$defaultImage"
        [[room.player.card.content.rows]]
        cells = [{ text = "账号头像优先，主题提供默认头像" }]
        [room.player.card.slots."1"]
        avatarImage = "$defaultImage"
        [[room.player.card.slots."1".rows]]
        cells = [{ text = "本地槽位默认头像" }]
    """.trimIndent()

    /** Exercise the real model/key while keeping image delivery entirely in memory. */
    private fun fakeAccountAvatars(bytes: (AccountAvatar) -> ByteArray?): CopyOnWriteArrayList<AccountAvatar> {
        val requests = CopyOnWriteArrayList<AccountAvatar>()
        SingletonImageLoader.setUnsafe { context -> ImageLoader.Builder(context).components {
            add(ImageableFetcherFactory()); add(ImageableKeyer()); add(AccountAvatarKeyer())
            add(object : Fetcher.Factory<AccountAvatar> {
                override fun create(data: AccountAvatar, options: Options, imageLoader: ImageLoader): Fetcher = object : Fetcher {
                    override suspend fun fetch(): FetchResult {
                        requests += data
                        check(data.hasAvatar) { "A player without an account avatar must use the theme default" }
                        val response = bytes(data) ?: throw IOException("Simulated account avatar request failure")
                        return SourceFetchResult(
                            source = ImageSource(ByteArrayInputStream(response).source().buffer(), fileSystem = FileSystem.SYSTEM),
                            mimeType = "image/png", dataSource = DataSource.MEMORY,
                        )
                    }
                }
            })
        }.build() }
        return requests
    }

    /** Real upload/delete session updates, with intercepted responses and no socket or HTTP server. */
    private fun bindAvatarMutationResponses(): CopyOnWriteArrayList<String> {
        val methods = CopyOnWriteArrayList<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.encodedPath == "/api/v1/users/me/avatar") { "Unexpected account request: ${request.url.encodedPath}" }
            check(request.method == "POST" || request.method == "DELETE")
            methods += request.method
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"ok":true,"has_avatar":${request.method == "POST"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        AccountSession.bindClient(AccountApiClient("https://avatar-test.invalid", "preview-app", http))
        return methods
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
        val files = linkedSetOf("theme.toml")
        files += listOf("strings_zh.toml", "strings_en.toml").filter { File(source, it).isFile }
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
