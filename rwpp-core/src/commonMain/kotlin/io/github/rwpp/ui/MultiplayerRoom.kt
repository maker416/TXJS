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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import io.github.rwpp.net.sync.SyncPeerPhase
import io.github.rwpp.net.sync.SyncPeerSnapshot
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Precision
import io.github.rwpp.AppContext
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.RoomIdentityController
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.PublishedRoomInfo
import io.github.rwpp.config.Settings
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.event.events.PlayerJoinEvent
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
import io.github.rwpp.net.MOD_SYNC_ROOM_TYPE
import io.github.rwpp.net.Net
import io.github.rwpp.net.account.RoomInvite
import io.github.rwpp.net.account.isPlausibleJoinAddress
import io.github.rwpp.net.composePublishRoomType
import io.github.rwpp.net.roomListPublishAddress
import io.github.rwpp.config.DEFAULT_ROOM_LIST_API_URLS
import com.eclipsesource.json.Json
import io.github.rwpp.core.ModSyncController
import io.github.rwpp.io.SizeUtils
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.platform.KickPlayerContextMenuAreaMultiplatform
import io.github.rwpp.projectVersion
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.group_30
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
import org.jetbrains.compose.resources.painterResource
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
            // 离房重置邀请策略（无论房主还是成员）
            RoomInvitePolicy.reset()
            // 离房（含被踢）停止房间身份公示并撤销服务端记录
            RoomIdentityController.stopPublishing()
        }
    }

    val externalHandler = koinInject<ExternalHandler>()
    val game = koinInject<Game>()
    val net = koinInject<Net>()
    val room = game.gameRoom

    // 房主禁止成员邀请期间，向每个新进房的真人玩家补播控制消息（其进房前的广播收不到）
    GlobalEventChannel.filter(PlayerJoinEvent::class).onDispose {
        subscribeAlways(Dispatchers.Main.immediate) {
            if ((room.isHost || room.isHostServer) && !RoomInvitePolicy.membersCanInvite &&
                !it.player.isAI && it.player != room.localPlayer
            ) {
                room.sendChatMessageOrCommand(RoomInvitePolicy.controlMessage(false))
            }
        }
    }

    var update by remember { mutableStateOf(false) }
    var lastSelectedIndex by remember { mutableIntStateOf(0) }
    var selectedMap by remember(update) { mutableStateOf(room.selectedMap) }
    val displayMapName = remember(update) { room.displayMapName }

    var optionVisible by remember { mutableStateOf(false) }
    var banUnitVisible by remember { mutableStateOf(false) }
    var showForceStartConfirm by remember { mutableStateOf(false) }
    var inviteDialogVisible by remember { mutableStateOf(false) }
    var showUnsyncedInRoomBlock by remember { mutableStateOf(false) }
    var unsyncedInRoomNames by remember { mutableStateOf(listOf<String>()) }
    var publishState by remember { mutableStateOf<PublishToListUiState>(PublishToListUiState.Hidden) }
    var pendingPublishRoomType by remember { mutableStateOf<String?>(null) }
    var pendingPublishRoomName by remember { mutableStateOf<String?>(null) }
    var roomIdForPublish by remember { mutableStateOf<String?>(null) }
    var publishJob by remember { mutableStateOf<Job?>(null) }
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
    /** 当前用户是否可发起邀请：房主始终可以；成员取决于房主广播的邀请策略。 */
    val canInvite = isHost || RoomInvitePolicy.membersCanInvite

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


