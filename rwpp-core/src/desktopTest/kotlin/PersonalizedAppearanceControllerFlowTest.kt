/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.RoomIdentityController
import io.github.rwpp.app.LauncherVisibility
import io.github.rwpp.appKoin
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.Settings
import io.github.rwpp.config.resolveOpenPlatformUrl
import io.github.rwpp.core.RoomSnapshotStore
import io.github.rwpp.game.Game
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.Player
import io.github.rwpp.game.data.PlayerData
import io.github.rwpp.game.map.MapType
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.net.account.PublicUser
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.net.account.AccountApiClient
import io.github.rwpp.net.appearance.AppearanceAssignment
import io.github.rwpp.net.appearance.AppearanceResolution
import io.github.rwpp.net.appearance.VerifiedAppearanceMember
import io.github.rwpp.net.appearance.VerifiedAppearanceRoster
import io.github.rwpp.net.appearance.VerifiedIdentityChallenge
import io.github.rwpp.net.appearance.IdentityChatCodec
import io.github.rwpp.net.games.OnlineGameArtBinding
import io.github.rwpp.ui.LauncherPage
import io.github.rwpp.ui.resetNavigation
import io.github.rwpp.ui.RoomPlayerTableRow
import io.github.rwpp.widget.RWPPTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.compose.KoinContext
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real controller, native loopback Socket and HTTP client; the game host supplies deterministic facts. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class, DelicateCoilApi::class)
class PersonalizedAppearanceControllerFlowTest {
    private lateinit var work: File
    private lateinit var server: MockWebServer
    private lateinit var listener: ServerSocket
    private lateinit var socket: Socket
    private lateinit var accepted: Socket
    private lateinit var game: Game
    private lateinit var room: GameRoom
    private lateinit var target: Member
    private lateinit var other: Member
    @Volatile private var members = emptyList<Player>()
    private var controller: Job? = null
    private var previousPlatformUrl: String? = null
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val commands = CopyOnWriteArrayList<String>()
    private val clientNonce = AtomicReference("")
    private val failure = AtomicReference<Throwable?>()
    private val revision = AtomicLong(10)
    private val rosterRequests = AtomicInteger()
    private val resolveRequests = AtomicInteger()
    @Volatile private var badChallenge = false
    @Volatile private var rosterFails = false
    @Volatile private var resolutionLifetime = 50L
    @Volatile private var resolveGate: Gate? = null
    @Volatile private var membersGate: Gate? = null
    @Volatile private var hideFromStrangers = false
    @Volatile private var gameScope: String? = null
    @Volatile private var boundCatalogOverride: String? = null
    private val packId = "01234567-89ab-cdef-0123-456789abcdef"

