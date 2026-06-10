/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.PublishedRoomInfo
import io.github.rwpp.config.Settings
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.event.events.RefreshUIEvent
import io.github.rwpp.event.events.ReturnMainMenuEvent
import io.github.rwpp.event.onDispose
import io.github.rwpp.external.Extension
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.game.*
import io.github.rwpp.game.base.Difficulty
import io.github.rwpp.game.map.FogMode
import io.github.rwpp.game.map.MapType
import io.github.rwpp.game.team.TeamMode
import io.github.rwpp.game.units.UnitType
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.Net
import io.github.rwpp.net.roomListPublishAddress
import io.github.rwpp.config.DEFAULT_ROOM_LIST_API_URLS
import com.eclipsesource.json.Json
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.platform.KickPlayerContextMenuAreaMultiplatform
import io.github.rwpp.scripts.Render
import io.github.rwpp.ui.UI.chatMessages
import io.github.rwpp.ui.color.getTeamColor
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.LazyColumnScrollbar
import io.github.rwpp.widget.v2.LineSpinFadeLoaderIndicator
import io.github.rwpp.widget.v2.ListIndicatorSettings
import io.github.rwpp.widget.v2.ScrollbarSelectionActionable
import io.github.rwpp.widget.v2.bounceClick
import io.github.rwpp.widget.v2.lazyListCanScroll
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** 已发布房间剩余时间与列表服务器同步的间隔（本地每秒递减倒计时） */
private const val PUBLISHED_ROOM_EXPIRY_SYNC_INTERVAL_MS = 30_000L

private const val PUBLISH_LOADING_MIN_MS = 400L

private const val LIST_DETECTOR_PLAYER_KEYWORD = "列表探测器"

private enum class PublishStep {
    FetchingTypes,
    Publishing,
}

private sealed class PublishToListUiState {
    data object Hidden : PublishToListUiState()

    data class Loading(val step: PublishStep) : PublishToListUiState()

    data class SelectRoomType(val types: List<String>) : PublishToListUiState()

    data class Success(val roomId: String, val serverId: String) : PublishToListUiState()

    data class Failure(val message: String, val step: PublishStep) : PublishToListUiState()
}

@Composable
fun MultiplayerRoomView(isSandboxGame: Boolean = false, onExit: () -> Unit) {
    BackHandler(true, onExit)
    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("multiplayerRoom").broadcastIn()
        }
    }

    val externalHandler = koinInject<ExternalHandler>()
    val game = koinInject<Game>()
    val net = koinInject<Net>()
    val room = game.gameRoom

    var update by remember { mutableStateOf(false) }
    var lastSelectedIndex by remember { mutableIntStateOf(0) }
    var selectedMap by remember(update) { mutableStateOf(room.selectedMap) }
    val displayMapName = remember(update) { room.displayMapName }

    var optionVisible by remember { mutableStateOf(false) }
    var banUnitVisible by remember { mutableStateOf(false) }
    var publishState by remember { mutableStateOf<PublishToListUiState>(PublishToListUiState.Hidden) }
    var pendingPublishRoomType by remember { mutableStateOf<String?>(null) }
    var roomIdForPublish by remember { mutableStateOf<String?>(null) }
    val isPublishing = publishState is PublishToListUiState.Loading
    //var downloadModViewVisible by remember { mutableStateOf(false) }
    //var loadModViewVisible by remember { mutableStateOf(false) }
    var selectedBanUnits by remember { mutableStateOf(listOf<UnitType>()) }

    // 发布到列表后的状态
    val configIO = koinInject<ConfigIO>()
    val publishedRoomInfo = koinInject<PublishedRoomInfo>()
    var hasPublishedInfo by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableIntStateOf(0) }
    var totalSeconds by remember { mutableIntStateOf(0) }
    var isRefreshingExpiry by remember { mutableStateOf(false) }

    fun updatePublishedExpiry(seconds: Int) {
        val safeSeconds = seconds.coerceAtLeast(0)
        remainingSeconds = safeSeconds
        if (safeSeconds > 0) {
            hasPublishedInfo = true
            if (totalSeconds == 0 || safeSeconds > totalSeconds) totalSeconds = safeSeconds
        } else {
            hasPublishedInfo = false
            totalSeconds = 0
        }
    }

    // 从持久化恢复已发布信息（若房间匹配）
    LaunchedEffect(roomIdForPublish) {
        val info = configIO.readConfig(PublishedRoomInfo::class)
        val currentRoomId = roomIdForPublish
        if (info == null || currentRoomId == null || info.roomId != currentRoomId || !info.isPublished) {
            hasPublishedInfo = false
            remainingSeconds = 0
            totalSeconds = 0
            return@LaunchedEffect
        }

        if (info.roomId == currentRoomId && info.isPublished) {
            hasPublishedInfo = true
            remainingSeconds = 0
            totalSeconds = 0
            // 立即查询一次
            with(net) {
                getServerExpiry(info.baseUrl, info.serverId, info.secretKey)
            }.onSuccess { sec ->
                updatePublishedExpiry(sec)
            }
        }
    }

    // 本地每秒递减倒计时，避免高频请求列表服务器
    LaunchedEffect(hasPublishedInfo) {
        if (!hasPublishedInfo) return@LaunchedEffect
        while (isActive) {
            delay(1_000L)
            if (remainingSeconds > 0) {
                remainingSeconds--
                if (remainingSeconds <= 0) {
                    hasPublishedInfo = false
                    totalSeconds = 0
                }
            }
        }
    }

    // 定期与服务器同步剩余时间（本地倒计时之间校正）
    LaunchedEffect(hasPublishedInfo) {
        if (!hasPublishedInfo) return@LaunchedEffect
        val info = configIO.readConfig(PublishedRoomInfo::class) ?: return@LaunchedEffect
        if (!info.isPublished) return@LaunchedEffect
        while (isActive) {
            with(net) {
                getServerExpiry(info.baseUrl, info.serverId, info.secretKey)
            }.onSuccess { sec ->
                updatePublishedExpiry(sec)
            }.onFailure {
                // 查询失败可能是房间已过期，标记为未发布
                if (remainingSeconds <= 0) hasPublishedInfo = false
            }
            delay(PUBLISHED_ROOM_EXPIRY_SYNC_INTERVAL_MS)
        }
    }

    var showMapSelectView by remember { mutableStateOf(false) }
    val isHost = remember(update) { room.isHost || room.isHostServer }

    val updateAction = { update = !update }

    val scope = rememberCoroutineScope()

    val renewPublishedRoom = {
        if (!isRefreshingExpiry) {
            isRefreshingExpiry = true
            scope.launch {
                try {
                    val info = configIO.readConfig(PublishedRoomInfo::class)
                    if (info != null && info.isPublished) {
                        with(net) {
                            refreshServer(info.baseUrl, info.serverId, info.secretKey)
                        }.onSuccess {
                            remainingSeconds = 0
                            totalSeconds = 0
                            with(net) {
                                getServerExpiry(info.baseUrl, info.serverId, info.secretKey)
                            }.onSuccess { sec ->
                                updatePublishedExpiry(sec)
                            }
                        }
                    }
                } finally {
                    isRefreshingExpiry = false
                }
            }
        }
    }

    val extensions = remember {
        externalHandler.getAllExtensions().onFailure {
            UI.showWarning(it.message ?: "Unexpected error")
        }.getOrDefault(listOf()).filter { !it.config.hasResource }
    }

//
//    GlobalEventChannel.filter(CallReloadModEvent::class).onDispose {
//        subscribeAlways {
//            loadModViewVisible = true
//            downloadModViewVisible = false
//        }
//    }
//
//    GlobalEventChannel.filter(CallStartDownloadModEvent::class).onDispose {
//        subscribeAlways { downloadModViewVisible = true }
//    }

    GlobalEventChannel.filter(RefreshUIEvent::class).onDispose {
        subscribeAlways(Dispatchers.Main.immediate) { updateAction() }
    }

    GlobalEventChannel.filter(ReturnMainMenuEvent::class).onDispose {
        subscribeAlways(Dispatchers.Main.immediate) { onExit() }
    }