// （旧协议内同步的残留占位已随重构移除）
    val players = remember(update) { room.getPlayers().forRoomPlayerList() }
    var selectedPlayer by remember { mutableStateOf(players.firstOrNull() ?: ConnectingPlayer) }
    var playerOverrideVisible by remember { mutableStateOf(false) }
    var playerCardVisible by remember { mutableStateOf(false) }

    LaunchedEffect(selectedPlayer) {
        UI.roomSelectedPlayer = selectedPlayer
    }

    PlayerOverrideDialog(
        playerOverrideVisible,
        { playerOverrideVisible = false },
        updateAction,
        room,
        extensions,
        selectedPlayer,
        onViewProfile = { player ->
            selectedPlayer = player
            playerOverrideVisible = false
            playerCardVisible = true
        },
    )

    PlayerCardDialog(
        visible = playerCardVisible,
        player = selectedPlayer,
        room = room,
        onDismiss = { playerCardVisible = false },
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
                    ModSyncController.bindPublishedServerId(serverId)
                    // 发布到列表后补充 sid 别名 key（内部立即重发一次 publish）
                    RoomIdentityController.addServerIdKey(serverId)
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
                val canTransferMod = ModSyncController.hostSyncRequested
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
        publishState = PublishToListUiState.Hidden
    }

    fun publishToList(roomId: String) {
        if (isPublishing || hasPublishedInfo) return
        launchPublish { runPublishFlow(roomId) }
    }

    PublishToListDialog(
        state = publishState,
        defaultRoomName = roomIdForPublish?.let { "公开房-$it" } ?: "",
        onDismiss = {
            publishState = PublishToListUiState.Hidden
        },
        onCancel = ::cancelPublishRequest,
        onCloseButtonClick = {
            publishState = PublishToListUiState.Hidden
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
        // 房主开启「传输模组」时：拿到短码即启动带外同步会话（内部幂等）
        roomIdForPublish?.let { code ->
            if (room.isHost) ModSyncController.startHostSession(code)
            // 短码就绪：补充身份公示 key 并立即重发（内部幂等）
            RoomIdentityController.addRoomCodeKey(code)
        }
        // 单人/沙盒复用本视图，不做身份公示
        if (!room.isSinglePlayerGame) RoomIdentityController.startPublishing()
    }

    @Composable
    fun ContentView() {
        val globalFocusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        val interactionSource = remember { MutableInteractionSource() }
        val isDesktop = remember { appKoin.get<AppContext>().isDesktop() }
        val isCompact = LocalWindowManager.current != WindowManager.Large

        val openPlayerCard: (Player) -> Unit = { player ->
            selectedPlayer = player
            playerCardVisible = true
        }

        val onPlayerClick: (Player) -> Unit = { player ->
            selectedPlayer = player
            // 房主/主机/点自己：沿用玩家配置弹窗；其余真人玩家：打开个人名片
            if (room.isHost || room.isHostServer || room.localPlayer == player) {
                playerOverrideVisible = true
            } else {
                playerCardVisible = true
            }
        }

        val startGame: () -> Unit = {
            val unpreparedPlayers = game.gameRoom.getPlayers().filter { !it.data.ready }
            val syncingPeers = ModSyncController.hostPeerSnapshots.filter { it.phase != SyncPeerPhase.SYNCED }
            val connectedNames = game.gameRoom.getPlayers().map { it.name }.toSet()
            // 已在房内但仍在下载/重载的玩家：现在开局必不同步（开局时服务端推送单位表，缺单位即断连），硬阻断
            unsyncedInRoomNames = syncingPeers.filter { it.displayName in connectedNames }.map { it.displayName }
            when {
                unsyncedInRoomNames.isNotEmpty() -> showUnsyncedInRoomBlock = true
                syncingPeers.isNotEmpty() -> showForceStartConfirm = true
                unpreparedPlayers.isNotEmpty() -> {
                    UI.showWarning(
                        readI18n(
                            "multiplayer.room.playersNotReadyModSync",
                            I18nType.RWPP,
                            unpreparedPlayers.joinToString(", ") { it.name },
                        ),
                    )
                }
                room.isHostServer -> room.sendQuickGameCommand("-start")
                else -> room.startGame()
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

                    ModSyncHostStatusBar(
                        isHost = isHost,
                        compact = true,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    RoomSelfSyncBar(
                        compact = true,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )

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
                                        onViewProfile = openPlayerCard,
                                        onInvite = if (!isSandboxGame && canInvite) {
                                            { inviteDialogVisible = true }
                                        } else {
                                            null
                                        },
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
                                        membersCanInvite = RoomInvitePolicy.membersCanInvite,
                                        onInvitePolicyToggle = {
                                            RoomInvitePolicy.setByHost(room, !RoomInvitePolicy.membersCanInvite)
                                        },
                                        isDesktop = isDesktop,
                                        // Q 房本身已公开，无需再走 RWList「公开到列表」
                                        showPublishButton = roomIdForPublish.let { id ->
                                            id != null && !id.startsWith('Q') && !isPublishing && !hasPublishedInfo
                                        },
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
                                        onViewProfile = openPlayerCard,
                                        onInvite = if (!isSandboxGame && canInvite) {
                                            { inviteDialogVisible = true }
                                        } else {
                                            null
                                        },
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
                                        membersCanInvite = RoomInvitePolicy.membersCanInvite,
                                        onInvitePolicyToggle = {
                                            RoomInvitePolicy.setByHost(room, !RoomInvitePolicy.membersCanInvite)
                                        },
                                        isDesktop = isDesktop,
                                        showPublishButton = roomIdForPublish.let { id ->
                                            id != null && !id.startsWith('Q') && !isPublishing && !hasPublishedInfo
                                        },
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
                    ModSyncHostStatusBar(
                        isHost = isHost,
                        compact = false,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    RoomSelfSyncBar(
                        compact = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    )
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
                                    // Q 房本身已公开，无需再走 RWList「公开到列表」
                                    showPublishButton = roomIdForPublish.let { id ->
                                        id != null && !id.startsWith('Q') && !isPublishing && !hasPublishedInfo
                                    },
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
                                                key = { roomPlayerListKey(players[it], it) },
                                            ) { index ->
                                                RoomPlayerTableRow(
                                                    player = players[index],
                                                    room = room,
                                                    game = game,
                                                    update = update,
                                                    onPlayerClick = onPlayerClick,
                                                    onViewProfile = openPlayerCard,
                                                    modifier = if (koinInject<Settings>().enableAnimations) {
                                                        Modifier.animateItem()
                                                    } else {
                                                        Modifier
                                                    },
                                                )
                                            }
                                            // 空槽位：追加最多 2 个「邀请好友」占位行
                                            if (!isSandboxGame && canInvite && players.size < room.maxPlayerCount) {
                                                items(
                                                    count = (room.maxPlayerCount - players.size).coerceAtMost(2),
                                                    key = { "invite_slot_$it" },
                                                ) {
                                                    RoomInviteSlotRow(onClick = { inviteDialogVisible = true })
                                                }
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
                                    // 锁房开关：成员视角为只读状态展示（enabled = isHost，无阴影降透明度）
                                    RoomToggleChip(
                                        label = readI18n("multiplayer.room.lockRoom", I18nType.RWPP),
                                        checked = isLocked,
                                        checkedColor = Color(237, 112, 20),
                                        onClick = {
                                            isLocked = !isLocked
                                            room.lockedRoom = isLocked
                                        },
                                        enabled = isHost,
                                        modifier = Modifier
                                            .padding(horizontal = 5.dp)
                                            .align(Alignment.CenterVertically),
                                    ) {
                                        Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp))
                                    }
                                }

                                if (!isSandboxGame && isHost) {
                                    // 房主邀请策略开关：禁止后广播控制消息，成员端隐藏邀请入口
                                    RoomToggleChip(
                                        label = readI18n("multiplayer.room.memberInvites", I18nType.RWPP),
                                        checked = RoomInvitePolicy.membersCanInvite,
                                        onClick = {
                                            RoomInvitePolicy.setByHost(room, !RoomInvitePolicy.membersCanInvite)
                                        },
                                        modifier = Modifier
                                            .padding(horizontal = 5.dp)
                                            .align(Alignment.CenterVertically),
                                    ) {
                                        Icon(
                                            painterResource(Res.drawable.group_30),
                                            null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }

                                if (!isSandboxGame && canInvite) {
                                    RWTextButton(
                                        readI18n("multiplayer.room.invite", I18nType.RWPP),
                                        modifier = Modifier.padding(
                                            horizontal = 5.dp,
                                            vertical = 30.dp,
                                        ),
                                    ) { inviteDialogVisible = true }
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

    /**
     * 以当前房间快照生成邀请负载；无法确定可加入地址时返回 null。
     * 地址优先取当前房间短码（roomDetails 的 [QR]\d+，与「加入上次游戏」的 R 码直连同先例）；
     * 无短码才回退 lastNetworkIP，且必须像 host:port/IP——快速建房指令（Qnews 等）会被拒绝。
     */
    fun buildRoomInvite(): RoomInvite? {
        val code = roomIdForPublish
        val lastIp = configIO.getGameConfig<String?>("lastNetworkIP").orEmpty()
        val address = when {
            code != null -> code
            isPlausibleJoinAddress(lastIp) -> lastIp.trim()
            else -> return null
        }
        return RoomInvite(
            address = address,
            code = code,
            inviter = AccountSession.displayName.ifBlank {
                configIO.getGameConfig<String?>("lastNetworkPlayerName").orEmpty()
            },
            map = displayMapName,
            players = "${players.size}/${room.maxPlayerCount}",
            mods = room.mods.size,
            version = projectVersion,
            invitedAt = System.currentTimeMillis(),
        )
    }

    InviteFriendsDialog(
        visible = inviteDialogVisible,
        invite = if (inviteDialogVisible) buildRoomInvite() else null,
        onGoLogin = { UI.showAccountView = true },
        onSent = { names ->
            // 在房间聊天里告知房内其他玩家（以自己的名义发言，文案标明是邀请）
            if (names.isNotEmpty()) {
                room.sendChatMessageOrCommand(
                    readI18n(
                        "multiplayer.room.inviteSent", I18nType.RWPP,
                        names.joinToString(readI18n("common.listSeparator", I18nType.RWPP)),
                    )
                )
            }
        },
        onDismiss = { inviteDialogVisible = false },
    )

    val forceStartPeers = ModSyncController.hostPeerSnapshots.filter { it.phase != SyncPeerPhase.SYNCED }
    AnimatedAlertDialog(
        visible = showForceStartConfirm,
        onDismissRequest = { showForceStartConfirm = false },
    ) { dismiss ->
        BorderCard(
            modifier = Modifier
                .fillMaxWidth(LargeProportion())
                .widthIn(max = 480.dp)
                .padding(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    readI18n("multiplayer.room.forceStartTitle"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    readI18n(
                        "multiplayer.room.forceStartMessage",
                        I18nType.RWPP,
                        forceStartPeers.joinToString(", ") { it.displayName },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    RWTextButton(readI18n("common.cancel")) {
                        dismiss()
                    }
                    RWTextButton(readI18n("modSync.clearPeers")) {
                        ModSyncController.clearHostPeers()
                        dismiss()
                    }
                    RWTextButton(readI18n("multiplayer.room.forceStartConfirm")) {
                        dismiss()
                        if (room.isHostServer) room.sendQuickGameCommand("-start")
                        else room.startGame()
                    }
                }
            }
        }
    }

    // 房内仍有玩家同步模组时的硬阻断：开局必导致他们不同步断连，只允许踢出后开局或取消等待
    AnimatedAlertDialog(
        visible = showUnsyncedInRoomBlock,
        onDismissRequest = { showUnsyncedInRoomBlock = false },
    ) { dismiss ->
        BorderCard(
            modifier = Modifier
                .fillMaxWidth(LargeProportion())
                .widthIn(max = 480.dp)
                .padding(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    readI18n("multiplayer.room.unsyncedInRoomTitle"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    readI18n(
                        "multiplayer.room.unsyncedInRoomMessage",
                        I18nType.RWPP,
                        unsyncedInRoomNames.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    RWTextButton(readI18n("common.cancel")) {
                        dismiss()
                    }
                    RWTextButton(readI18n("multiplayer.room.kickAndStart")) {
                        dismiss()
                        val names = unsyncedInRoomNames.toSet()
                        game.gameRoom.getPlayers()
                            .filter { it.name in names }
                            .forEach { room.kickPlayer(it) }
                        // 清掉残留 Presence，避免被踢者的同步记录继续触发门控
                        ModSyncController.clearHostPeers()
                        if (room.isHostServer) room.sendQuickGameCommand("-start")
                        else room.startGame()
                    }
                }
            }
        }
    }
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
    onViewProfile: ((Player) -> Unit)? = null,
) {
    val game = koinInject<Game>()

    AnimatedAlertDialog(
        visible, onDismissRequest = { onDismissRequest(); update() }
    ) { dismiss ->

        // 模组初始单位预设在模组加载/重载后才注册到引擎，
        // 必须在弹窗打开时实时拉取（remember 无 key 会在进房时固化成旧列表）
        val items = remember(player) {
            buildList {
                add(-1 to "Default")
                addAll(game.getStartingUnitOptions())
            }
        }

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

                // 真人玩家可查看名片（个人资料 / 加好友）
                if (onViewProfile != null && !player.isAI && player != ConnectingPlayer)
                    RWTextButton(readI18n("playerCard.viewProfile", I18nType.RWPP), Modifier.padding(5.dp)) {
                        dismiss()
                        onViewProfile(player)
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
    var incomeMultiplierText by remember { mutableStateOf(room.incomeMultiplier.toString()) }
    var gameSpeed by remember { mutableStateOf(room.gameSpeed) }
    var teamUnitCapHostedGame by remember {
        mutableStateOf(configIO.getGameConfig<Int?>("teamUnitCapHostedGame"))
    }

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
    // 下拉索引必须挂在对话框层：LazyColumn item 滚出视口会 dispose remember，
    // 若在 item 内用 room.* 重建，会把尚未 apply 的修改打回默认值。
    val selectedDifficulty = (aiDifficulty + 2).coerceAtMost(Difficulty.entries.size - 1)
    val selectedFog = fogMode.ordinal
    val selectedStartingUnit =
        startingOptionList.indexOfFirst { it.first == startingUnits }.coerceAtLeast(0)
    val selectedTeam = teamMode?.let { teamModes.indexOf(it) + 1 } ?: 0

    BorderCard(
        modifier = Modifier
            .fillMaxWidth(LargeProportion())
            .fillMaxHeight(0.88f),
    ) {
        Text(
            readI18n(if (room.isHost) "multiplayer.room.option" else "multiplayer.room.viewOption"),
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
                    MultiplayerOption(
                        readI18n("multiplayer.room.noNukes"),
                        noNukes,
                        room.isHost,
                    ) { noNukes = it }
                    MultiplayerOption(
                        readI18n("multiplayer.room.sharedControl"),
                        sharedControl,
                        room.isHost,
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
                    LargeDropdownMenu(
                        modifier = Modifier.weight(1f),
                        enabled = room.isHost,
                        label = readI18n("common.difficulty"),
                        items = Difficulty.entries,
                        selectedIndex = selectedDifficulty,
                        selectedItemToString = ::difficultyDisplayName,
                        onItemSelected = { index, _ -> aiDifficulty = index - 2 },
                    )

                    LargeDropdownMenu(
                        modifier = Modifier.weight(1f),
                        enabled = room.isHost,
                        label = readI18n("common.fog"),
                        items = FogMode.entries,
                        selectedIndex = selectedFog,
                        selectedItemToString = ::fogModeDisplayName,
                        onItemSelected = { index, _ -> fogMode = FogMode.entries[index] },
                    )
                }
            }

            item {
                RoomOptionFieldRow {
                    LargeDropdownMenu(
                        modifier = Modifier.weight(1f),
                        enabled = room.isHost,
                        label = readI18n("multiplayer.room.startingUnit"),
                        items = startingOptionList,
                        selectedIndex = selectedStartingUnit,
                        selectedItemToString = { (_, s) -> localizeStartingUnitOption(s) },
                        onItemSelected = { index, _ ->
                            startingUnits = startingOptionList[index].first
                        },
                    )

                    LargeDropdownMenu(
                        modifier = Modifier.weight(1f),
                        enabled = room.isHost,
                        label = readI18n("multiplayer.room.startingCredits"),
                        items = startingCreditLabels,
                        selectedIndex = startingCredits,
                        onItemSelected = { index, _ -> startingCredits = index },
                    )
                }
            }

            item {
                RoomOptionFieldRow {
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
                            teamMode = if (index == 0) null else teamModes[index - 1]
                        },
                    )

                    var incomeExpanded by remember { mutableStateOf(false) }
                    RWSingleOutlinedTextField(
                        readI18n("multiplayer.room.incomeMultiplier"),
                        incomeMultiplierText,
                        lengthLimitCount = 5,
                        typeInNumberOnly = true,
                        enabled = room.isHost,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            val icon =
                                if (incomeExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
                            Icon(
                                icon,
                                "",
                                modifier = Modifier.clickable(!incomeExpanded && room.isHost) { incomeExpanded = !incomeExpanded },
                            )
                        },
                        appendedContent = {
                            BasicDropdownMenu(
                                incomeExpanded,
                                listOf(1f, 1.5f, 2f, 2.5f, 3f, 10f),
                                onItemSelected = { _, v ->
                                    incomeMultiplierText = v.toString()
                                    realIncomeMultiplier = v
                                },
                            ) {
                                incomeExpanded = false
                            }
                        },
                    ) {
                        incomeMultiplierText = it
                        realIncomeMultiplier = it.toFloatOrNull() ?: 1f
                    }
                }
            }

            item {
                RoomOptionFieldRow {
                    var unitCapExpanded by remember { mutableStateOf(false) }
                    LaunchedEffect(teamUnitCapHostedGame) {
                        // 单位上限是房主本地配置，加入者只读查看时不落盘、不下发
                        if (room.isHost) {
                            val count = teamUnitCapHostedGame ?: 100
                            configIO.setGameConfig("teamUnitCapHostedGame", count)
                            game.setTeamUnitCapHostGame(count)
                        }
                    }
                    if (room.isHost) {
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
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                    Text(
                        "${readI18n("multiplayer.room.maxPlayer")}：$maxPlayerCount",
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Slider(
                        valueRange = players.size.toFloat()..100f,
                        modifier = Modifier.fillMaxWidth(),
                        value = maxPlayerCount.toFloat(),
                        enabled = room.isHost,
                        colors = RWSliderColors,
                        onValueChange = {
                            maxPlayerCount = it.roundToInt().coerceAtLeast(players.size.coerceAtLeast(10))
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
            if (room.isHost) {
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
            } else {
                RWTextButton(readI18n("common.close")) {
                    dismiss()
                }
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


/**
 * 引擎玩家数组里 [Player.connectHexId]（Android `p.S` / 桌面 `n.O`）并不保证唯一：
 * 缺省值、握手占位与正式槽位可能共用同一把 SHA-256。LazyColumn 只用它当 key，
 * 第二人进房刷新列表就会 `Key was already used` 把房主和加入者一起崩掉。
 */
private fun List<Player>.forRoomPlayerList(): List<Player> =
    distinctBy { System.identityHashCode(it) }.sortedBy { it.team }

private fun roomPlayerListKey(player: Player, index: Int): String =
    "${player.spawnPoint}\u0000${player.name}\u0000${player.connectHexId}\u0000$index"

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
    onInvite: (() -> Unit)? = null,
    onViewProfile: ((Player) -> Unit)? = null,
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
                    key = { roomPlayerListKey(players[it], it) },
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
                        onViewProfile = onViewProfile,
                        modifier = if (enableAnimations) {
                            Modifier.animateItem()
                        } else {
                            Modifier
                        },
                    )
                }
                // 空槽位：追加最多 2 个「邀请好友」占位行
                if (onInvite != null && players.size < room.maxPlayerCount) {
                    items(
                        count = (room.maxPlayerCount - players.size).coerceAtMost(2),
                        key = { "invite_slot_$it" },
                    ) {
                        RoomInviteSlotRow(
                            onClick = onInvite,
                            compact = true,
                            rowShape = rowShape,
                        )
                    }
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
    membersCanInvite: Boolean,
    onInvitePolicyToggle: () -> Unit,
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
            // 房间管理开关条：锁房 + 成员邀请。设置项式布局（图标 + 功能名 + 尾部开关指示），
            // 与下方操作按钮同款的按钮外观明确可点击；开关位置/颜色即当前状态
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RoomToggleChip(
                    label = readI18n("multiplayer.room.lockRoom", I18nType.RWPP),
                    checked = isLocked,
                    checkedColor = Color(237, 112, 20),
                    onClick = onLockToggle,
                    compact = true,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(14.dp))
                }
                RoomToggleChip(
                    label = readI18n("multiplayer.room.memberInvites", I18nType.RWPP),
                    checked = membersCanInvite,
                    onClick = onInvitePolicyToggle,
                    compact = true,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(painterResource(Res.drawable.group_30), null, modifier = Modifier.size(14.dp))
                }
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
        } else {
            // 加入者入口：只读查看房间设置
            CompactRoomTextButton(
                readI18n("multiplayer.room.viewOption") + if (isDesktop) "(O)" else "",
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = CompactHostButtonMinHeight),
                onClick = onOption,
            )
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
        } else {
            // 加入者入口：只读查看房间设置
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                RWTextButton(
                    readI18n("multiplayer.room.viewOption") + if (isDesktop) "(O)" else "",
                    modifier = Modifier.padding(5.dp),
                    onClick = onOption,
                )
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

/**
 * 迷你开关指示器：纯视觉（不响应点击），嵌在 [RoomToggleChip] 尾部模仿 Switch 的开/关态，
 * 让用户一眼看出条目是可切换的开关以及当前状态。点击由整条目的 bounceClick 统一处理。
 */
@Composable
private fun MiniToggleSwitch(
    checked: Boolean,
    checkedColor: Color,
    modifier: Modifier = Modifier,
) {
    val thumbOffset by animateDpAsState(if (checked) 12.dp else 0.dp)
    val trackColor by animateColorAsState(
        if (checked) {
            checkedColor.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    )
    val thumbColor by animateColorAsState(
        if (checked) {
            checkedColor
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        }
    )
    Box(
        modifier = modifier
            .size(width = 28.dp, height = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(trackColor)
            .border(BorderStroke(1.dp, thumbColor.copy(alpha = 0.6f)), RoundedCornerShape(8.dp)),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 2.dp)
                .offset(x = thumbOffset)
                .size(12.dp)
                .clip(CircleShape)
                .background(thumbColor),
        )
    }
}

/**
 * 房间状态开关条目：按钮外观（描边/阴影/按压回弹）+ 尾部 [MiniToggleSwitch] 开关指示，
 * 设置项式布局（图标 + 功能名 + 开关），一眼可知可点击且是开/关切换。
 * [checked] 为开关态（锁房=已锁定、成员邀请=允许邀请），ON 时滑块着 [checkedColor]；
 * [enabled] 为 false 时仅展示状态（如成员视角的锁房状态）：去阴影、降透明度、不可点击。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomToggleChip(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checkedColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    compact: Boolean = false,
    icon: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalRippleConfiguration provides RippleConfiguration(Color.Transparent, RippleAlpha(0f, 0f, 0f, 0f)),
    ) {
        Card(
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.surfaceContainer),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = if (enabled) 4.dp else 0.dp),
            modifier = modifier.then(
                if (enabled) {
                    Modifier.bounceClick(onClick = onClick)
                } else {
                    Modifier.alpha(0.65f)
                }
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = if (compact) 5.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                ) {
                    icon()
                }
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = if (compact) {
                        MaterialTheme.typography.labelSmall
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MiniToggleSwitch(checked = checked, checkedColor = checkedColor)
            }
        }
    }
}

/** 玩家列表中的「邀请好友」空槽位行：加号图标 + 灰字，点击打开邀请弹窗。 */
@Composable
private fun RoomInviteSlotRow(
    onClick: () -> Unit,
    compact: Boolean = false,
    rowShape: Shape = CircleShape,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(if (compact) 2.dp else 5.dp)
            .border(
                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                rowShape,
            )
            .clip(rowShape)
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(vertical = if (compact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
            Text(
                readI18n("multiplayer.room.inviteFriends", I18nType.RWPP),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
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
    onViewProfile: ((Player) -> Unit)? = null,
) {
    // 跟随房间刷新重建，避免模组预设变化后玩家初始单位后缀显示 Unknown
    val options = remember(update) { game.getStartingUnitOptions() }
    val rowPadding = if (compact) 2.dp else 5.dp
    Box(modifier) {
        KickPlayerContextMenuAreaMultiplatform(player, onViewProfile = onViewProfile) {
            Row(
                modifier = Modifier
                    .height(IntrinsicSize.Max)
                    .padding(rowPadding)
                    .border(
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        rowShape,
                    )
                    .fillMaxWidth()
                    // 任意真人玩家可点（个人名片）；房主/主机/点自己沿用玩家配置弹窗；AI 仅房主可点
                    .clickable(
                        room.isHost || room.isHostServer || room.localPlayer == player ||
                            (!player.isAI && player != ConnectingPlayer)
                    ) {
                        onPlayerClick(player)
                    },
            ) {
                val baseName = player.name + if (player.startingUnit != -1) {
                    " - ${options.firstOrNull { it.first == player.startingUnit }?.second ?: "Unknown"}"
                } else ""
                RoomPlayerNameCell(
                    name = baseName,
                    nameColor = if (player.color != -1) {
                        Player.getTeamColor(player.color)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    syncPeer = ModSyncController.roomPeerBadges[player.name],
                    showReadySpinner = !player.data.ready,
                    compact = compact,
                )
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
        // 新消息 prepend 在文本开头，光标（决定滚动位置）须放在起点，
        // 否则每条新消息都会把列表滚动到最旧的消息处。
        value = TextFieldValue(
            annotatedString = chatMessages,
            selection = TextRange.Zero,
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


/**
 * 房主视角的模组同步状态条：仅在开启「传输模组」且为房主时显示。
 * 状态由 [ModSyncController.hostSyncState] 驱动，上传中显示进度，就绪/失败常显。
 */
@Composable
private fun ModSyncHostStatusBar(
    isHost: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (!isHost) return
    val state = ModSyncController.hostSyncState
    if (state is ModSyncController.HostSyncState.Off) return
    val hasUnsyncedPeers = ModSyncController.hostPeerSnapshots.any { it.phase != SyncPeerPhase.SYNCED }

    val (text, color) = when (state) {
        is ModSyncController.HostSyncState.Preparing -> readI18n(
            "modSync.statusUploading",
            I18nType.RWPP,
            state.doneCount.toString(),
            state.totalCount.toString(),
            SizeUtils.byteToMB(state.uploadedBytes).toString(),
            SizeUtils.byteToMB(state.totalBytes).toString(),
        ) to MaterialTheme.colorScheme.primary

        ModSyncController.HostSyncState.Ready ->
            readI18n("modSync.statusReady", I18nType.RWPP) to Color(0xFF4CAF50)

        is ModSyncController.HostSyncState.Error -> readI18n(
            "modSync.statusError",
            I18nType.RWPP,
            state.message,
        ) to MaterialTheme.colorScheme.error

        ModSyncController.HostSyncState.Off -> return
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state is ModSyncController.HostSyncState.Preparing) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hasUnsyncedPeers) {
            RWTextButton(
                readI18n("modSync.clearPeers"),
                modifier = Modifier.defaultMinSize(minHeight = if (compact) 28.dp else 32.dp),
            ) {
                ModSyncController.clearHostPeers()
            }
        }
    }
}

/**
 * 加入者视角的进房后同步状态条：等待房主/下载/重载/已同步各阶段一行展示，
 * 下载中带确定进度条与当前 mod 名；应用中显示引擎已加载单位数（与重载弹窗同口径）；
 * 未同步完成前可点「取消同步」中止并退出房间。
 * 由 [ModSyncController.inRoomSyncPhase]、[loadingMessage] 与 [UI.receivingModName] 等状态驱动。
 */
@Composable
private fun RoomSelfSyncBar(compact: Boolean, modifier: Modifier = Modifier) {
    val phase = ModSyncController.inRoomSyncPhase ?: return
    val isSynced = phase == SyncPeerPhase.SYNCED
    val isDownloading = phase == SyncPeerPhase.DOWNLOADING
    val isApplying = phase == SyncPeerPhase.APPLYING
    val applyProgress = if (isApplying) parseEngineLoadProgress(loadingMessage) else null
    val phaseLabel = when (phase) {
        SyncPeerPhase.WAITING_HOST -> readI18n("modSync.phaseWaitingHost", I18nType.RWPP)
        SyncPeerPhase.DOWNLOADING -> readI18n("modSync.phaseDownloading", I18nType.RWPP)
        SyncPeerPhase.APPLYING -> applyingPhaseLabel(applyProgress?.detail)
        SyncPeerPhase.SYNCED -> readI18n("modSync.phaseSynced", I18nType.RWPP)
        else -> readI18n("modSync.phaseJoining", I18nType.RWPP)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSynced) {
            Text(
                "✓",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF4CAF50),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val speedBps = UI.receivingModSpeedBps
        val speedText = if (isDownloading && speedBps > 0L) " ${fmtSpeed(speedBps)}" else ""
        val detailText = if (isDownloading && UI.receivingModName.isNotBlank()) {
            "$phaseLabel: ${UI.receivingModName} " +
                "(${fmtMB(UI.receivingModReceivedBytes)}/${fmtMB(UI.receivingModTotalBytes)}MB)$speedText"
        } else {
            phaseLabel
        }
        Text(
            detailText,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSynced) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isApplying && applyProgress != null) {
            ApplyingUnitCountBadge(count = applyProgress.count, compact = compact)
        }
        if (isDownloading) {
            LinearProgressIndicator(
                progress = { UI.receivingModProgress },
                modifier = Modifier
                    .width(if (compact) 64.dp else 96.dp)
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainer,
            )
        }
        if (!isSynced) {
            RWTextButton(
                readI18n("modSync.cancelSync"),
                modifier = Modifier.defaultMinSize(minHeight = 28.dp),
            ) {
                ModSyncController.cancelInRoomSync()
            }
        }
    }
}

/** 字节数格式化为一位小数 MB（如 1.2）。commonMain 无 String.format，故手写截断到一位小数。 */
private fun fmtMB(bytes: Long): String {
    val tenths = (bytes / 1048576.0 * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

/** 下载速率：≥1MB/s 用 MB/s，否则 KB/s。 */
private fun fmtSpeed(bytesPerSec: Long): String {
    return if (bytesPerSec >= 1048576L) {
        val tenths = (bytesPerSec / 1048576.0 * 10).toLong()
        "${tenths / 10}.${tenths % 10}MB/s"
    } else {
        val tenths = (bytesPerSec / 1024.0 * 10).toLong()
        "${tenths / 10}.${tenths % 10}KB/s"
    }
}

/**
 * 玩家行名称格：同步中在名字下方展开阶段/进度（原顶部待同步面板的信息收到行内）。
 */
@Composable
private fun RowScope.RoomPlayerNameCell(
    name: String,
    nameColor: Color,
    syncPeer: SyncPeerSnapshot?,
    showReadySpinner: Boolean,
    compact: Boolean,
) {
    val settings = koinInject<Settings>()
    val nameStyle = if (settings.boldText) {
        MaterialTheme.typography.bodyLarge
    } else {
        MaterialTheme.typography.bodyMedium
    }
    val syncing = syncPeer != null && syncPeer.phase != SyncPeerPhase.SYNCED
    Column(
        modifier = Modifier
            .weight(RoomPlayerNameWeight)
            .fillMaxHeight()
            .padding(horizontal = if (compact) 4.dp else 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = if (syncing) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (syncing) Arrangement.Start else Arrangement.Center,
        ) {
            when {
                syncPeer?.phase == SyncPeerPhase.SYNCED -> {
                    Text(
                        "✓",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF4CAF50),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                syncing || showReadySpinner -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(if (compact) 10.dp else 12.dp),
                        strokeWidth = if (compact) 1.5.dp else 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
            }
            Text(
                name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = nameColor,
                style = nameStyle,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (syncing && syncPeer != null) {
            RoomPlayerSyncStatus(peer = syncPeer, compact = compact)
        }
    }
}

/**
 * 玩家行内同步详情：下载显示百分比与模组计数；应用显示单位名与已加载单位数。
 */
@Composable
private fun RoomPlayerSyncStatus(peer: SyncPeerSnapshot, compact: Boolean) {
    val applyingUnits = peer.phase == SyncPeerPhase.APPLYING && peer.currentBytes > 0
    val indeterminate = peer.phase == SyncPeerPhase.WAITING_HOST ||
        peer.phase == SyncPeerPhase.APPLYING ||
        peer.phase == SyncPeerPhase.JOINING
    val progress = if (!indeterminate && peer.currentTotal > 0) {
        (peer.currentBytes.toFloat() / peer.currentTotal).coerceIn(0f, 1f)
    } else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(220),
        label = "player sync progress",
    )
    val percent = (progress * 100).roundToInt()
    val statusLabel = when (peer.phase) {
        SyncPeerPhase.WAITING_HOST -> readI18n("modSync.phaseWaitingHost", I18nType.RWPP)
        SyncPeerPhase.APPLYING -> applyingPhaseLabel(
            peer.currentModName.takeIf { applyingUnits && !compact },
        )
        SyncPeerPhase.JOINING -> readI18n("modSync.phaseJoining", I18nType.RWPP)
        else -> peer.currentModName.ifBlank { readI18n("modSync.phaseDownloading", I18nType.RWPP) }
    }
    val modCount = peer.modCount.coerceAtLeast(1)
    val modIndexDisplay = (peer.modIndex + 1).coerceIn(1, modCount)

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        Text(
            statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!compact && !indeterminate) {
            Text(
                "${fmtMB(peer.currentBytes)}/${fmtMB(peer.currentTotal)}MB",
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (indeterminate) {
            LinearProgressIndicator(
                modifier = Modifier
                    .weight(0.9f)
                    .height(if (compact) 3.dp else 4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainer,
            )
            if (applyingUnits) {
                ApplyingUnitCountBadge(count = peer.currentBytes.toInt(), compact = compact)
            }
        } else {
            ModSyncProgressBar(
                progress = animatedProgress,
                modifier = Modifier
                    .weight(0.9f)
                    .height(if (compact) 3.dp else 4.dp),
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
            )
            Text(
                "$modIndexDisplay/$modCount",
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
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(fillStartColor, fillEndColor),
                        startX = 0f,
                        endX = size.width,
                    ),
                    cornerRadius = CornerRadius(radius, radius),
                )
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
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = radius * 0.45f,
                center = Offset((fillWidth - radius).coerceAtLeast(radius), size.height / 2f),
            )
        }
    }
}

/** 应用阶段文案：有当前单位名时附在「正在应用模组」后，计数走 [ApplyingUnitCountBadge]。 */
@Composable
private fun applyingPhaseLabel(unitName: String?): String {
    val base = readI18n("modSync.phaseApplying", I18nType.RWPP)
    val name = unitName?.takeIf { it.isNotBlank() } ?: return base
    return "$base · $name"
}

@Composable
private fun ApplyingUnitCountBadge(count: Int, compact: Boolean) {
    Text(
        count.toString(),
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