    @BeforeTest
    fun setup() = runBlocking {
        runCatching { stopKoin() }
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
        logger = LoggerFactory.getLogger("PersonalizedAppearanceControllerFlowTest")
        target = Member("Target", 1)
        other = Member("Other", 2)
        members = listOf(target.player, other.player)
        room = stub(GameRoom::class.java) { method, args -> when (method) {
            "getPlayers" -> members
            "getLocalPlayer" -> other.player
            "isConnecting" -> true
            "isSinglePlayerGame", "isStartGame", "isStartingGame", "isHost", "isHostServer" -> false
            "getSelectedMap" -> null
            "getMapType" -> MapType.SkirmishMap
            "getDisplayMapName" -> "Test map"
            "sendChatMessage" -> {
                val text = args!![0] as String
                commands += text
                if (text.startsWith("/txjsid hello ")) {
                    val nonce = text.substringAfterLast(' ')
                    clientNonce.set(nonce)
                    assertTrue(PersonalizedAppearanceController.receiveChatPacket(socket,
                        chatPacket("[RWJSID1] $nonce ${"a".repeat(64)} ${"b".repeat(64)}")))
                }
                Unit
            }
            else -> error("Unexpected game room access: $method")
        } }
        game = stub(Game::class.java) { method, _ -> when (method) {
            "getGameRoom" -> room
            "getStartingUnitOptions" -> emptyList<Pair<Int, String>>()
            else -> error("Unexpected game access: $method")
        } }
        appKoin = startKoin { modules(module {
            single { Settings(autoCheckUpdate = false, enableAnimations = false, language = "zh", launcherMusicVolume = 0f) }
            single { AccountPreferences(appKey = "ak_test") }
            single<Game> { game }
        }) }.koin
        koinInit = true
        withContext(Dispatchers.Main) {
            AccountSession.resetForTests()
            AccountSession.networkEnabled = true // Anonymous observers may read verified members without an account claim.
            RoomIdentityController.resetForTests()
        }
        server = MockWebServer().apply { dispatcher = dispatcher(); start() }
        previousPlatformUrl = System.getProperty("rwjs.openPlatform.url")
        System.setProperty("rwjs.openPlatform.url", "http://127.0.0.1:${server.port}")
        assertEquals("http://127.0.0.1:${server.port}", resolveOpenPlatformUrl())
        work = Files.createTempDirectory("rwjs-appearance-flow-").toFile()
        ArtThemeController.themeRootOverride = File(work, "themes").apply { mkdirs() }
        writeTheme("first", packId, 0xFF2653AE.toInt())
        writeTheme("second", "11234567-89ab-cdef-0123-456789abcdef", 0xFF25A979.toInt())
        withContext(Dispatchers.Main) {
            ArtThemeController.scan()
            assertTrue(ArtThemeController.apply("first"))
            assertNotNull(ArtThemeController.activeTheme?.personalizedCatalog)
            LauncherVisibility.setVisible(true)
            resetNavigation(LauncherPage.Room)
            RoomSnapshotStore.resample(game)
        }
        listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        socket = Socket("127.0.0.1", listener.localPort)
        accepted = listener.accept()
        PersonalizedAppearanceController.onGameSocketConnected(socket)
    }

    @AfterTest
    fun cleanup() = runBlocking {
        resolveGate?.release?.countDown()
        membersGate?.release?.countDown()
        controller?.cancelAndJoin()
        if (::socket.isInitialized) socket.close()
        if (::accepted.isInitialized) accepted.close()
        if (::listener.isInitialized) listener.close()
        withContext(Dispatchers.Main) {
            LauncherVisibility.setVisible(false)
            AccountSession.resetForTests()
            RoomIdentityController.resetForTests()
            ArtThemeController.apply(null)
            ArtThemeController.themeRootOverride = null
            resetNavigation(LauncherPage.MainMenu)
        }
        if (previousPlatformUrl == null) System.clearProperty("rwjs.openPlatform.url")
        else System.setProperty("rwjs.openPlatform.url", previousPlatformUrl)
        if (::server.isInitialized) server.shutdown()
        if (::work.isInitialized) work.deleteRecursively()
        SingletonImageLoader.reset()
        stopKoin()
        koinInit = false
        LauncherVisibility.setVisible(true)
        Dispatchers.resetMain()
    }

    @Test
    fun realHttpSelectsOnlyTheVerifiedTargetAndClearsOnMoveReplacementAndThemeSwitch() = runBlocking {
        startController()
        await { variant(target, "room.player.background") != null }
        assertEquals("target.png", variant(target, "room.player.background")?.image)
        assertEquals("#FFD700", variant(target, "room.player.frame")?.borderColor)
        assertEquals("Supporter", variant(target, "room.player.info")?.content?.rows?.single()?.cells?.single()?.text)
        assertNull(variant(other, "room.player.background"))
        assertFalse(AccountSession.loggedIn)
        assertTrue(requests.none { it.path.orEmpty().contains("/claims") || it.getHeader("Authorization") != null || it.getHeader("X-App-Key") != null })
        val lookup = requests.single { it.requestUrl?.encodedPath == "/api/v1/identity/servers/lookup" }
        assertEquals("127.0.0.1:${listener.localPort}", lookup.requestUrl?.queryParameter("endpoint"))
        withContext(Dispatchers.Main) {
            target.slot = 3
            RoomSnapshotStore.resample(game)
            assertNull(PersonalizedAppearanceController.variantFor(target.player, "room.player.background"))
        }
        revision.incrementAndGet()
        await { variant(target, "room.player.background") != null }
        val replacement = Member(target.name, target.slot)
        withContext(Dispatchers.Main) {
            members = listOf(replacement.player, other.player)
            RoomSnapshotStore.resample(game)
            assertNull(PersonalizedAppearanceController.variantFor(replacement.player, "room.player.background"))
            assertTrue(ArtThemeController.apply("second"))
            assertNull(PersonalizedAppearanceController.variantFor(target.player, "room.player.background"))
        }
    }