//    LoadingView(loadModViewVisible, { loadModViewVisible = false }) {
//        message("reloading mods...")
//        modManager.modReload()
//        net.sendPacketToServer(ModPacket.ModReloadFinishPacket())
//        true
//    }
//
//    LoadingView(downloadModViewVisible, { downloadModViewVisible = false }) {
//        message("downloading mods...")
//        delay(Long.MAX_VALUE)
//        false
//    }

    val players = remember(update) { room.getPlayers().sortedBy { it.team } }
    var selectedPlayer by remember { mutableStateOf(players.firstOrNull() ?: ConnectingPlayer) }
    var playerOverrideVisible by remember { mutableStateOf(false) }

    LaunchedEffect(selectedPlayer) {
        UI.roomSelectedPlayer = selectedPlayer
    }

    PlayerOverrideDialog(
        playerOverrideVisible,
        { playerOverrideVisible = false },
        updateAction,
        room,
        extensions,
        selectedPlayer
    )

    MapViewDialog(
        showMapSelectView,
        { showMapSelectView = false },
        lastSelectedIndex,
        if (room.isHostServer) MapType.SkirmishMap else selectedMap.mapType
    ) { index, map ->
        selectedMap = map
        room.selectedMap = map
        lastSelectedIndex = index
    }

    MultiplayerOption(
        optionVisible,
        { optionVisible = false },
        updateAction,
        extensions,
        { banUnitVisible = true; optionVisible = false },
    )

    BanUnitViewDialog(banUnitVisible, { banUnitVisible = false }, selectedBanUnits) {
        selectedBanUnits = it
        game.onBanUnits(it)
    }

    suspend fun ensureMinPublishLoading(startMs: Long) {
        val remaining = PUBLISH_LOADING_MIN_MS - (System.currentTimeMillis() - startMs)
        if (remaining > 0) delay(remaining)
    }

    fun kickListDetectorPlayers() {
        if (!room.isHost && !room.isHostServer) return
        val detectors = room.getPlayers().filter { it.name.contains(LIST_DETECTOR_PLAYER_KEYWORD) }
        if (detectors.isEmpty()) return
        detectors.forEach { player ->
            runCatching { room.kickPlayer(player) }
        }
        updateAction()
    }

    suspend fun submitPublish(roomId: String, roomType: String) {
        pendingPublishRoomType = roomType
        publishState = PublishToListUiState.Loading(PublishStep.Publishing)
        val startMs = System.currentTimeMillis()
        val result = with(net) {
            publishServerToPublicList(
                DEFAULT_ROOM_LIST_API_URLS,
                "公开房-$roomId",
                roomListPublishAddress(roomId),
                roomType
            )
        }
        ensureMinPublishLoading(startMs)
        result.fold(
            onSuccess = { body ->
                val json = Json.parse(body)
                val code = json.asObject().getInt("code", -1)
                val msg = json.asObject().getString("message", "")
                if (code == 0) {
                    val data = json.asObject().get("data")?.asObject()
                    val serverId = data?.getString("server_id", "") ?: ""
                    val secretKey = data?.getString("secret_key", "") ?: ""
                    publishedRoomInfo.roomId = roomId
                    publishedRoomInfo.serverId = serverId
                    publishedRoomInfo.secretKey = secretKey
                    publishedRoomInfo.roomType = roomType
                    publishedRoomInfo.baseUrl = DEFAULT_ROOM_LIST_API_URLS
                    configIO.saveConfig(publishedRoomInfo)
                    hasPublishedInfo = true
                    remainingSeconds = 0
                    totalSeconds = 0
                    kickListDetectorPlayers()
                    publishState = PublishToListUiState.Success(roomId, serverId)
                } else {
                    val detail = if (msg.isNotBlank()) "$msg (code: $code)" else "code: $code"
                    kickListDetectorPlayers()
                    publishState = PublishToListUiState.Failure(
                        readI18n("multiplayer.room.publishFailedDetail", I18nType.RWPP, detail),
                        PublishStep.Publishing,
                    )
                }
            },
            onFailure = { e ->
                kickListDetectorPlayers()
                publishState = PublishToListUiState.Failure(
                    readI18n(
                        "multiplayer.room.publishFailedDetail",
                        I18nType.RWPP,
                        e.message ?: readI18n("multiplayer.room.publishFailed"),
                    ),
                    PublishStep.Publishing,
                )
            }
        )
    }

    suspend fun runPublishFlow(roomId: String) {
        publishState = PublishToListUiState.Loading(PublishStep.FetchingTypes)
        val startMs = System.currentTimeMillis()
        val types = with(net) {
            fetchRoomTypes(DEFAULT_ROOM_LIST_API_URLS)
        }
        ensureMinPublishLoading(startMs)
        when {
            types.isEmpty() -> {
                kickListDetectorPlayers()
                publishState = PublishToListUiState.Failure(
                    readI18n("multiplayer.room.publishFetchTypesFailed"),
                    PublishStep.FetchingTypes,
                )
            }
            types.size == 1 -> submitPublish(roomId, types.first())
            else -> publishState = PublishToListUiState.SelectRoomType(types)
        }
    }

    PublishToListDialog(
        state = publishState,
        onDismiss = { publishState = PublishToListUiState.Hidden },
        onSelectRoomType = { roomType ->
            val roomId = roomIdForPublish ?: return@PublishToListDialog
            scope.launch { submitPublish(roomId, roomType) }
        },
        onRetry = { step ->
            val roomId = roomIdForPublish ?: return@PublishToListDialog
            scope.launch {
                when (step) {
                    PublishStep.FetchingTypes -> runPublishFlow(roomId)
                    PublishStep.Publishing -> {
                        val roomType = pendingPublishRoomType
                        if (roomType != null) submitPublish(roomId, roomType)
                        else runPublishFlow(roomId)
                    }
                }
            }
        },
    )

    val chatFocusRequester = remember { FocusRequester() }
    var roomDetails by remember { mutableStateOf("Getting details...") }

    LaunchedEffect(update) {
        val raw = room.roomDetails()
        roomDetails = raw.split("\n")
            .filter { !it.startsWith("Map:") && it.isNotBlank() }
            .joinToString("\n")
        roomIdForPublish = Regex("""[QR]\d+""").find(raw)?.value
    }

    @Composable
    fun ContentView() {
        val globalFocusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        val interactionSource = remember { MutableInteractionSource() }
        val isDesktop = remember { appKoin.get<AppContext>().isDesktop() }
        val isCompact = LocalWindowManager.current != WindowManager.Large

        val onPlayerClick: (Player) -> Unit = { player ->
            selectedPlayer = player
            playerOverrideVisible = true
        }

        val startGame: () -> Unit = {
            val unpreparedPlayers = game.gameRoom.getPlayers().filter { !it.data.ready }
            if (unpreparedPlayers.isNotEmpty()) {
                game.gameRoom.sendSystemMessage(
                    "Cannot start game. Because players: ${unpreparedPlayers.joinToString(", ") { it.name }} aren't ready.",
                )
            } else if (room.isHostServer) {
                room.sendQuickGameCommand("-start")
            } else {
                room.startGame()
            }
        }

        val roomSurfaceModifier = Modifier
            .fillMaxSize()
            .padding(
                start = if (isCompact) 4.dp else 5.dp,
                end = if (isCompact) 4.dp else 5.dp,
                top = if (isCompact) 2.dp else 5.dp,
                bottom = if (isCompact) 4.dp else 5.dp,
            )
            .focusRequester(globalFocusRequester)
            .clickable(interactionSource, null) {
                globalFocusRequester.requestFocus()
                keyboardController?.hide()
            }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && !it.isCtrlPressed && !it.isShiftPressed) {
                    when (it.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            chatFocusRequester.requestFocus()
                            true
                        }

                        Key.M -> {
                            showMapSelectView = true
                            true
                        }

                        Key.O -> {
                            optionVisible = true
                            true
                        }

                        Key.A -> {
                            room.addAI()
                            true
                        }

                        Key.C -> {
                            if (players.isNotEmpty()) {
                                selectedPlayer = room.localPlayer
                                playerOverrideVisible = true
                            }
                            true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            }

        @Composable
        fun CompactRoomContent() {
            val playerListState = rememberLazyListState()
            var chatMessage by remember { mutableStateOf("") }
            var roomDetailsDialogVisible by remember { mutableStateOf(false) }
            var isLocked by remember(update) { mutableStateOf(room.lockedRoom) }
            val mapType = remember(update) { room.mapType }
            val compactRowShape = RoundedCornerShape(6.dp)
            val scrollState = rememberScrollState()
            val enableAnimations = koinInject<Settings>().enableAnimations

            if (roomDetailsDialogVisible) {
                RoomDetailsDialog(
                    details = roomDetails,
                    onDismiss = { roomDetailsDialogVisible = false },
                )
            }

            Box(modifier = roomSurfaceModifier) {
                ExitButton(onExit)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 2.dp, top = 2.dp, end = 36.dp, bottom = 2.dp),
                ) {
                    if (hasPublishedInfo) {
                        PublishedRoomExpiryBar(
                            remainingSeconds = remainingSeconds,
                            totalSeconds = totalSeconds,
                            isRefreshing = isRefreshingExpiry,
                            onRenew = renewPublishedRoom,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }

                    CompactRoomDetailsBar(
                        roomDetails = roomDetails,
                        onShowDetails = { roomDetailsDialogVisible = true },
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val stackVertically = maxWidth < 480.dp
                            if (stackVertically) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    CompactPlayerPanel(
                                        modifier = Modifier.fillMaxWidth(),
                                        players = players,
                                        room = room,
                                        game = game,
                                        update = update,
                                        playerListState = playerListState,
                                        rowShape = compactRowShape,
                                        onPlayerClick = onPlayerClick,
                                        enableAnimations = enableAnimations,
                                        expandVertically = false,
                                    )
                                    CompactMapSettingsPanel(
                                        modifier = Modifier.fillMaxWidth(),
                                        expandVertically = false,
                                        mapType = mapType,
                                        displayMapName = displayMapName,
                                        selectedMap = selectedMap,
                                        isHost = isHost,
                                        isSandboxGame = isSandboxGame,
                                        isLocked = isLocked,
                                        onLockToggle = {
                                            isLocked = !isLocked
                                            room.lockedRoom = isLocked
                                        },
                                        isDesktop = isDesktop,
                                        showPublishButton = roomIdForPublish != null && !isPublishing && !hasPublishedInfo,
                                        onOption = { optionVisible = true },
                                        onStart = startGame,
                                        onAddAI = { room.addAI() },
                                        onAddAIMany = { room.addAI(10) },
                                        onPublish = {
                                            val roomId = roomIdForPublish ?: return@CompactMapSettingsPanel
                                            scope.launch { runPublishFlow(roomId) }
                                        },
                                        onMapClick = { showMapSelectView = true },
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(CompactTopPanelHeight),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    CompactPlayerPanel(
                                        modifier = Modifier
                                            .weight(0.55f)
                                            .fillMaxHeight(),
                                        players = players,
                                        room = room,
                                        game = game,
                                        update = update,
                                        playerListState = playerListState,
                                        rowShape = compactRowShape,
                                        onPlayerClick = onPlayerClick,
                                        enableAnimations = enableAnimations,
                                        expandVertically = true,
                                    )
                                    CompactMapSettingsPanel(
                                        modifier = Modifier
                                            .weight(0.45f)
                                            .fillMaxHeight(),
                                        expandVertically = true,
                                        mapType = mapType,
                                        displayMapName = displayMapName,
                                        selectedMap = selectedMap,
                                        isHost = isHost,
                                        isSandboxGame = isSandboxGame,
                                        isLocked = isLocked,
                                        onLockToggle = {
                                            isLocked = !isLocked
                                            room.lockedRoom = isLocked
                                        },
                                        isDesktop = isDesktop,
                                        showPublishButton = roomIdForPublish != null && !isPublishing && !hasPublishedInfo,
                                        onOption = { optionVisible = true },
                                        onStart = startGame,
                                        onAddAI = { room.addAI() },
                                        onAddAIMany = { room.addAI(10) },
                                        onPublish = {
                                            val roomId = roomIdForPublish ?: return@CompactMapSettingsPanel
                                            scope.launch { runPublishFlow(roomId) }
                                        },
                                        onMapClick = { showMapSelectView = true },
                                    )
                                }
                            }
                        }

                        if (!isSandboxGame) {
                            CompactRoomPanel(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = CompactChatPanelMinHeight),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = CompactChatMessageAreaMinHeight),
                                ) {
                                    RoomChatMessageView(modifier = Modifier.fillMaxSize())
                                }
                                RoomChatMessageTextField(
                                    chatMessage = chatMessage,
                                    focusRequester = chatFocusRequester,
                                    onChatMessageChange = { chatMessage = it },
                                    onSend = room::sendChatMessageOrCommand,
                                    compact = true,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isCompact) {
            CompactRoomContent()
            return
        }

        BorderCard(modifier = roomSurfaceModifier) {
            Box {
                ExitButton(onExit)
                Column {
                    Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            val mapType = remember(update) { room.mapType }
                            BorderCard(
                                modifier = Modifier
                                    .weight(.48f)
                                    .padding(10.dp),
                                backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(
                                    .7f
                                )
                            ) {
                                DesktopMapSettingsCard(
                                    mapType = mapType,
                                    displayMapName = displayMapName,
                                    selectedMap = selectedMap,
                                    roomDetails = roomDetails,
                                    isHost = isHost,
                                    isDesktop = isDesktop,
                                    hasPublishedInfo = hasPublishedInfo,
                                    remainingSeconds = remainingSeconds,
                                    totalSeconds = totalSeconds,
                                    isRefreshingExpiry = isRefreshingExpiry,
                                    onRenew = renewPublishedRoom,
                                    showPublishButton = roomIdForPublish != null && !isPublishing && !hasPublishedInfo,
                                    onMapClick = { showMapSelectView = true },
                                    onOption = { optionVisible = true },
                                    onStart = startGame,
                                    onPublish = {
                                        val roomId = roomIdForPublish ?: return@DesktopMapSettingsCard
                                        scope.launch { runPublishFlow(roomId) }
                                    },
                                )
                            }

                            val state = rememberLazyListState()

                            Column(
                                modifier = Modifier.weight(.52f).padding(10.dp).then(
                                    /*if(LocalWindowManager.current != WindowManager.Large) Modifier.verticalScroll(rememberScrollState())
                                else*/ Modifier
                                ),
                            ) {
                                BorderCard(
                                    modifier = Modifier.fillMaxWidth()
                                        .defaultMinSize(minHeight = 200.dp).padding(5.dp),
                                    backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(
                                        .7f
                                    )
                                ) {
                                    RoomPlayerTableHeader()
                                    LazyColumnScrollbar(
                                        listState = state,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxWidth(),
                                            state = state,
                                        ) {
                                            items(
                                                count = players.size,
                                                key = { players[it].connectHexId },
                                            ) { index ->
                                                RoomPlayerTableRow(
                                                    player = players[index],
                                                    room = room,
                                                    game = game,
                                                    update = update,
                                                    onPlayerClick = onPlayerClick,
                                                    modifier = if (koinInject<Settings>().enableAnimations) {
                                                        Modifier.animateItem()
                                                    } else {
                                                        Modifier
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    BorderCard(
                        modifier = Modifier
                            .weight(1f)
                            .padding(10.dp),
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Max)
                                    .padding(5.dp),
                            ) {
                                RWTextButton(
                                    readI18n("multiplayer.room.changeSite") + if (isDesktop) "(C)" else "",
                                    modifier = Modifier.padding(
                                        horizontal = 5.dp,
                                        vertical = 30.dp,
                                    ),
                                ) {
                                    if (players.isNotEmpty()) {
                                        selectedPlayer = room.localPlayer
                                        playerOverrideVisible = true
                                    }
                                }
                                if (isHost) {
                                    RWTextButton(
                                        readI18n("multiplayer.room.addAI") + if (isDesktop) "(A)" else "",
                                        modifier = Modifier.padding(
                                            horizontal = 5.dp,
                                            vertical = 30.dp,
                                        ),
                                        onLongClick = { room.addAI(10) },
                                    ) { room.addAI() }
                                }

                                var isLocked by remember(update) { mutableStateOf(room.lockedRoom) }
                                if (!isSandboxGame) {
                                    IconButton(
                                        onClick = {
                                            isLocked = !isLocked
                                            room.lockedRoom = isLocked
                                        },
                                        enabled = isHost,
                                        modifier = Modifier.padding(
                                            horizontal = 5.dp,
                                            vertical = 30.dp,
                                        ),
                                    ) {
                                        Icon(
                                            Icons.Default.Lock,
                                            null,
                                            tint = if (isLocked) {
                                                Color(237, 112, 20)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceTint
                                            },
                                        )
                                    }
                                }

                                var chatMessage by remember { mutableStateOf("") }
                                if (!isSandboxGame) {
                                    RoomChatMessageTextField(
                                        chatMessage = chatMessage,
                                        focusRequester = chatFocusRequester,
                                        onChatMessageChange = { chatMessage = it },
                                        onSend = room::sendChatMessageOrCommand,
                                    )
                                }
                            }

                            if (!isSandboxGame) {
                                BorderCard(
                                    modifier = Modifier.padding(5.dp),
                                    backgroundColor = MaterialTheme.colorScheme.surface.copy(.7f),
                                ) {
                                    RoomChatMessageView(modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    ContentView()
}

@Composable
private fun MultiplayerOption(
    label: String,
    value: Boolean,
    enabled: Boolean = true,
    onValueChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        RWCheckbox(
            value,
            onCheckedChange = { onValueChange(!value) },
            modifier = Modifier.padding(5.dp),
            enabled
        )
        Text(
            label,
            modifier = Modifier.padding(5.dp),
            style = MaterialTheme.typography.headlineMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.inversePrimary
        )
    }
}

@Composable
private fun PlayerOverrideDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    update: () -> Unit,
    room: GameRoom,
    extensions: List<Extension>,
    player: Player,
) {
    val game = koinInject<Game>()
    val items = remember {
        buildList {
            add(-1 to "Default")
            addAll(game.getStartingUnitOptions())
        }
    }

    AnimatedAlertDialog(
        visible, onDismissRequest = { onDismissRequest(); update() }
    ) { dismiss ->

        var playerSpawnPoint by remember(player) { mutableStateOf<Int?>(player.spawnPoint + 1) }
        var playerTeam by remember(player) { mutableStateOf<Int?>(-1) }
        var playerColor by remember(player) { mutableStateOf(player.color) }
        var playerStartingUnits by remember(player) { mutableStateOf(items.indexOfFirst { it.first == player.startingUnit }) }
        var aiDifficulty by remember(player) { mutableStateOf(player.difficulty ?: room.aiDifficulty) }

        BorderCard(
            modifier = Modifier
                .fillMaxSize(LargeProportion()),
        ) {
            Text(
                readI18n("multiplayer.room.playerConfig"),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(10.dp),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            LargeDividingLine { 0.dp }
            Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                var expanded0 by remember { mutableStateOf(false) }
                RWSingleOutlinedTextField(
                    readI18n("common.spawnPoint"),
                    if (playerSpawnPoint == -3) "Spectator" else playerSpawnPoint?.toString() ?: "",
                    lengthLimitCount = 3,
                    modifier = Modifier.padding(10.dp),
                    typeInNumberOnly = true,
                    typeInOnlyInteger = true,
                    trailingIcon = {
                        val icon =
                            if (expanded0) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
                        Icon(
                            icon,
                            "",
                            modifier = Modifier.clickable(!expanded0) { expanded0 = !expanded0 })
                    },
                    appendedContent = {
                        BasicDropdownMenu(
                            expanded0,
                            buildList<Any> { addAll(1..10); add("Spectator") },
                            onItemSelected = { i, v ->
                                playerSpawnPoint = if (v != "Spectator") i + 1 else -3
                            }
                        ) {
                            expanded0 = false
                        }
                    }
                ) {
                    val n = it.toIntOrNull()
                    if (n == null || n <= room.maxPlayerCount) playerSpawnPoint = n
                }

                Text(
                    readI18n("multiplayer.room.spawnPointTip"),
                    modifier = Modifier.padding(5.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )

                var expanded by remember { mutableStateOf(false) }
                RWSingleOutlinedTextField(
                    readI18n("common.team"),
                    if (playerTeam == -1) "auto" else playerTeam?.toString() ?: "",
                    lengthLimitCount = 3,
                    modifier = Modifier.padding(10.dp),
                    typeInNumberOnly = true,
                    typeInOnlyInteger = true,
                    trailingIcon = {
                        val icon =
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
                        Icon(
                            icon,
                            "",
                            modifier = Modifier.clickable(!expanded) { expanded = !expanded })
                    },
                    appendedContent = {
                        BasicDropdownMenu(
                            expanded,
                            buildList<Any> { add("auto"); addAll(1..10) },
                            onItemSelected = { _, v ->
                                playerTeam = if (v == "auto") -1 else (v as Int)
                            }
                        ) {
                            expanded = false
                        }
                    }
                ) {
                    playerTeam = it.toIntOrNull()?.coerceAtMost(100)?.coerceAtLeast(1)
                }



                Text(
                    readI18n("multiplayer.room.teamTip"),
                    modifier = Modifier.padding(5.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (room.isHost) {
                    LargeDropdownMenu(
                        modifier = Modifier.padding(20.dp),
                        label = "Color",
                        items = buildList {
                            add("Default")
                            add("Green")
                            add("Red")
                            add("Blue")
                            add("Yellow")
                            add("Cyan")
                            add("White")
                            add("Black")
                            add("Pink")
                            add("Orange")
                            add("Purple")
                        },
                        selectedIndex = playerColor + 1,
                        onItemSelected = { i, _ -> playerColor = i - 1 },
                        selectedItemColor = { _, i -> if (i > 0) Player.getTeamColor(i - 1) else MaterialTheme.colorScheme.onSurface }
                    )

                    LargeDropdownMenu(
                        modifier = Modifier.padding(20.dp),
                        label = "Starting Units",
                        items = items,
                        selectedIndex = playerStartingUnits,
                        onItemSelected = { i, _ -> playerStartingUnits = i },
                        selectedItemToString = { (_, s) -> s }
                    )
                }


                if (player.isAI) {
                    LargeDropdownMenu(
                        modifier = Modifier.padding(20.dp),
                        label = "Difficulty",
                        items = Difficulty.entries,
                        selectedIndex = (aiDifficulty + 2).coerceAtMost(Difficulty.entries.size - 1),
                        onItemSelected = { _, v -> aiDifficulty = v.ordinal - 2 }
                    )
                }
            }


            LargeDividingLine { 0.dp }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                for (extension in extensions) {
                    if (extension.isEnabled && extension.extraPlayerOptions.isNotEmpty()) {
                        Column {
                            Text(
                                extension.config.displayName,
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 5.dp)
                            )

                            HorizontalDivider(
                                thickness = 3.dp,
                                modifier = Modifier.padding(top = 2.dp, bottom = 5.dp),
                                color = MaterialTheme.colorScheme.primary
                            )

                            for (widget in extension.extraPlayerOptions) {
                                widget.Render()
                            }
                        }
                    }
                }

                if (player != room.localPlayer && (room.isHost || room.isHostServer))
                    RWTextButton(readI18n("multiplayer.room.kick"), Modifier.padding(5.dp)) {
                        room.kickPlayer(player)
                        dismiss()
                    }

                RWTextButton(readI18n("multiplayer.room.apply"), Modifier.padding(5.dp)) {
                    player.applyConfigChange(
                        if (playerSpawnPoint == -3) -3 else ((playerSpawnPoint ?: 1) - 1).coerceAtLeast(0),
                        if (playerTeam == -1) 0 else playerTeam ?: 1,
                        if (playerColor > -1) playerColor else null,
                        if (playerStartingUnits > 0) items[playerStartingUnits].first else null,
                        aiDifficulty,
                        playerTeam == -1
                    )

                    dismiss()
                }
            }
        }
    }
}

@Composable
private fun MultiplayerOption(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    update: () -> Unit,
    extensions: List<Extension>,
    onShowBanUnitDialog: () -> Unit,
) = AnimatedAlertDialog(
    visible, onDismissRequest = { onDismissRequest(); update() }
) { dismiss ->
    val game = koinInject<Game>()
    val room = game.gameRoom
    val configIO = koinInject<ConfigIO>()

    val players = remember { room.getPlayers() }

    var noNukes by remember { mutableStateOf(room.noNukes) }
    var sharedControl by remember { mutableStateOf(room.sharedControl) }
    var allowSpectators by remember { mutableStateOf(room.allowSpectators) }
    var teamLock by remember { mutableStateOf(room.teamLock) }
    var aiDifficulty by remember { mutableStateOf(room.aiDifficulty) }
    var fogMode by remember { mutableStateOf(room.fogMode) }
    var startingUnits by remember { mutableStateOf(room.startingUnits) }
    var teamMode by remember { mutableStateOf(room.teamMode) }
    var startingCredits by remember { mutableStateOf(room.startingCredits) }
    var maxPlayerCount by remember { mutableStateOf(room.maxPlayerCount) }
    var realIncomeMultiplier by remember { mutableStateOf(room.incomeMultiplier) }
    var gameSpeed by remember { mutableStateOf(room.gameSpeed) }

    val teamModes = remember {
        TeamMode.modes
    }

    BorderCard(
        modifier = Modifier
            .fillMaxSize(LargeProportion()),
    ) {
        LazyColumn(modifier = Modifier.weight(1f).padding(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    MultiplayerOption(readI18n("multiplayer.room.noNukes"), noNukes) { noNukes = it }
                    MultiplayerOption(
                        readI18n("multiplayer.room.sharedControl"),
                        sharedControl
                    ) { sharedControl = it }
                    MultiplayerOption(
                        readI18n("multiplayer.room.allowSpectators"),
                        allowSpectators, room.isHost
                    ) { allowSpectators = it }
                    MultiplayerOption(readI18n("multiplayer.room.teamLock"), teamLock, room.isHost) {
                        teamLock = it
                    }
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    var selectedIndex1 by remember(room) { mutableStateOf((room.aiDifficulty + 2).coerceAtMost(Difficulty.entries.size - 1)) }
                    LargeDropdownMenu(
                        modifier = Modifier.weight(.3f).padding(5.dp),
                        label = readI18n("common.difficulty"),
                        items = Difficulty.entries,
                        selectedIndex = selectedIndex1,
                        onItemSelected = { index, _ -> selectedIndex1 = index }
                    )

                    LaunchedEffect(selectedIndex1) {
                        aiDifficulty = selectedIndex1 - 2
                    }

                    var selectedIndex2 by remember(room) { mutableStateOf(room.fogMode.ordinal) }
                    LargeDropdownMenu(
                        modifier = Modifier.weight(.3f).padding(5.dp),
                        label = readI18n("common.fog"),
                        items = FogMode.entries,
                        selectedIndex = selectedIndex2,
                        onItemSelected = { index, _ -> selectedIndex2 = index }
                    )

                    LaunchedEffect(selectedIndex2) {
                        fogMode = FogMode.entries[selectedIndex2]
                    }

                    val startingOptionList = remember {
                        game.getStartingUnitOptions()
                    }
                    var selectedIndex3 by remember(room) { mutableStateOf(startingOptionList.indexOfFirst { it.first == room.startingUnits }) }

                    LargeDropdownMenu(
                        modifier = Modifier.weight(.3f).padding(5.dp),
                        label = readI18n("multiplayer.room.startingUnit"),
                        items = startingOptionList,
                        selectedIndex = selectedIndex3,
                        onItemSelected = { index, _ -> selectedIndex3 = index },
                        selectedItemToString = { (_, s) -> s }
                    )

                    LaunchedEffect(selectedIndex3) {
                        startingUnits = startingOptionList[selectedIndex3].first
                    }
                }

            }

            item {
                Row(modifier = Modifier.fillMaxWidth()) {

                    val teamList = remember {
                        buildList {
                            add("Keep current")
                            addAll(teamModes)
                        }
                    }

                    var selectedIndex by remember {
                        mutableStateOf(teamMode?.let { teamModes.indexOf(teamMode) + 1 } ?: 0)
                    }
                    LargeDropdownMenu(
                        modifier = Modifier.weight(.5f).padding(5.dp),
                        label = readI18n("multiplayer.room.setTeam"),
                        enabled = room.isHost,
                        items = teamList,
                        selectedIndex = selectedIndex,
                        selectedItemToString = {
                            if (it is TeamMode) it.displayName else it.toString()
                        },
                        onItemSelected = { index, _ ->
                            selectedIndex = index
                            teamMode = when (index) {
                                0 -> null
                                else -> teamModes[index - 1]
                            }
                        }
                    )

                    var selectedIndex1 by remember(room) { mutableStateOf(room.startingCredits) }
                    val startingCreditList = remember {
                        listOf(
                            "Default ($4000)",
                            "$0",
                            "$1000",
                            "$2000",
                            "$5000",
                            "$10000",
                            "$50000",
                            "$100000",
                            "$200000"
                        )
                    }
                    LargeDropdownMenu(
                        modifier = Modifier.weight(.5f).padding(5.dp),
                        label = readI18n("multiplayer.room.startingCredits"),
                        items = startingCreditList,
                        selectedIndex = selectedIndex1,
                        onItemSelected = { index, _ -> selectedIndex1 = index }
                    )

                    LaunchedEffect(selectedIndex1) {
                        startingCredits = selectedIndex1
                    }
                }
            }

            item {
                var range by remember { mutableStateOf(room.maxPlayerCount) }

                Column(modifier = Modifier.wrapContentSize()) {
                    Text(
                        "${readI18n("multiplayer.room.maxPlayer")} : $range",
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                            .padding(0.dp, 5.dp, 0.dp, 5.dp)
                    )
                    Slider(
                        valueRange = players.size.toFloat()..100f,
                        modifier = Modifier.fillMaxWidth().padding(0.dp, 0.dp, 0.dp, 5.dp),
                        value = range.toFloat(),
                        enabled = room.isHost,
                        colors = RWSliderColors,
                        onValueChange = { range = it.roundToInt().coerceAtLeast(10) },
                        onValueChangeFinished = {
                            if (range >= players.size) maxPlayerCount = range else range =
                                room.maxPlayerCount
                        }
                    )
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    var incomeMultiplier by remember { mutableStateOf(room.incomeMultiplier.toString()) }
                    var expanded by remember { mutableStateOf(false) }
                    RWSingleOutlinedTextField(
                        readI18n("multiplayer.room.incomeMultiplier"),
                        incomeMultiplier,
                        lengthLimitCount = 5,
                        typeInNumberOnly = true,
                        modifier = Modifier.weight(.5f).padding(5.dp),
                        trailingIcon = {
                            val icon =
                                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
                            Icon(
                                icon,
                                "",
                                modifier = Modifier.clickable(!expanded) { expanded = !expanded })
                        },
                        appendedContent = {
                            BasicDropdownMenu(
                                expanded,
                                listOf(1f, 1.5f, 2f, 2.5f, 3f, 10f),
                                onItemSelected = { _, v -> incomeMultiplier = v.toString() }
                            ) {
                                expanded = false
                            }
                        }
                    ) {
                        incomeMultiplier = it
                    }

                    LaunchedEffect(incomeMultiplier) {
                        realIncomeMultiplier = incomeMultiplier.toFloatOrNull() ?: 1f
                    }

                    var teamUnitCapHostedGame by remember { mutableStateOf(configIO.getGameConfig<Int?>("teamUnitCapHostedGame")) }
                    var expanded1 by remember { mutableStateOf(false) }
                    LaunchedEffect(teamUnitCapHostedGame) {
                        val count = teamUnitCapHostedGame ?: 100
                        configIO.setGameConfig("teamUnitCapHostedGame", count)
                        game.setTeamUnitCapHostGame(count)
                    }
                    RWSingleOutlinedTextField(
                        readI18n("multiplayer.room.teamUnitCapHostedGame"),
                        teamUnitCapHostedGame?.toString() ?: "",
                        lengthLimitCount = 6,
                        typeInNumberOnly = true,
                        enabled = room.isHost,
                        modifier = Modifier.weight(.5f).padding(5.dp),
                        typeInOnlyInteger = true,
                        trailingIcon = {
                            val icon =
                                if (expanded1) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
                            Icon(
                                icon,
                                "",
                                modifier = Modifier.clickable(!expanded1 && room.isHost) { expanded1 = !expanded1 })
                        },
                        appendedContent = {
                            BasicDropdownMenu(
                                expanded1,
                                listOf(100, 250, 500, 1000, 2000, 5000, 10000),
                                onItemSelected = { _, v -> teamUnitCapHostedGame = v }
                            ) {
                                expanded1 = false
                            }
                        }
                    ) {
                        teamUnitCapHostedGame = it.toIntOrNull()
                    }
                }
            }

            item {
                var expanded by remember { mutableStateOf(false) }
                RWSingleOutlinedTextField(
                    readI18n("multiplayer.room.gameSpeed"),
                    gameSpeed.toString(),
                    lengthLimitCount = 5,
                    typeInNumberOnly = true,
                    enabled = room.isHost,
                    modifier = Modifier.weight(.5f).padding(5.dp),
                    trailingIcon = {
                        val icon =
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
                        Icon(
                            icon,
                            "",
                            modifier = Modifier.clickable(!expanded && room.isHost) { expanded = !expanded })
                    },
                    appendedContent = {
                        BasicDropdownMenu(
                            expanded,
                            listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f),
                            onItemSelected = { _, v -> gameSpeed = v }
                        ) {
                            expanded = false
                        }
                    }
                ) {
                    gameSpeed = it.toFloatOrNull() ?: 1f
                }
            }

            if (room.isHost) {
                item {
                    RWTextButton(
                        readI18n("multiplayer.room.banUnits"),
                        modifier = Modifier.padding(5.dp).align(Alignment.CenterHorizontally),
                    ) {
                        onShowBanUnitDialog()
                    }
                }
            }

            items(extensions, key = { it.config.id }) { extension ->
                if (extension.isEnabled && extension.extraRoomOptions.isNotEmpty()) {
                    Column {
                        Text(
                            extension.config.displayName,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 5.dp)
                        )

                        HorizontalDivider(
                            thickness = 3.dp,
                            modifier = Modifier.padding(top = 2.dp, bottom = 5.dp),
                            color = MaterialTheme.colorScheme.primary
                        )

                        for (widget in extension.extraRoomOptions) {
                            widget.Render()
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(5.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            RWTextButton(readI18n("multiplayer.room.apply")) {
                room.applyRoomConfig(
                    maxPlayerCount,
                    sharedControl,
                    startingCredits,
                    startingUnits,
                    fogMode,
                    aiDifficulty,
                    realIncomeMultiplier,
                    noNukes,
                    allowSpectators,
                    teamLock,
                    teamMode
                )

                room.gameSpeed = gameSpeed

                dismiss()
            }
        }
    }
}


@Composable
private fun PublishToListDialog(
    state: PublishToListUiState,
    onDismiss: () -> Unit,
    onSelectRoomType: (String) -> Unit,
    onRetry: (PublishStep) -> Unit,
) {
    val settings = koinInject<Settings>()
    val enableAnimations = settings.enableAnimations

    AnimatedAlertDialog(
        visible = state != PublishToListUiState.Hidden,
        onDismissRequest = {
            if (state !is PublishToListUiState.Loading) onDismiss()
        },
        enableDismiss = state !is PublishToListUiState.Loading,
    ) { dismiss ->
        BorderCard(
            modifier = Modifier
                .fillMaxWidth(LargeProportion())
                .widthIn(max = 360.dp)
                .padding(10.dp),
        ) {
            val showExit = state is PublishToListUiState.SelectRoomType
                || state is PublishToListUiState.Success
                || state is PublishToListUiState.Failure
            Box {
                if (showExit) ExitButton(dismiss)
                AnimatedContent(
                    targetState = state,
                    transitionSpec = {
                        if (enableAnimations) {
                            (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut()
                        } else {
                            EnterTransition.None togetherWith ExitTransition.None
                        }
                    },
                    label = "publishToListContent",
                ) { current ->
                    when (current) {
                        PublishToListUiState.Hidden -> Unit
                        is PublishToListUiState.Loading -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                LineSpinFadeLoaderIndicator(MaterialTheme.colorScheme.onSecondaryContainer)
                                Text(
                                    readI18n("multiplayer.room.publishLoading"),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    readI18n(
                                        if (current.step == PublishStep.FetchingTypes) {
                                            "multiplayer.room.publishFetchingTypes"
                                        } else {
                                            "multiplayer.room.publishSubmitting"
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        is PublishToListUiState.SelectRoomType -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 20.dp),
                            ) {
                                Text(
                                    readI18n("multiplayer.room.publishSelectType"),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 12.dp),
                                )
                                current.types.forEach { type ->
                                    RWTextButton(
                                        type,
                                        modifier = Modifier
                                            .padding(vertical = 4.dp)
                                            .fillMaxWidth(),
                                    ) { onSelectRoomType(type) }
                                }
                            }
                        }
                        is PublishToListUiState.Success -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = Color(0xFF4CAF50),
                                )
                                Text(
                                    readI18n("multiplayer.room.publishSuccess"),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    readI18n("multiplayer.room.publishSuccessHint"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    RWTextButton(readI18n("common.ok"), onClick = dismiss)
                                }
                            }
                        }
                        is PublishToListUiState.Failure -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = Color(0xFFFF9800),
                                )
                                Text(
                                    readI18n("multiplayer.room.publishFailed"),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    current.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(
                                        8.dp,
                                        Alignment.CenterHorizontally,
                                    ),
                                ) {
                                    RWTextButton(readI18n("settings.retry")) {
                                        onRetry(current.step)
                                    }
                                    RWTextButton(readI18n("common.close"), onClick = dismiss)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


private val RoomPlayerNameWeight = 0.6f
private val RoomPlayerSpawnWeight = 0.1f
private val RoomPlayerTeamWeight = 0.1f
private val RoomPlayerPingWeight = 0.2f

private val CompactChatPanelMinHeight = 220.dp
private val CompactChatMessageAreaMinHeight = 160.dp
private val CompactMapPreviewHeight = 140.dp
private val CompactPlayerListMinHeight = 140.dp
private val CompactScrollbarReservedWidth = 14.dp
private const val CompactScrollbarFadeInMillis = 280
private const val CompactScrollbarFadeOutMillis = 400
private const val CompactScrollbarHideDelayMillis = 350
/** 横屏左右面板等高时的固定高度（避免 Row + IntrinsicSize.Max 与 LazyColumn 冲突） */
private val CompactTopPanelHeight = 320.dp
private val CompactHostButtonMinHeight = 36.dp

@Composable
private fun CompactPlayerPanel(
    players: List<Player>,
    room: GameRoom,
    game: Game,
    update: Boolean,
    playerListState: LazyListState,
    rowShape: Shape,
    onPlayerClick: (Player) -> Unit,
    enableAnimations: Boolean,
    expandVertically: Boolean,
    modifier: Modifier = Modifier,
) {
    val playerListCanScroll by remember {
        derivedStateOf { lazyListCanScroll(playerListState) }
    }
    val reserveScrollbarSpace = playerListCanScroll && playerListState.isScrollInProgress
    val animatedScrollbarReservedWidth by animateDpAsState(
        targetValue = if (reserveScrollbarSpace) CompactScrollbarReservedWidth else 0.dp,
        animationSpec = tween(
            durationMillis = if (reserveScrollbarSpace) {
                CompactScrollbarFadeInMillis
            } else {
                CompactScrollbarFadeOutMillis
            },
            delayMillis = if (reserveScrollbarSpace) 0 else CompactScrollbarHideDelayMillis,
            easing = FastOutSlowInEasing,
        ),
        label = "compact player scrollbar reserved width",
    )

    CompactRoomPanel(
        modifier = modifier
            .then(
                if (expandVertically) {
                    Modifier.fillMaxHeight()
                } else {
                    Modifier.heightIn(min = 150.dp)
                },
            ),
    ) {
        RoomPlayerTableHeader(compact = true, rowShape = rowShape)
        LazyColumnScrollbar(
            listState = playerListState,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (expandVertically) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.heightIn(min = CompactPlayerListMinHeight)
                    },
                ),
            thickness = 4.dp,
            padding = 4.dp,
            alwaysShowScrollBar = false,
            selectionActionable = ScrollbarSelectionActionable.WhenVisible,
            showItemIndicator = ListIndicatorSettings.Disabled,
            hideDelay = CompactScrollbarHideDelayMillis.toDuration(DurationUnit.MILLISECONDS),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = playerListState,
                contentPadding = PaddingValues(end = animatedScrollbarReservedWidth),
            ) {
                items(
                    count = players.size,
                    key = { players[it].connectHexId },
                ) { index ->
                    val player = players[index]
                    RoomPlayerTableRow(
                        player = player,
                        room = room,
                        game = game,
                        update = update,
                        onPlayerClick = onPlayerClick,
                        compact = true,
                        rowShape = rowShape,
                        modifier = if (enableAnimations) {
                            Modifier.animateItem()
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactMapSettingsPanel(
    mapType: MapType,
    displayMapName: String,
    selectedMap: io.github.rwpp.game.map.GameMap,
    isHost: Boolean,
    isSandboxGame: Boolean,
    isLocked: Boolean,
    onLockToggle: () -> Unit,
    isDesktop: Boolean,
    showPublishButton: Boolean,
    onOption: () -> Unit,
    onStart: () -> Unit,
    onAddAI: () -> Unit,
    onAddAIMany: () -> Unit,
    onPublish: () -> Unit,
    onMapClick: () -> Unit,
    expandVertically: Boolean,
    modifier: Modifier = Modifier,
) {
    CompactRoomPanel(
        modifier = if (expandVertically) {
            modifier.fillMaxHeight()
        } else {
            modifier
        },
    ) {
        Text(
            mapType.displayName(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            displayMapName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        RoomMapPreview(
            selectedMap = selectedMap,
            isHost = isHost,
            onMapClick = onMapClick,
            modifier = if (expandVertically) {
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 64.dp, max = CompactMapPreviewHeight)
            } else {
                Modifier
                    .fillMaxWidth()
                    .height(CompactMapPreviewHeight)
            },
        )
        if (!isSandboxGame && isHost) {
            IconButton(
                onClick = onLockToggle,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enabled = isHost,
            ) {
                Icon(
                    Icons.Default.Lock,
                    null,
                    tint = if (isLocked) {
                        Color(237, 112, 20)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
        if (isHost) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CompactRoomTextButton(
                        readI18n("multiplayer.room.option") + if (isDesktop) "(O)" else "",
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = CompactHostButtonMinHeight),
                        onClick = onOption,
                    )
                    CompactRoomTextButton(
                        readI18n("multiplayer.room.start"),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = CompactHostButtonMinHeight),
                        onClick = onStart,
                    )
                    if (!isSandboxGame) {
                        CompactRoomTextButton(
                            readI18n("multiplayer.room.addAI") + if (isDesktop) "(A)" else "",
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = CompactHostButtonMinHeight),
                            onLongClick = onAddAIMany,
                            onClick = onAddAI,
                        )
                    }
                }
                if (showPublishButton) {
                    CompactRoomTextButton(
                        readI18n("multiplayer.room.publishToList"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = CompactHostButtonMinHeight),
                        onClick = onPublish,
                    )
                }
            }
        }
    }
}

private fun parseRoomDetailEntries(details: String): List<Pair<String, String>> {
    return details.split("\n")
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val separatorIndex = trimmed.indexOf(':')
            if (separatorIndex <= 0) return@mapNotNull null
            val label = trimmed.substring(0, separatorIndex).trim()
            val value = trimmed.substring(separatorIndex + 1).trim()
            if (label.isEmpty() || value.isEmpty()) return@mapNotNull null
            label to value
        }
}

@Composable
private fun RoomDetailsPanel(
    details: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val entries = remember(details) { parseRoomDetailEntries(details) }
    if (entries.isEmpty()) {
        if (details.isNotBlank()) {
            SelectionContainer {
                Text(
                    details,
                    style = if (compact) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = modifier.fillMaxWidth(),
                )
            }
        }
        return
    }

    val labelStyle = if (compact) {
        MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.bodySmall
    }
    val valueStyle = if (compact) {
        MaterialTheme.typography.bodySmall
    } else {
        MaterialTheme.typography.bodyMedium
    }

    SelectionContainer {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        ) {
            entries.forEach { (label, value) ->
                Column(
                    modifier = Modifier.widthIn(min = 96.dp, max = 180.dp),
                ) {
                    Text(
                        label,
                        style = labelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        value,
                        style = valueStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopMapSettingsCard(
    mapType: MapType,
    displayMapName: String,
    selectedMap: io.github.rwpp.game.map.GameMap,
    roomDetails: String,
    isHost: Boolean,
    isDesktop: Boolean,
    hasPublishedInfo: Boolean,
    remainingSeconds: Int,
    totalSeconds: Int,
    isRefreshingExpiry: Boolean,
    onRenew: () -> Unit,
    showPublishButton: Boolean,
    onMapClick: () -> Unit,
    onOption: () -> Unit,
    onStart: () -> Unit,
    onPublish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        if (hasPublishedInfo) {
            PublishedRoomExpiryBar(
                remainingSeconds = remainingSeconds,
                totalSeconds = totalSeconds,
                isRefreshing = isRefreshingExpiry,
                onRenew = onRenew,
                modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 10.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                mapType.displayName(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                displayMapName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            RoomMapPreview(
                selectedMap = selectedMap,
                isHost = isHost,
                onMapClick = onMapClick,
                modifier = Modifier.fillMaxSize(),
            )
        }

        RoomDetailsPanel(
            details = roomDetails,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )

        if (isHost) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                RWTextButton(
                    readI18n("multiplayer.room.selectMap") + if (isDesktop) "(M)" else "",
                    modifier = Modifier.padding(5.dp),
                    onClick = onMapClick,
                )
                RWTextButton(
                    readI18n("multiplayer.room.option") + if (isDesktop) "(O)" else "",
                    modifier = Modifier.padding(5.dp),
                    onClick = onOption,
                )
                RWTextButton(
                    readI18n("multiplayer.room.start"),
                    modifier = Modifier.padding(5.dp),
                    onClick = onStart,
                )
                if (showPublishButton) {
                    RWTextButton(
                        readI18n("multiplayer.room.publishToList"),
                        modifier = Modifier.padding(5.dp),
                        onClick = onPublish,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactRoomDetailsBar(
    roomDetails: String,
    onShowDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectionContainer {
        Text(
            roomDetails,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .clickable(onClick = onShowDetails),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RoomDetailsDialog(
    details: String,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    AnimatedAlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
    ) { dismiss ->
        BorderCard(
            modifier = Modifier
                .fillMaxWidth(LargeProportion())
                .widthIn(max = 480.dp)
                .padding(10.dp),
        ) {
            Box {
                ExitButton(dismiss)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 28.dp, end = 12.dp, bottom = 12.dp),
                ) {
                    Text(
                        readI18n("multiplayer.room.roomDetails"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    RoomDetailsPanel(
                        details = details,
                        compact = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(scrollState),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        RWTextButton(readI18n("common.close"), onClick = dismiss)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactRoomTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    CompositionLocalProvider(
        LocalRippleConfiguration provides RippleConfiguration(Color.Transparent, RippleAlpha(0f, 0f, 0f, 0f)),
    ) {
        Card(
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.surfaceContainer),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            modifier = modifier.bounceClick(onLongClick = onLongClick, onClick = onClick),
        ) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CompactRoomPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .border(
                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                RoundedCornerShape(8.dp),
            )
            .background(
                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp),
            )
            .padding(4.dp),
        content = content,
    )
}

@Composable
private fun RoomPlayerTableHeader(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    rowShape: Shape = CircleShape,
) {
    val strokeColor = MaterialTheme.colorScheme.secondaryContainer
    Row(
        modifier = modifier
            .padding(if (compact) 2.dp else 5.dp)
            .border(
                BorderStroke(2.dp, MaterialTheme.colorScheme.secondary),
                rowShape,
            )
            .fillMaxWidth(),
    ) {
        TableCell(readI18n("multiplayer.room.colName"), RoomPlayerNameWeight, drawStroke = false, strokeColor = strokeColor)
        TableCell(readI18n("multiplayer.room.colSpawn"), RoomPlayerSpawnWeight, strokeColor = strokeColor)
        TableCell(readI18n("multiplayer.room.colTeam"), RoomPlayerTeamWeight, strokeColor = strokeColor)
        TableCell(readI18n("multiplayer.room.colPing"), RoomPlayerPingWeight, drawStroke = false, strokeColor = strokeColor)
    }
}

@Composable
private fun RoomPlayerTableRow(
    player: Player,
    room: GameRoom,
    game: Game,
    update: Boolean,
    onPlayerClick: (Player) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    rowShape: Shape = CircleShape,
) {
    val options = remember { game.getStartingUnitOptions() }
    val rowPadding = if (compact) 2.dp else 5.dp
    Box(modifier) {
        KickPlayerContextMenuAreaMultiplatform(player) {
            Row(
                modifier = Modifier
                    .height(IntrinsicSize.Max)
                    .padding(rowPadding)
                    .border(
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        rowShape,
                    )
                    .fillMaxWidth()
                    .clickable(room.isHost || room.isHostServer || room.localPlayer == player) {
                        onPlayerClick(player)
                    },
            ) {
                TableCell(
                    player.name + if (player.startingUnit != -1) {
                        " - ${options.firstOrNull { it.first == player.startingUnit }?.second ?: "Unknown"}"
                    } else "",
                    color = if (player.color != -1) {
                        Player.getTeamColor(player.color)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    weight = RoomPlayerNameWeight,
                    drawStroke = false,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    if (!player.data.ready) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(if (compact) 12.dp else 15.dp)
                                .padding(0.dp, 2.dp, 0.dp, 2.dp),
                        )
                    }
                }
                TableCell(
                    if (player.isSpectator) "S" else (player.spawnPoint + 1).toString(),
                    RoomPlayerSpawnWeight,
                    color = if (player.isSpectator) Color.Black else Player.getTeamColor(player.spawnPoint),
                    modifier = Modifier.fillMaxHeight(),
                )
                TableCell(
                    player.teamAlias(),
                    RoomPlayerTeamWeight,
                    color = Player.getTeamColor(player.team),
                    modifier = Modifier.fillMaxHeight(),
                )
                val ping = remember(update) { player.ping }
                TableCell(
                    ping,
                    RoomPlayerPingWeight,
                    drawStroke = false,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun RoomChatMessageTextField(
    chatMessage: String,
    focusRequester: FocusRequester,
    onChatMessageChange: (String) -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val sendAction = {
        if (chatMessage.isNotEmpty()) {
            onSend(chatMessage)
            onChatMessageChange("")
        }
    }
    val keyModifier = Modifier.onKeyEvent {
        if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && chatMessage.isNotEmpty()) {
            sendAction()
        }
        true
    }
    val trailingIcon: @Composable () -> Unit = {
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            null,
            modifier = Modifier.clickable(onClick = sendAction),
            tint = MaterialTheme.colorScheme.surfaceTint,
        )
    }

    if (compact) {
        OutlinedTextField(
            value = chatMessage,
            onValueChange = onChatMessageChange,
            placeholder = {
                Text(
                    readI18n("multiplayer.room.sendMessage"),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            trailingIcon = trailingIcon,
            colors = RWOutlinedTextColors,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .defaultMinSize(minHeight = 52.dp)
                .focusRequester(focusRequester)
                .then(keyModifier),
        )
    } else {
        RWSingleOutlinedTextField(
            label = readI18n("multiplayer.room.sendMessage"),
            value = chatMessage,
            focusRequester = focusRequester,
            modifier = modifier
                .fillMaxWidth()
                .padding(10.dp)
                .then(keyModifier),
            trailingIcon = trailingIcon,
            onValueChange = onChatMessageChange,
        )
    }
}

@Composable
private fun RoomChatMessageView(modifier: Modifier = Modifier) {
    var value by remember(chatMessages) { mutableStateOf(TextFieldValue(chatMessages)) }
    LaunchedEffect(chatMessages) {
        value = TextFieldValue(
            annotatedString = chatMessages,
            selection = TextRange(chatMessages.length),
        )
    }
    TextField(
        value = value,
        onValueChange = { value = it },
        readOnly = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
        colors = RWTextFieldColors,
        maxLines = Int.MAX_VALUE,
    )
}

@Composable
private fun RoomMapPreview(
    selectedMap: io.github.rwpp.game.map.GameMap,
    isHost: Boolean,
    onMapClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewShape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .padding(vertical = 4.dp)
            .clip(previewShape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.35f))
            .border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.surfaceContainer), previewShape)
            .clickable(isHost, onClick = onMapClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            ImageRequest.Builder(LocalPlatformContext.current)
                .data(selectedMap)
                .precision(Precision.INEXACT)
                .build(),
            null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PublishedRoomExpiryBar(
    remainingSeconds: Int,
    totalSeconds: Int,
    isRefreshing: Boolean,
    onRenew: () -> Unit,
    modifier: Modifier = Modifier
) {
    val compact = LocalWindowManager.current != WindowManager.Large
    val safeRemainingSeconds = remainingSeconds.coerceAtLeast(0)
    val progress = if (totalSeconds > 0) {
        (safeRemainingSeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
    } else 1f
    val barColor = when {
        progress > 0.5f -> Color(0xFF4CAF50)
        progress > 0.2f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
    val minutes = safeRemainingSeconds / 60
    val seconds = safeRemainingSeconds % 60
    val timeText = if (safeRemainingSeconds > 0) minutes.toString().padStart(2, '0') + ":" + seconds.toString().padStart(2, '0')
        else "--:--"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                BorderStroke(1.dp, barColor.copy(alpha = 0.65f)),
                RoundedCornerShape(12.dp)
            )
            .padding(
                horizontal = if (compact) 8.dp else 10.dp,
                vertical = if (compact) 5.dp else 7.dp
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                readI18n("multiplayer.room.listExpiryLabel") + " $timeText",
                modifier = Modifier.weight(1f),
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = barColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(
                enabled = !isRefreshing,
                onClick = onRenew,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = if (compact) 28.dp else 32.dp)
            ) {
                Text(
                    readI18n("multiplayer.room.listExpiryRenew"),
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.height(if (compact) 3.dp else 4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(if (compact) 4.dp else 5.dp),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainer
        )
    }
}