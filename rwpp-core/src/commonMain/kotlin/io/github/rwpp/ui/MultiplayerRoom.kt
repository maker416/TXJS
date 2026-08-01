/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
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
import io.github.rwpp.net.DEFAULT_PUBLISH_ROOM_TYPE
import io.github.rwpp.net.HostTransferSnapshot
import io.github.rwpp.net.MOD_SYNC_ROOM_TYPE
import io.github.rwpp.net.Net
import io.github.rwpp.net.composePublishRoomType
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

    data class SelectRoomType(
        val types: List<String>,
        val allowCustomRoomName: Boolean,
        /** 房主开启传输模组时为 true：普通标签仍可多选，模组同步标签会被自动追加。 */
        val modSyncEnabled: Boolean = false,
    ) : PublishToListUiState()

    data class Success(val roomId: String, val serverId: String) : PublishToListUiState()

    data class Failure(val message: String, val step: PublishStep) : PublishToListUiState()
}

@Composable
fun MultiplayerRoomView(isSandboxGame: Boolean = false, onExit: () -> Unit) {
    BackHandler(true, onExit)
    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("multiplayerRoom").broadcastIn()
            UI.clearAutoPublishQRoom()
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
    var pendingPublishRoomName by remember { mutableStateOf<String?>(null) }
    var roomIdForPublish by remember { mutableStateOf<String?>(null) }
    var publishJob by remember { mutableStateOf<Job?>(null) }
    var publishDialogCloseExitsRoom by remember { mutableStateOf(false) }
    val isPublishing = publishState is PublishToListUiState.Loading || publishJob?.isActive == true
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

    suspend fun submitPublish(roomId: String, roomType: String, roomName: String) {
        pendingPublishRoomType = roomType
        val effectiveName = roomName.trim().ifBlank { "公开房-$roomId" }
        pendingPublishRoomName = effectiveName
        publishState = PublishToListUiState.Loading(PublishStep.Publishing)
        val startMs = System.currentTimeMillis()
        val result = with(net) {
            publishServerToPublicList(
                DEFAULT_ROOM_LIST_API_URLS,
                effectiveName,
                roomListPublishAddress(roomId),
                roomType
            )
        }
        currentCoroutineContext().ensureActive()
        ensureMinPublishLoading(startMs)
        currentCoroutineContext().ensureActive()
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
        val (types, health) = coroutineScope {
            val typesDeferred = async {
                with(net) {
                    fetchRoomTypes(DEFAULT_ROOM_LIST_API_URLS)
                }
            }
            val healthDeferred = async {
                with(net) {
                    fetchRwListHealth(DEFAULT_ROOM_LIST_API_URLS)
                }
            }
            typesDeferred.await() to healthDeferred.await()
        }
        currentCoroutineContext().ensureActive()
        ensureMinPublishLoading(startMs)
        currentCoroutineContext().ensureActive()
        when {
            types.isEmpty() -> {
                kickListDetectorPlayers()
                publishState = PublishToListUiState.Failure(
                    readI18n("multiplayer.room.publishFetchTypesFailed"),
                    PublishStep.FetchingTypes,
                )
            }
            else -> {
                // 「模组同步」标签与传输模组特性绑定：
                // - 始终不作为可手选的普通标签出现；
                // - 开启传输模组时由客户端自动追加该协议哨兵，普通标签仍可自由多选；
                // - 未开启传输模组时不追加，也不会出现在候选中。
                val canTransferMod = room.option.canTransferMod
                val selectableTypes = types.filterNot { it.equals(MOD_SYNC_ROOM_TYPE, ignoreCase = true) }
                if (canTransferMod && types.none { it.equals(MOD_SYNC_ROOM_TYPE, ignoreCase = true) }) {
                    // 房间列表白名单未提供模组同步标签，发布必然被服务端白名单拒绝
                    kickListDetectorPlayers()
                    publishState = PublishToListUiState.Failure(
                        readI18n("multiplayer.room.publishModSyncTypeMissing"),
                        PublishStep.FetchingTypes,
                    )
                } else {
                    publishState = PublishToListUiState.SelectRoomType(
                        types = selectableTypes,
                        allowCustomRoomName = health.allowCustomName,
                        modSyncEnabled = canTransferMod,
                    )
                }
            }
        }
    }

    fun launchPublish(block: suspend () -> Unit) {
        if (publishJob?.isActive == true) return
        val job = scope.launch { block() }
        publishJob = job
        job.invokeOnCompletion {
            scope.launch {
                if (publishJob == job) publishJob = null
            }
        }
    }

    fun cancelPublishRequest() {
        publishJob?.cancel()
        publishJob = null
        pendingPublishRoomType = null
        pendingPublishRoomName = null
        publishDialogCloseExitsRoom = false
        publishState = PublishToListUiState.Hidden
    }

    fun publishToList(roomId: String, closeExitsRoom: Boolean = false) {
        if (isPublishing || hasPublishedInfo) return
        publishDialogCloseExitsRoom = closeExitsRoom
        launchPublish { runPublishFlow(roomId) }
    }

    PublishToListDialog(
        state = publishState,
        defaultRoomName = roomIdForPublish?.let { "公开房-$it" } ?: "",
        onDismiss = {
            publishDialogCloseExitsRoom = false
            publishState = PublishToListUiState.Hidden
        },
        onCancel = ::cancelPublishRequest,
        onCloseButtonClick = {
            if (publishDialogCloseExitsRoom) {
                onExit()
            } else {
                publishDialogCloseExitsRoom = false
                publishState = PublishToListUiState.Hidden
            }
        },
        onPublish = { roomType, roomName ->
            val roomId = roomIdForPublish ?: return@PublishToListDialog
            launchPublish { submitPublish(roomId, roomType, roomName) }
        },
        onRetry = { step ->
            val roomId = roomIdForPublish ?: return@PublishToListDialog
            launchPublish {
                when (step) {
                    PublishStep.FetchingTypes -> runPublishFlow(roomId)
                    PublishStep.Publishing -> {
                        val roomType = pendingPublishRoomType
                        val roomName = pendingPublishRoomName
                        if (roomType != null && roomName != null) submitPublish(roomId, roomType, roomName)
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

    LaunchedEffect(roomIdForPublish, isHost, hasPublishedInfo) {
        val roomId = roomIdForPublish ?: return@LaunchedEffect
        if (!roomId.startsWith('Q') || hasPublishedInfo) {
            UI.clearAutoPublishQRoom()
            return@LaunchedEffect
        }
        if (!isHost) return@LaunchedEffect
        val publishedInfo = configIO.readConfig(PublishedRoomInfo::class)
        if (publishedInfo?.roomId == roomId && publishedInfo.isPublished) {
            UI.clearAutoPublishQRoom()
            return@LaunchedEffect
        }
        if (UI.consumeAutoPublishQRoom()) {
            publishToList(roomId, closeExitsRoom = true)
        }
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
                UI.showWarning(
                    readI18n(
                        "multiplayer.room.playersNotReadyModSync",
                        I18nType.RWPP,
                        unpreparedPlayers.joinToString(", ") { it.name },
                    ),
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
                                            publishToList(roomId)
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
                                            publishToList(roomId)
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
                                RoomChatMessageTextField(
                                    chatMessage = chatMessage,
                                    focusRequester = chatFocusRequester,
                                    onChatMessageChange = { chatMessage = it },
                                    onSend = room::sendChatMessageOrCommand,
                                    compact = true,
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = CompactChatMessageAreaMinHeight),
                                ) {
                                    RoomChatMessageView(modifier = Modifier.fillMaxSize())
                                }
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
                                        publishToList(roomId)
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
                        label = readI18n("multiplayer.room.startingUnit"),
                        items = items,
                        selectedIndex = playerStartingUnits,
                        onItemSelected = { i, _ -> playerStartingUnits = i },
                        selectedItemToString = { (_, s) -> localizeStartingUnitOption(s) }
                    )
                }


                if (player.isAI) {
                    LargeDropdownMenu(
                        modifier = Modifier.padding(20.dp),
                        label = readI18n("common.difficulty"),
                        items = Difficulty.entries,
                        selectedIndex = (aiDifficulty + 2).coerceAtMost(Difficulty.entries.size - 1),
                        selectedItemToString = ::difficultyDisplayName,
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

private fun difficultyDisplayName(difficulty: Difficulty): String = when (difficulty) {
    Difficulty.VeryEasy -> readI18n("common.difficultyLevel.veryEasy")
    Difficulty.Easy -> readI18n("common.difficultyLevel.easy")
    Difficulty.Medium -> readI18n("common.difficultyLevel.medium")
    Difficulty.Hard -> readI18n("common.difficultyLevel.hard")
    Difficulty.VeryHard -> readI18n("common.difficultyLevel.veryHard")
    Difficulty.Impossible -> readI18n("common.difficultyLevel.impossible")
}

private fun fogModeDisplayName(fogMode: FogMode): String = when (fogMode) {
    FogMode.NoFog -> readI18n("common.fogMode.noFog")
    FogMode.BasicFog -> readI18n("common.fogMode.basicFog")
    FogMode.LOSFog -> readI18n("common.fogMode.losFog")
}

private fun teamModeDisplayName(mode: TeamMode): String = when (mode.name) {
    "2t" -> readI18n("multiplayer.room.teamMode.twoTeams")
    "3t" -> readI18n("multiplayer.room.teamMode.threeTeams")
    "FFA" -> readI18n("multiplayer.room.teamMode.ffa")
    "spectators" -> readI18n("multiplayer.room.teamMode.spectators")
    "random-team" -> readI18n("multiplayer.room.teamMode.randomTeam")
    "all-vs-ai" -> readI18n("multiplayer.room.teamMode.allVsAi")
    "all-vs-2" -> readI18n("multiplayer.room.teamMode.allVs2")
    else -> mode.displayName
}

private fun localizeStartingUnitOption(raw: String): String = when (raw.trim()) {
    "Normal (1 builder)" -> readI18n("multiplayer.room.startingUnitOption.normal1Builder")
    "Small Army" -> readI18n("multiplayer.room.startingUnitOption.smallArmy")
    "3 Engineers" -> readI18n("multiplayer.room.startingUnitOption.threeEngineers")
    "3 Engineers (No Command Center)" -> readI18n("multiplayer.room.startingUnitOption.threeEngineersNoCc")
    "Experimental Spider" -> readI18n("multiplayer.room.startingUnitOption.experimentalSpider")
    else -> raw
}

@Composable
private fun RoomOptionFieldRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        content = content,
    )
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

    val teamModes = remember { TeamMode.modes }
    val startingOptionList = remember { game.getStartingUnitOptions() }
    val startingCreditLabels = remember {
        listOf(
            readI18n("multiplayer.room.startingCreditsDefault"),
            "$0",
            "$1000",
            "$2000",
            "$5000",
            "$10000",
            "$50000",
            "$100000",
            "$200000",
        )
    }
    val keepCurrentTeamLabel = remember { readI18n("multiplayer.room.keepCurrentTeam") }
    val teamList = remember(keepCurrentTeamLabel) {
        buildList<Any> {
            add(keepCurrentTeamLabel)
            addAll(teamModes)
        }
    }

    BorderCard(
        modifier = Modifier
            .fillMaxWidth(LargeProportion())
            .fillMaxHeight(0.88f),
    ) {
        Text(
            readI18n("multiplayer.room.option"),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MultiplayerOption(readI18n("multiplayer.room.noNukes"), noNukes) { noNukes = it }
                    MultiplayerOption(
                        readI18n("multiplayer.room.sharedControl"),
                        sharedControl,
                    ) { sharedControl = it }
                    MultiplayerOption(
                        readI18n("multiplayer.room.allowSpectators"),
                        allowSpectators,
                        room.isHost,
                    ) { allowSpectators = it }
                    MultiplayerOption(
                        readI18n("multiplayer.room.teamLock"),
                        teamLock,
                        room.isHost,
                    ) { teamLock = it }
                }
            }

            item {
                RoomOptionFieldRow {
                    var selectedDifficulty by remember(room) {
                        mutableStateOf((room.aiDifficulty + 2).coerceAtMost(Difficulty.entries.size - 1))
                    }
                    LargeDropdownMenu(
                        modifier = Modifier.weight(1f),
                        label = readI18n("common.difficulty"),
                        items = Difficulty.entries,
                        selectedIndex = selectedDifficulty,
                        selectedItemToString = ::difficultyDisplayName,
                        onItemSelected = { index, _ -> selectedDifficulty = index },
                    )
                    LaunchedEffect(selectedDifficulty) {
                        aiDifficulty = selectedDifficulty - 2
                    }

                    var selectedFog by remember(room) { mutableStateOf(room.fogMode.ordinal) }
                    LargeDropdownMenu(
                        modifier = Modifier.weight(1f),
                        label = readI18n("common.fog"),
                        items = FogMode.entries,
                        selectedIndex = selectedFog,
                        selectedItemToString = ::fogModeDisplayName,
                        onItemSelected = { index, _ -> selectedFog = index },
                    )
                    LaunchedEffect(selectedFog) {
                        fogMode = FogMode.entries[selectedFog]
                    }
                }
            }

            item {
                RoomOptionFieldRow {
                    var selectedStartingUnit by remember(room) {
                        mutableStateOf(startingOptionList.indexOfFirst { it.first == room.startingUnits }.coerceAtLeast(0))
                    }
                    LargeDropdownMenu(
                        modifier = Modifier.weight(1f),
                        label = readI18n("multiplayer.room.startingUnit"),
                        items = startingOptionList,
                        selectedIndex = selectedStartingUnit,
                        selectedItemToString = { (_, s) -> localizeStartingUnitOption(s) },
                        onItemSelected = { index, _ -> selectedStartingUnit = index },
                    )
                    LaunchedEffect(selectedStartingUnit) {
                        startingUnits = startingOptionList[selectedStartingUnit].first
                    }

                    var selectedCredits by remember(room) { mutableStateOf(room.startingCredits) }
                    LargeDropdownMenu(
                        modifier = Modifier.weight(1f),
                        label = readI18n("multiplayer.room.startingCredits"),
                        items = startingCreditLabels,
                        selectedIndex = selectedCredits,
                        onItemSelected = { index, _ -> selectedCredits = index },
                    )
                    LaunchedEffect(selectedCredits) {
                        startingCredits = selectedCredits
                    }
                }
            }

            item {
                RoomOptionFieldRow {
                    var selectedTeam by remember {
                        mutableStateOf(teamMode?.let { teamModes.indexOf(it) + 1 } ?: 0)
                    }
                    LargeDropdownMenu(
                        modifier = Modifier.weight(1f),
                        label = readI18n("multiplayer.room.setTeam"),
                        enabled = room.isHost,
                        items = teamList,
                        selectedIndex = selectedTeam,
                        selectedItemToString = {
                            if (it is TeamMode) teamModeDisplayName(it) else it.toString()
                        },
                        onItemSelected = { index, _ ->
                            selectedTeam = index
                            teamMode = if (index == 0) null else teamModes[index - 1]
                        },
                    )

                    var incomeMultiplier by remember { mutableStateOf(room.incomeMultiplier.toString()) }
                    var incomeExpanded by remember { mutableStateOf(false) }
                    RWSingleOutlinedTextField(
                        readI18n("multiplayer.room.incomeMultiplier"),
                        incomeMultiplier,
                        lengthLimitCount = 5,
                        typeInNumberOnly = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            val icon =
                                if (incomeExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
                            Icon(
                                icon,
                                "",
                                modifier = Modifier.clickable(!incomeExpanded) { incomeExpanded = !incomeExpanded },
                            )
                        },
                        appendedContent = {
                            BasicDropdownMenu(
                                incomeExpanded,
                                listOf(1f, 1.5f, 2f, 2.5f, 3f, 10f),
                                onItemSelected = { _, v -> incomeMultiplier = v.toString() },
                            ) {
                                incomeExpanded = false
                            }
                        },
                    ) {
                        incomeMultiplier = it
                    }
                    LaunchedEffect(incomeMultiplier) {
                        realIncomeMultiplier = incomeMultiplier.toFloatOrNull() ?: 1f
                    }
                }
            }

            item {
                RoomOptionFieldRow {
                    var teamUnitCapHostedGame by remember {
                        mutableStateOf(configIO.getGameConfig<Int?>("teamUnitCapHostedGame"))
                    }
                    var unitCapExpanded by remember { mutableStateOf(false) }
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
                        modifier = Modifier.weight(1f),
                        typeInOnlyInteger = true,
                        trailingIcon = {
                            val icon =
                                if (unitCapExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
                            Icon(
                                icon,
                                "",
                                modifier = Modifier.clickable(!unitCapExpanded && room.isHost) {
                                    unitCapExpanded = !unitCapExpanded
                                },
                            )
                        },
                        appendedContent = {
                            BasicDropdownMenu(
                                unitCapExpanded,
                                listOf(100, 250, 500, 1000, 2000, 5000, 10000),
                                onItemSelected = { _, v -> teamUnitCapHostedGame = v },
                            ) {
                                unitCapExpanded = false
                            }
                        },
                    ) {
                        teamUnitCapHostedGame = it.toIntOrNull()
                    }

                    var speedExpanded by remember { mutableStateOf(false) }
                    RWSingleOutlinedTextField(
                        readI18n("multiplayer.room.gameSpeed"),
                        gameSpeed.toString(),
                        lengthLimitCount = 5,
                        typeInNumberOnly = true,
                        enabled = room.isHost,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            val icon =
                                if (speedExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
                            Icon(
                                icon,
                                "",
                                modifier = Modifier.clickable(!speedExpanded && room.isHost) {
                                    speedExpanded = !speedExpanded
                                },
                            )
                        },
                        appendedContent = {
                            BasicDropdownMenu(
                                speedExpanded,
                                listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f),
                                onItemSelected = { _, v -> gameSpeed = v },
                            ) {
                                speedExpanded = false
                            }
                        },
                    ) {
                        gameSpeed = it.toFloatOrNull() ?: 1f
                    }
                }
            }

            item {
                var range by remember { mutableStateOf(room.maxPlayerCount) }
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                    Text(
                        "${readI18n("multiplayer.room.maxPlayer")}：$range",
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Slider(
                        valueRange = players.size.toFloat()..100f,
                        modifier = Modifier.fillMaxWidth(),
                        value = range.toFloat(),
                        enabled = room.isHost,
                        colors = RWSliderColors,
                        onValueChange = { range = it.roundToInt().coerceAtLeast(10) },
                        onValueChangeFinished = {
                            if (range >= players.size) maxPlayerCount = range
                            else range = room.maxPlayerCount
                        },
                    )
                }
            }

            if (room.isHost) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        RWTextButton(readI18n("multiplayer.room.banUnits")) {
                            onShowBanUnitDialog()
                        }
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
                            modifier = Modifier.padding(start = 5.dp),
                        )

                        HorizontalDivider(
                            thickness = 3.dp,
                            modifier = Modifier.padding(top = 2.dp, bottom = 5.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )

                        for (widget in extension.extraRoomOptions) {
                            widget.Render()
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
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
                    teamMode,
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
    defaultRoomName: String,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onCloseButtonClick: () -> Unit,
    onPublish: (String, String) -> Unit,
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
                .widthIn(max = 540.dp)
                .padding(10.dp),
        ) {
            val showExit = state is PublishToListUiState.SelectRoomType
                || state is PublishToListUiState.Success
                || state is PublishToListUiState.Failure
            Box {
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
                                RWTextButton(
                                    readI18n("multiplayer.room.cancelPublish"),
                                    modifier = Modifier.padding(top = 4.dp),
                                    onClick = onCancel,
                                )
                            }
                        }
                        is PublishToListUiState.SelectRoomType -> {
                            val modSyncEnabled = current.modSyncEnabled
                            // 多选状态：保持 Set 语义，但序列化时按服务端返回顺序过滤，保证请求稳定
                            var selectedRoomTypes by remember(current.types) {
                                mutableStateOf<Set<String>>(emptySet())
                            }
                            var roomName by remember(defaultRoomName, current.allowCustomRoomName) {
                                mutableStateOf(defaultRoomName)
                            }
                            var roomNameHintFlashTick by remember { mutableIntStateOf(0) }
                            var roomNameHintHighlighted by remember { mutableStateOf(false) }
                            val effectiveRoomName = if (current.allowCustomRoomName) {
                                roomName.trim().ifBlank { defaultRoomName }
                            } else {
                                defaultRoomName
                            }
                            val orderedSelected = current.types.filter { it in selectedRoomTypes }
                            // 未选手动标签时由 composePublishRoomType 默认补「默认」，允许直接点公开
                            val effectiveRoomType = composePublishRoomType(orderedSelected, modSyncEnabled)
                            val canPublish = effectiveRoomName.isNotBlank()
                            val roomNameHintColor by animateColorAsState(
                                targetValue = if (roomNameHintHighlighted) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                animationSpec = tween(120),
                                label = "roomNameHintColor",
                            )

                            LaunchedEffect(roomNameHintFlashTick) {
                                if (roomNameHintFlashTick == 0) return@LaunchedEffect
                                roomNameHintHighlighted = true
                                delay(120)
                                roomNameHintHighlighted = false
                                delay(80)
                                roomNameHintHighlighted = true
                                delay(120)
                                roomNameHintHighlighted = false
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 520.dp),
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.14f),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            readI18n("multiplayer.room.publishToList"),
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            readI18n("multiplayer.room.publishSelectTypeHint"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 340.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 20.dp, vertical = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(modifier = Modifier.fillMaxWidth()) {
                                            RWSingleOutlinedTextField(
                                                label = readI18n("multiplayer.room.publishRoomNameLabel"),
                                                value = roomName,
                                                lengthLimitCount = 32,
                                                modifier = Modifier.fillMaxWidth(),
                                                enabled = current.allowCustomRoomName,
                                                onValueChange = { roomName = it },
                                            )
                                            if (!current.allowCustomRoomName) {
                                                Box(
                                                    modifier = Modifier
                                                        .matchParentSize()
                                                        .clickable(
                                                            indication = null,
                                                            interactionSource = remember { MutableInteractionSource() },
                                                        ) {
                                                            roomNameHintFlashTick++
                                                        },
                                                )
                                            }
                                        }
                                        if (!current.allowCustomRoomName) {
                                            Text(
                                                readI18n("multiplayer.room.publishRoomNameHint"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = roomNameHintColor,
                                            )
                                        }
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (modSyncEnabled) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    Icon(
                                                        Icons.Default.Refresh,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                    Text(
                                                        readI18n("multiplayer.room.publishModSyncAutoHint"),
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                }
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                Text(
                                                    readI18n("multiplayer.room.publishSelectType"),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Text(
                                                    if (orderedSelected.isEmpty()) {
                                                        readI18n(
                                                            "multiplayer.room.publishTypeDefault",
                                                            I18nType.RWPP,
                                                            DEFAULT_PUBLISH_ROOM_TYPE,
                                                        )
                                                    } else {
                                                        readI18n(
                                                            "multiplayer.room.publishSelectedTypes",
                                                            I18nType.RWPP,
                                                            orderedSelected.size.toString(),
                                                        )
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Button(
                                                enabled = canPublish,
                                                onClick = {
                                                    onPublish(effectiveRoomType, effectiveRoomName)
                                                },
                                            ) {
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(readI18n("multiplayer.room.publishToList"))
                                            }
                                        }
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            current.types.forEach { type ->
                                                val selected = type in selectedRoomTypes
                                                FilterChip(
                                                    selected = selected,
                                                    onClick = {
                                                        selectedRoomTypes = if (selected) {
                                                            selectedRoomTypes - type
                                                        } else {
                                                            selectedRoomTypes + type
                                                        }
                                                    },
                                                    label = { Text(type) },
                                                    leadingIcon = if (selected) {
                                                        {
                                                            Icon(
                                                                Icons.Default.CheckCircle,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(18.dp),
                                                            )
                                                        }
                                                    } else {
                                                        null
                                                    },
                                                )
                                            }
                                        }
                                    }
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
                if (showExit) ExitButton(onCloseButtonClick)
            }
        }
    }
}


private val RoomPlayerNameWeight = 0.6f

/** 字节数格式化为一位小数 MB（如 1.2）。commonMain 无 String.format，故手写截断到一位小数。 */
private fun fmtMB(bytes: Long): String {
    val tenths = (bytes / 1048576.0 * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
}
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
                    CompactRoomTextButton(
                        readI18n("multiplayer.room.addAI") + if (isDesktop) "(A)" else "",
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = CompactHostButtonMinHeight),
                        onLongClick = onAddAIMany,
                        onClick = onAddAI,
                    )
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
    // 房主视图：该玩家是否有正在进行的 MOD 同步会话（有则在行内展开同步进度条）
    val transfer = if (room.isHost) UI.hostTransferSnapshots.firstOrNull { it.client == player.client } else null

    // 同步中的玩家行边框做呼吸脉冲，一眼可辨「还在传模组」；
    // 脉冲仅在有同步会话时才创建，空闲行不跑无限动画
    val rowBorderColor = if (transfer != null) {
        val borderPulse by rememberInfiniteTransition(label = "room player sync border").animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "room player sync border alpha",
        )
        MaterialTheme.colorScheme.primary.copy(alpha = borderPulse)
    } else {
        MaterialTheme.colorScheme.primary
    }

    Box(modifier) {
        KickPlayerContextMenuAreaMultiplatform(player) {
            Column(
                modifier = Modifier
                    .padding(rowPadding)
                    .border(BorderStroke(2.dp, rowBorderColor), rowShape)
                    .fillMaxWidth()
                    .clickable(room.isHost || room.isHostServer || room.localPlayer == player) {
                        onPlayerClick(player)
                    },
            ) {
                Row(modifier = Modifier.height(IntrinsicSize.Max)) {
                    val baseName = player.name + if (player.startingUnit != -1) {
                        " - ${options.firstOrNull { it.first == player.startingUnit }?.second ?: "Unknown"}"
                    } else ""
                    TableCell(
                        baseName,
                        color = if (player.color != -1) {
                            Player.getTeamColor(player.color)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        weight = RoomPlayerNameWeight,
                        drawStroke = false,
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        // 未就绪（等待装载/就绪）且尚无同步会话：显示不确定进度环；
                        // 有同步会话时由行内进度条承载进度，名称格保持干净
                        if (transfer == null && !player.data.ready) {
                            // Box 纵向 fillMaxHeight + 居中，修复原实现里小圆圈在格内偏上、视觉中心偏离的问题
                            Box(
                                modifier = Modifier.fillMaxHeight().padding(end = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(if (compact) 12.dp else 16.dp),
                                    strokeWidth = if (compact) 1.5.dp else 2.dp,
                                )
                            }
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
                if (transfer != null) {
                    RoomModSyncStrip(transfer = transfer, compact = compact)
                }
            }
        }
    }
}

/**
 * 房主视角的行内 MOD 同步进度条（玩家行第二行）。
 * 构成：下载图标 + 当前模组名 + 渐变进度条（流光扫过 + 端点光斑）+ 百分比 + 模组计数徽章；
 * 宽屏额外显示「已传/总量 MB」。进度按当前模组口径（含断点续传免发字节）。
 */
@Composable
private fun RoomModSyncStrip(transfer: HostTransferSnapshot, compact: Boolean) {
    val progress = if (transfer.totalBytes > 0) {
        (transfer.currentModProgressBytes.toFloat() / transfer.totalBytes).coerceIn(0f, 1f)
    } else 0f
    // 快照每 200ms 轮询一次，用 220ms 过渡让进度走动连续
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(220),
        label = "room mod sync progress",
    )
    val percent = (progress * 100).roundToInt()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (compact) 8.dp else 12.dp,
                end = if (compact) 8.dp else 12.dp,
                bottom = if (compact) 4.dp else 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp),
    ) {
        ModSyncDownloadGlyph(
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(if (compact) 9.dp else 11.dp),
        )
        Text(
            transfer.currentModName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!compact) {
            Text(
                "${fmtMB(transfer.currentModProgressBytes)}/${fmtMB(transfer.totalBytes)}MB",
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        ModSyncProgressBar(
            progress = animatedProgress,
            modifier = Modifier
                .weight(if (compact) 0.9f else 1.2f)
                .height(if (compact) 4.dp else 5.dp),
        )
        Text(
            "$percent%",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.widthIn(min = if (compact) 26.dp else 32.dp),
        )
        // 模组计数徽章：当前第几个 / 共几个
        Text(
            "${transfer.modIndex + 1}/${transfer.modCount}",
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f))
                .padding(horizontal = if (compact) 4.dp else 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
        )
    }
}

/** 细圆角渐变进度条：轨道 + 主色渐变填充 + 流光扫过 + 进度端点光斑。 */
@Composable
private fun ModSyncProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainer
    val fillStartColor = MaterialTheme.colorScheme.inversePrimary
    val fillEndColor = MaterialTheme.colorScheme.primary
    val shimmer by rememberInfiniteTransition(label = "room mod sync shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "room mod sync shimmer offset",
    )

    Canvas(modifier = modifier) {
        val radius = size.height / 2f
        drawRoundRect(color = trackColor, cornerRadius = CornerRadius(radius, radius))

        val fillWidth = size.width * progress.coerceIn(0f, 1f)
        if (fillWidth > 0f) {
            val fillPath = Path().apply {
                addRoundRect(RoundRect(0f, 0f, fillWidth, size.height, CornerRadius(radius, radius)))
            }
            clipPath(fillPath) {
                // 渐变铺满整条轨道（而非仅填充区），保证任意进度下色相一致
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(fillStartColor, fillEndColor),
                        startX = 0f,
                        endX = size.width,
                    ),
                    cornerRadius = CornerRadius(radius, radius),
                )
                // 流光：一条斜向半透明白带反复扫过已填充区域
                val bandWidth = size.height * 2.5f
                val sweep = shimmer * (fillWidth + bandWidth * 2f) - bandWidth
                val band = Path().apply {
                    moveTo(sweep, size.height)
                    lineTo(sweep + bandWidth * 0.5f, 0f)
                    lineTo(sweep + bandWidth, 0f)
                    lineTo(sweep + bandWidth * 0.5f, size.height)
                    close()
                }
                drawPath(band, Color.White.copy(alpha = 0.16f))
            }
            // 端点光斑，强化「正在前进」的视觉锚点
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = radius * 0.45f,
                center = Offset((fillWidth - radius).coerceAtLeast(radius), size.height / 2f),
            )
        }
    }
}

/** 手绘下载图标（箭杆 + 两翼 + 托盘线）；material-icons-core 无下载图标，Canvas 绘制更贴合圆角细线风格。 */
@Composable
private fun ModSyncDownloadGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = minOf(w, h) * 0.16f
        val cx = w / 2f
        // 箭杆
        drawLine(
            color = tint,
            start = Offset(cx, h * 0.06f),
            end = Offset(cx, h * 0.52f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        // 箭头两翼
        val head = Path().apply {
            moveTo(cx - w * 0.26f, h * 0.38f)
            lineTo(cx, h * 0.64f)
            lineTo(cx + w * 0.26f, h * 0.38f)
        }
        drawPath(
            head,
            tint,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // 托盘
        drawLine(
            color = tint,
            start = Offset(w * 0.10f, h * 0.88f),
            end = Offset(w * 0.90f, h * 0.88f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
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