    @Test
    fun rosterHttpFailureClearsVisibleContentWithoutUsingThePublicNicknameCache() = runBlocking {
        startController()
        await { variant(target, "room.player.background") != null }
        withContext(Dispatchers.Main) {
            RoomIdentityController.cardAvailability[target.name] = true
            RoomIdentityController.playerAccounts[target.name] = PublicUser(101, "untrusted-publication", "Target", accountId = "101")
        }
        val previous = rosterRequests.get()
        rosterFails = true
        await { rosterRequests.get() > previous && variant(target, "room.player.background") == null }
        assertTrue(requests.none { it.path.orEmpty().contains("/roomid/") })
    }

    @Test
    fun actualSocketLookupScopesAssignmentsToTheApprovedGameAndExactPackVersion() = runBlocking {
        gameScope = "11111111-1111-1111-1111-111111111111"
        startController()
        await { variant(target, "room.player.background") != null }
        val request = requests.first { it.requestUrl?.encodedPath?.endsWith("/resolve") == true }
        assertEquals(gameScope, Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["game_app_id"]?.jsonPrimitive?.content)
        assertEquals("target.png", variant(target, "room.player.background")?.image)
        assertNull(variant(other, "room.player.background"))
    }

    @Test
    fun aDifferentApprovedCatalogNeverReceivesAppearanceRequestsForTheLocalPack() = runBlocking {
        gameScope = "11111111-1111-1111-1111-111111111111"
        boundCatalogOverride = "0".repeat(64)
        startController()
        await { rosterRequests.get() > 0 }
        delay(600)
        assertEquals(0, resolveRequests.get())
        assertNull(variant(target, "room.player.background"))
    }

    @Test
    fun expiredResolutionReturnsToDefaultBeforeAnotherRosterPoll() = runBlocking {
        resolutionLifetime = 2
        startController()
        await { variant(target, "room.player.background") != null }
        val requestsBeforeExpiry = rosterRequests.get()
        await { variant(target, "room.player.background") == null }
        assertEquals(requestsBeforeExpiry, rosterRequests.get(), "expiry must clear the visible binding without waiting for the 5s network refresh")
    }

    @Test
    fun monotonicRosterRevisionIsEnforcedByTheRealHttpFlow() = runBlocking {
        startController()
        await { variant(target, "room.player.background") != null }
        val previous = rosterRequests.get()
        revision.set(9)
        await { rosterRequests.get() > previous && variant(target, "room.player.background") == null }
    }

    @Test
    fun mismatchedChallengeNeverRequestsAccountsOrAppearanceAssignments() = runBlocking {
        badChallenge = true
        startController()
        await { requests.any { it.requestUrl?.encodedPath.orEmpty().startsWith("/api/v1/identity/challenges/") } }
        withContext(Dispatchers.Main) { delay(100) }
        assertNull(variant(target, "room.player.background"))
        assertEquals(0, rosterRequests.get())
        assertEquals(0, resolveRequests.get())
        assertTrue(commands.none { it.startsWith("/txjsid prove ") })
    }

    @Test
    fun onlyTheCurrentSocketAndOriginalServerSourceCanUnlockThePostAdmissionHello() = runBlocking {
        startController(ready = false)
        await { requests.any { it.requestUrl?.encodedPath == "/api/v1/identity/servers/lookup" } }
        assertFalse(PersonalizedAppearanceController.receiveChatPacket(socket, chatPacket(IdentityChatCodec.READY_NOTICE, source = 42)))
        assertFalse(PersonalizedAppearanceController.receiveChatPacket(accepted, chatPacket(IdentityChatCodec.READY_NOTICE)))
        withContext(Dispatchers.Main) { delay(650) }
        assertTrue(commands.none { it.startsWith("/txjsid hello ") }, "a matching nickname or an old socket cannot make the client send pre-admission chat")
        assertTrue(PersonalizedAppearanceController.receiveChatPacket(socket, chatPacket(IdentityChatCodec.READY_NOTICE)))
        await { variant(target, "room.player.background") != null }
    }

    @Test
    fun switchingPacksDiscardsAResponseAlreadyInFlight() = runBlocking {
        val gate = Gate().also { resolveGate = it }
        startController()
        await { gate.entered.count == 0L }
        withContext(Dispatchers.Main) { assertTrue(ArtThemeController.apply("second")) }
        gate.release.countDown()
        await { gate.responded.count == 0L }
        withContext(Dispatchers.Main) { delay(150) }
        assertNull(variant(target, "room.player.background"))
    }

    @Test
    fun aLateRosterCannotBindAnAccountToASameNameSameSlotReplacement() = runBlocking {
        val gate = Gate().also { membersGate = it }
        startController()
        await { gate.entered.count == 0L }
        val replacement = Member(target.name, target.slot)
        withContext(Dispatchers.Main) {
            members = listOf(replacement.player, other.player)
            RoomSnapshotStore.resample(game)
        }
        gate.release.countDown()
        await { resolveRequests.get() > 0 && gate.responded.count == 0L }
        withContext(Dispatchers.Main) { delay(150) }
        assertNull(variant(replacement, "room.player.background"), "the old account proof must remain tied to the original player object")
    }

    @Test
    fun visibleExpiryStillAdvancesWhileTheNextHttpRefreshIsBlocked() = runBlocking {
        resolutionLifetime = 6
        startController()
        await { variant(target, "room.player.background") != null }
        val gate = Gate().also { membersGate = it }
        await { gate.entered.count == 0L }
        await { variant(target, "room.player.background") == null }
        assertEquals(1L, gate.responded.count, "the independent expiry clock must work before HTTP completes")
    }

    @Test
    fun accountClaimsUseOnlyTheOfficialEndpointAndPrivacyAndLogoutClearTheConnection() = runBlocking {
        withContext(Dispatchers.Main) {
            AccountSession.bindClient(AccountApiClient("http://127.0.0.1:${server.port}", "ak_test", OkHttpClient()))
            AccountSession.applyPreview(AccountUser(7, "self", nickname = "Self", accountId = "202"), "uas-player-token")
            AccountSession.networkEnabled = true
        }
        startController()
        await { commands.any { it == "/txjsid prove ${"c".repeat(64)}" } && variant(target, "room.player.background") != null }
        val claim = requests.single { it.requestUrl?.encodedPath == "/api/v1/identity/claims" }
        assertEquals("ak_test", claim.getHeader("X-App-Key"))
        assertEquals("Bearer uas-player-token", claim.getHeader("Authorization"))
        val body = Json.parseToJsonElement(claim.body.clone().readUtf8()).jsonObject
        assertEquals(clientNonce.get(), body.getValue("client_nonce").jsonPrimitive.content)
        assertEquals("official-test", body.getValue("server_id").jsonPrimitive.content)
        assertEquals("a".repeat(64), body.getValue("challenge_id").jsonPrimitive.content)
        assertTrue(commands.none { it.contains("uas-player-token") || it.contains("ak_test") }, "game packets must never receive UAS credentials")
        val inspections = requests.count { it.requestUrl?.encodedPath.orEmpty().startsWith("/api/v1/identity/challenges/") }
        withContext(Dispatchers.Main) {
            AccountSession.updatePresenceSettings(hideFromStrangers = true, hideFromFriends = null)
            assertNull(PersonalizedAppearanceController.variantFor(target.player, "room.player.background"))
        }
        await { commands.contains("/txjsid clear") && requests.count { it.requestUrl?.encodedPath.orEmpty().startsWith("/api/v1/identity/challenges/") } > inspections }
        withContext(Dispatchers.Main) { delay(150) }
        assertEquals(1, requests.count { it.requestUrl?.encodedPath == "/api/v1/identity/claims" }, "hidden accounts must not obtain another ticket")
        val clears = commands.count { it == "/txjsid clear" }
        withContext(Dispatchers.Main) { AccountSession.logout() }
        await { commands.count { it == "/txjsid clear" } > clears }
        assertFalse(AccountSession.loggedIn)
    }

    @Test
    fun productionRowsRenderPersonalBackgroundFrameAndReadableInfoAtDesktopWidth() = renderedRows(760, false)

    @Test
    fun productionRowsKeepTheSamePlayerAttributionAndFallbackAtNarrowWidth() = renderedRows(390, true)

    private fun renderedRows(width: Int, compact: Boolean) = runDesktopComposeUiTest(width = width, height = 340) {
        resolutionLifetime = 10
        SingletonImageLoader.setUnsafe { context -> ImageLoader.Builder(context).build() }
        startController()
        setContent {
            KoinContext(appKoin) { RWPPTheme(default = true) {
                Column(Modifier.fillMaxSize().background(Color(0xFF101820)).padding(12.dp).testTag("appearance-flow-ui")) {
                    ProductionPlayerRows(compact)
                }
            } }
        }
        onNodeWithText("Target", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Other", useUnmergedTree = true).assertIsDisplayed()
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Supporter", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        // Let this assignment expire. A later resolve must not renew it between the fallback pixel checks.
        resolutionLifetime = 0
        onNodeWithText("Supporter", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText("Supporter", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow, "the personal information must remain readable at $width dp")
        val targetTag = "room-player-2"
        val otherTag = "room-player-3"
        val personalBlue = 0xFF2653AE.toInt()
        val gold = 0xFFFFD700.toInt()
        val normal = 0xFF202020.toInt()
        waitUntil(timeoutMillis = 10_000) { colorCount(onNodeWithTag(targetTag, useUnmergedTree = true).captureToImage().toAwtImage(), personalBlue) > 100 }
        assertTrue(colorCount(onNodeWithTag(targetTag, useUnmergedTree = true).captureToImage().toAwtImage(), gold) > 20, "production border must draw the assigned gold frame")
        waitUntil(timeoutMillis = 10_000) { colorCount(onNodeWithTag(otherTag, useUnmergedTree = true).captureToImage().toAwtImage(), normal) > 100 }
        assertEquals(0, colorCount(onNodeWithTag(otherTag, useUnmergedTree = true).captureToImage().toAwtImage(), personalBlue), "the unassigned player must keep the pack default")
        onNode(hasText("Supporter") and hasAnyAncestor(hasTestTag(otherTag)), useUnmergedTree = true).assertDoesNotExist()
        saveUi("players-${width}-assigned.png")
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Supporter", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        waitUntil(timeoutMillis = 10_000) { colorCount(onNodeWithTag(targetTag, useUnmergedTree = true).captureToImage().toAwtImage(), normal) > 100 }
        assertEquals(0, colorCount(onNodeWithTag(targetTag, useUnmergedTree = true).captureToImage().toAwtImage(), gold))
        saveUi("players-${width}-expired.png")
        runOnIdle { assertTrue(ArtThemeController.apply(null)) }
        onNodeWithText("Supporter", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, colorCount(onNodeWithTag(targetTag, useUnmergedTree = true).captureToImage().toAwtImage(), personalBlue))
        saveUi("players-${width}-disabled.png")
    }

    @Composable
    private fun ProductionPlayerRows(compact: Boolean) {
        RoomPlayerTableRow(target.player, room, game, update = false, onPlayerClick = {}, compact = compact)
        RoomPlayerTableRow(other.player, room, game, update = false, onPlayerClick = {}, compact = compact)
    }

    private fun ComposeUiTest.saveUi(name: String) {
        val output = File("../build/artifacts/personalized-player").apply { mkdirs() }
        ImageIO.write(onNodeWithTag("appearance-flow-ui").captureToImage().toAwtImage(), "png", File(output, name))
        File(output, "PLAYER-UI-README.txt").writeText("Production Compose player rows with the real appearance controller and loopback HTTP/socket fixtures. These are logical desktop viewports, not Android device or live multiplayer validation.\n")
    }

    private fun colorCount(image: BufferedImage, color: Int): Int =
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width).count { it == color }

    private fun startController(ready: Boolean = true) {
        if (ready) assertTrue(PersonalizedAppearanceController.receiveChatPacket(socket, chatPacket(IdentityChatCodec.READY_NOTICE)))
        val handler = CoroutineExceptionHandler { _, thrown -> failure.set(thrown) }
        controller = CoroutineScope(SupervisorJob() + Dispatchers.Main + handler).launch { PersonalizedAppearanceController.run(game) }
    }

    private suspend fun variant(member: Member, slot: String): PersonalizedVariantSpec? =
        withContext(Dispatchers.Main) { PersonalizedAppearanceController.variantFor(member.player, slot) }

    private suspend fun await(predicate: suspend () -> Boolean) {
        try {
            withTimeout(15_000) {
                while (!predicate()) {
                    failure.get()?.let { throw AssertionError("Controller terminated unexpectedly", it) }
                    delay(25)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            val paths = requests.map { it.requestUrl?.encodedPath }
            val actions = commands.map { it.split(' ').take(2).joinToString(" ") }
            throw AssertionError("Appearance flow timed out: paths=$paths, gameActions=$actions", timeout)
        }
    }

    private fun dispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val path = request.requestUrl!!.encodedPath
            val now = System.currentTimeMillis() / 1000
            return when {
                path == "/api/v1/identity/servers/lookup" -> {
                    val scope = gameScope
                    if (scope == null) json("""{"server_id":"official-test"}""")
                    else {
                        val catalog = boundCatalogOverride ?: ArtThemeController.installedThemes.first { it.id == "first" }.personalizedCatalog!!.catalogHash
                        val binding = Json.encodeToString(OnlineGameArtBinding.serializer(), OnlineGameArtBinding(packId, "1.0.0", catalog))
                        json("""{"server_id":"official-test","game_app_id":"$scope","art_binding":$binding}""")
                    }
                }
                path.startsWith("/api/v1/identity/challenges/") -> json(Json.encodeToString(VerifiedIdentityChallenge.serializer(),
                    VerifiedIdentityChallenge(if (badChallenge) "unregistered-server" else "official-test", "room-test", "observer-connection", clientNonce.get(), now + 100)))
                path == "/api/v1/identity/rooms/room-test/members" -> {
                    rosterRequests.incrementAndGet()
                    if (rosterFails) MockResponse().setResponseCode(503).setBody("unavailable")
                    else {
                        val response = json(Json.encodeToString(VerifiedAppearanceRoster.serializer(), VerifiedAppearanceRoster(
                            "official-test", "room-test", "observer-connection", revision.get(), now + 12,
                            listOf(VerifiedAppearanceMember("target-connection", target.slot, target.name, "101", now + 100),
                                VerifiedAppearanceMember("other-connection", other.slot, other.name, "202", now + 100)),
                        )))
                        val gate = membersGate
                        gate?.entered?.countDown()
                        if (gate != null) assertTrue(gate.release.await(10, TimeUnit.SECONDS), "Members gate was not released")
                        gate?.responded?.countDown()
                        response
                    }
                }
                path == "/api/v1/identity/claims" -> json("""{"ticket":"${"c".repeat(64)}","expires_at":${now + 50}}""")
                path == "/api/v1/users/me/presence-settings" -> {
                    if (request.method == "POST") {
                        hideFromStrangers = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["hide_from_strangers"]
                            ?.jsonPrimitive?.content == "true"
                    }
                    MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"settings":{"hide_from_strangers":$hideFromStrangers,"hide_from_friends":false}}""")
                }
                path == "/api/v1/users/logout" -> MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true}""")
                path.endsWith("/resolve") -> {
                    resolveRequests.incrementAndGet()
                    val body = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                    assertEquals(setOf("101", "202"), body.getValue("account_ids").jsonArray.map { it.jsonPrimitive.content }.toSet())
                    assertEquals(ArtThemeController.installedThemes.first { it.id == "first" }.personalizedCatalog?.catalogHash,
                        body.getValue("catalog_hash").jsonPrimitive.content)
                    val gate = resolveGate
                    gate?.entered?.countDown()
                    if (gate != null) assertTrue(gate.release.await(10, TimeUnit.SECONDS), "Resolve gate was not released")
                    val response = json(Json.encodeToString(AppearanceResolution.serializer(), AppearanceResolution(4, now + resolutionLifetime, listOf(
                        AppearanceAssignment("101", "room.player.background", "supporter"),
                        AppearanceAssignment("101", "room.player.frame", "gold"),
                        AppearanceAssignment("101", "room.player.info", "supporter"),
                    ))))
                    gate?.responded?.countDown()
                    response
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun json(data: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"code":"ok","data":$data}""")

    private fun chatPacket(text: String, source: Int = 0): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeUTF(text)
            output.writeByte(3)
            output.writeBoolean(true)
            output.writeUTF("RWJS-ID")
            output.writeInt(source)
            output.writeInt(-1)
        }
    }.toByteArray()

    private fun writeTheme(id: String, platformId: String, color: Int) {
        val dir = File(ArtThemeController.themeRootOverride, id).apply { mkdirs() }
        for ((name, argb) in listOf("default.png" to 0xFF202020.toInt(), "target.png" to color)) {
            val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until 8) for (y in 0 until 8) image.setRGB(x, y, argb)
            ImageIO.write(image, "png", File(dir, name))
        }
        File(dir, "theme.toml").writeText("""
            [theme]
            id = "$id"
            name = "Appearance $id"
            version = "1.0.0"
            [room.player]
            backgroundImage = "default.png"
            [personalization]
            schemaVersion = 1
            platformPackId = "$platformId"
            [personalization.slots."room.player.background".variants.supporter]
            image = "target.png"
            [personalization.slots."room.player.frame".variants.gold]
            borderColor = "#FFD700"
            borderWidth = 2
            [[personalization.slots."room.player.info".variants.supporter.content.rows]]
            [[personalization.slots."room.player.info".variants.supporter.content.rows.cells]]
            text = "Supporter"
        """.trimIndent())
    }

    private class Member(@Volatile var name: String, @Volatile var slot: Int) {
        private val data = PlayerData()
        val player = stub(Player::class.java) { method, _ -> when (method) {
            "getName" -> name
            "getSpawnPoint" -> slot
            "getTeam" -> slot
            "getConnectHexId" -> "untrusted-legacy-id"
            "getStartingUnit", "getColor" -> -1
            "getPingNumber" -> 42
            "getPing" -> "42"
            "getData" -> data
            "isAI", "isSpectator", "isRoomHost" -> false
            else -> error("Unexpected player access: $method")
        } }
    }

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val responded = CountDownLatch(1)
    }

    companion object {
        private fun <T> stub(type: Class<T>, value: (String, Array<out Any?>?) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args -> when (method.name) {
                "equals" -> instance === args!![0]
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "Appearance${type.simpleName}"
                else -> value(method.name, args)
            } },
        )
    }
}
