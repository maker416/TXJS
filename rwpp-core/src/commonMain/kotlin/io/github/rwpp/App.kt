/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

@file:Suppress("DuplicatedCode")

package io.github.rwpp

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import coil3.size.Precision
import io.github.rwpp.coil.AccountAvatarFetcherFactory
import io.github.rwpp.coil.AccountAvatarKeyer
import io.github.rwpp.coil.ImageableFetcherFactory
import io.github.rwpp.coil.ImageableKeyer
import io.github.rwpp.config.CoreData
import io.github.rwpp.config.Settings
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.core.GameSessionController
import io.github.rwpp.core.ModSyncController
import io.github.rwpp.core.ModPlaytimeController
import io.github.rwpp.core.RoomSnapshotStore
import io.github.rwpp.net.sync.SyncPeerPhase
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.broadcast
import io.github.rwpp.event.events.KeyboardEvent
import io.github.rwpp.event.events.RefreshUIEvent
import io.github.rwpp.event.events.ReloadModEvent
import io.github.rwpp.event.events.ReloadModFinishedEvent
import io.github.rwpp.event.onDispose
import io.github.rwpp.game.Game
import io.github.rwpp.game.map.MissionType
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.io.SizeUtils
import io.github.rwpp.app.AutoUpdater
import io.github.rwpp.net.Net
import io.github.rwpp.scripts.Render
import io.github.rwpp.ui.*
import io.github.rwpp.ui.UI.selectedColorSchemeName
import io.github.rwpp.utils.compareVersions
import io.github.rwpp.ui.UI.showExtensionView
import io.github.rwpp.ui.UI.showMissionView
import io.github.rwpp.ui.UI.showModsView
import io.github.rwpp.ui.UI.showMultiplayerView
import io.github.rwpp.ui.UI.showOpenSourceInfoView
import io.github.rwpp.ui.UI.showReplayView
import io.github.rwpp.ui.UI.showResourceBrowser
import io.github.rwpp.ui.UI.showRoomView
import io.github.rwpp.ui.UI.showAccountView
import io.github.rwpp.ui.UI.showFriendsView
import io.github.rwpp.ui.UI.showSettingsView
import io.github.rwpp.ui.UI.showSinglePlayerView
import io.github.rwpp.ui.UI.showSurvivalView
import io.github.rwpp.ui.UI.showThemesView
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.LauncherMusicController
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.LineSpinFadeLoaderIndicator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

var LocalWindowManager = staticCompositionLocalOf { WindowManager.Large }

@Suppress("UnusedBoxWithConstraintsScope", "UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun App(
    sizeModifier: Modifier = Modifier.fillMaxSize(),
    isPremium: Boolean = false,
    onChangeBackgroundImage: (String) -> Unit,
) {
    val coreData = koinInject<CoreData>()
    val settings = koinInject<Settings>()
    val net = koinInject<Net>()
    val appContext = koinInject<AppContext>()
    var isSinglePlayerGame by remember { mutableStateOf(false) }
    var roomExitInProgress by remember { mutableStateOf(false) }
    val appScope = rememberCoroutineScope()

    val updateController = remember {
        MandatoryUpdateController(net::getLatestVersionProfile, appScope, appKoin.getOrNull<AutoUpdater>(),
            appContext.isAndroid(), hasNetworkConnection = appContext::hasNetworkConnection)
    }
    LaunchedEffect(Unit) { UI.latestVersionProfile = updateController.check() }
    LaunchedEffect(Unit) {
        coreData.lastPlayTime = System.currentTimeMillis()
        runCatching { AccountSession.restoreIfNeeded() }
    }
    DisposableEffect(updateController) {
        onDispose { updateController.cancel() }
    }
    val requiredRelease = updateController.release
    if (requiredRelease != null) {
        RWPPTheme {
            MandatoryUpdateScreen(requiredRelease, updateController.progress,
                onUpdate = updateController::start,
                onCancel = updateController::cancel,
                onExit = { updateController.cancel(); appContext.exit() },
                onOpenRelease = { net.openUriInBrowser("https://gitee.com/maker416/TXJS/releases") })
        }
        return
    }

    // 好友私信全局轮询：驱动未读徽标与房间邀请悬浮卡片。
    // 好友页/账号页打开时跳过（它们有自有轮询）；共用聊天配额，列表每 5s 刷新
    LaunchedEffect(Unit) {
        while (true) {
            if (AccountSession.loggedIn && AccountSession.networkEnabled &&
                !UI.showFriendsView && !UI.showAccountView
            ) {
                runCatching { FriendsSession.refreshLists() }
            }
            delay(FriendsSession.LIST_POLL_INTERVAL_MS)
        }
    }

    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .components {
                add(ImageableFetcherFactory())
                add(ImageableKeyer())
                add(AccountAvatarFetcherFactory())
                add(AccountAvatarKeyer())
            }
            .precision(Precision.EXACT)
            .build()
    }

    val showMainMenu = launcherPage == LauncherPage.MainMenu && !showAccountView && !showFriendsView

    // 主题美术包：进程内一次初始化（扫描 themes/ 并恢复上次启用的包）
    LaunchedEffect(Unit) {
        LauncherMusicController.init()
        withContext(Dispatchers.IO) { ArtThemeController.ensureInitialized() }
    }

    val game = koinInject<Game>()

    LaunchedEffect(game) { ModPlaytimeController.start(game) }

    // 房间状态快照的唯一采样入口：RefreshUIEvent 是引擎→UI 的既有刷新漏斗，
    // 在 Main 上重采样 RoomSnapshot，房间 UI 只采集不可变快照
    GlobalEventChannel.filter(RefreshUIEvent::class).onDispose {
        subscribeAlways(Dispatchers.Main.immediate) { RoomSnapshotStore.resample(game) }
    }

    val globalFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val interactionSource = remember { MutableInteractionSource() }

    RWPPTheme {
        BoxWithConstraints(
            modifier = Modifier
                .then(sizeModifier)
                .focusRequester(globalFocusRequester)
                .clickable(
                    interactionSource,
                    null
                ) {
                    globalFocusRequester.requestFocus()
                    keyboardController?.hide()
                }.onKeyEvent {
                    runBlocking {
                        if (it.type == KeyEventType.KeyDown) {
                            KeyboardEvent(it.key.keyCode.toInt()).broadcast().isIntercepted
                        } else false
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalTextSelectionColors provides RWSelectionColors,
                LocalWindowManager provides ConstraintWindowManager(maxWidth, maxHeight),
                LocalUpdateCheckInProgress provides updateController.checking,
            ) {

                val enableAnimations = settings.enableAnimations
                Scaffold(
                    containerColor = Color.Transparent,
                    floatingActionButton = {
                        if(game.isGameCouldContinue() && (showMainMenu || showMissionView || showSinglePlayerView || showSurvivalView)) {
                            FloatingActionButton(
                                onClick = { game.continueGame() },
                                shape = CircleShape,
                                modifier = Modifier.padding(5.dp),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.surfaceTint)
                            }
                        }
                    },
                    floatingActionButtonPosition = FabPosition.End
                ) {
                    LauncherOverlayHost(
                        visible = showMainMenu,
                        isInteractive = { launcherPage == LauncherPage.MainMenu && !showAccountView && !showFriendsView },
                        enter = if(enableAnimations) fadeIn() else EnterTransition.None,
                        exit = if(enableAnimations) fadeOut() else ExitTransition.None,
                    ) {
                        UI.UiProvider.MainMenu(
                            multiplayer = {
                                isSinglePlayerGame = false
                                showMultiplayerView = true
                            },
                            singlePlayer = {
                                showSinglePlayerView = true
                            },
                            settings = {
                                showSettingsView = true
                            },
                            mods = {
                                showModsView = true
                            },
                            extension = {
                                showExtensionView = true
                            },
                            resourceBrowser = {
                                showResourceBrowser = true
                            },
                            openSourceInfo = {
                                showOpenSourceInfoView = true
                            },
                            account = {
                                showAccountView = true
                            },
                            friends = {
                                showFriendsView = true
                            },
                        )
                    }

                    LauncherPageHost(
                        LauncherPage.Mission,
                        enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                        exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                    ) {
                        MissionView { showMissionView = false }
                    }

                    LauncherPageHost(
                        LauncherPage.Survival,
                        enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                        exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                    ) {
                        MissionView(fixedType = MissionType.Survival) { showSurvivalView = false }
                    }
                }

                LauncherPageHost(
                    LauncherPage.SinglePlayer,
                    enter = if(enableAnimations) fadeIn() + slideInVertically() else EnterTransition.None,
                    exit = if(enableAnimations) fadeOut() + slideOutVertically() else ExitTransition.None,
                ) {
                    SinglePlayerView(
                        onExit = { showSinglePlayerView = false },
                        onMission = {
                            navigateTo(LauncherPage.Mission)
                        },
                        onSurvival = {
                            navigateTo(LauncherPage.Survival)
                        },
                        onSkirmish = {
                            appScope.launch {
                                GameSessionController.beginSession {
                                    if (launcherPage != LauncherPage.SinglePlayer) return@beginSession
                                    isSinglePlayerGame = true
                                    game.hostNewSinglePlayer(false)
                                    navigateTo(LauncherPage.Room)
                                    GameSessionController.onRoomOpened()
                                }
                            }
                        },
                        onSandbox = {
                            appScope.launch {
                                GameSessionController.beginSession {
                                    if (launcherPage != LauncherPage.SinglePlayer) return@beginSession
                                    isSinglePlayerGame = true
                                    game.hostNewSinglePlayer(sandbox = true)
                                    navigateTo(LauncherPage.Room)
                                    GameSessionController.onRoomOpened()
                                }
                            }
                        },
                    )
                }

                LauncherPageHost(
                    LauncherPage.Multiplayer,
                    enter = if(enableAnimations) fadeIn() + slideInVertically() else EnterTransition.None,
                    exit = if(enableAnimations) fadeOut() + slideOutVertically() else ExitTransition.None,
                ) {
                    MultiplayerView(
                        { showMultiplayerView = false },
                        {
                            isSinglePlayerGame = false
                            showRoomView = true
                            GameSessionController.onRoomOpened()
                        },
                    )
                }

                LauncherPageHost(
                    LauncherPage.Settings,
                    enter = if (enableAnimations) fadeIn() + slideInVertically() else EnterTransition.None,
                    exit = if (enableAnimations) fadeOut() + slideOutVertically() else ExitTransition.None,
                ) {
                    SettingsView(
                        {
                            if (compareVersions(it.version, projectVersion) <= 0) return@SettingsView
                            UI.latestVersionProfile = it
                            updateController.accept(it)
                        },
                        selectedColorSchemeName,
                        { theme ->
                            settings.selectedTheme = theme
                            selectedColorSchemeName = theme
                        },
                        onChangeBackgroundImage
                    ) { showSettingsView = false }
                }

                LauncherPageHost(
                    LauncherPage.Mods,
                    enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                    exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                ) {
                    ModsAndMapsView { showModsView = false }
                }

                LauncherPageHost(
                    LauncherPage.ResourceBrowser,
                    enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                    exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                ) {
                    ResourceBrowser { showResourceBrowser = false }
                }

                LauncherPageHost(
                    LauncherPage.Extensions,
                    enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                    exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                ) {
                    ExtensionView {
                        showExtensionView = false
                    }
                }

                LauncherPageHost(
                    LauncherPage.Themes,
                    enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                    exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                ) {
                    ThemesView {
                        showThemesView = false
                    }
                }

                LauncherPageHost(
                    LauncherPage.Replay,
                    enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                    exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                ) {
                    ReplaysViewDialog {
                        showReplayView = false
                    }
                }

                LauncherPageHost(
                    LauncherPage.OpenSourceInfo,
                    enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                    exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                ) {
                    OpenSourceInfoView {
                        showOpenSourceInfoView = false
                    }
                }

                LauncherPageHost(
                    LauncherPage.Room,
                    enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                    exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                ) {
                    val roomDisposed = remember { CompletableDeferred<Unit>() }
                    DisposableEffect(Unit) {
                        onDispose { roomDisposed.complete(Unit) }
                    }
                    MultiplayerRoomView(isSinglePlayerGame) {
                        if (roomExitInProgress || launcherPage != LauncherPage.Room) return@MultiplayerRoomView
                        val syncPhase = ModSyncController.inRoomSyncPhase
                        if (syncPhase != null && syncPhase != SyncPeerPhase.SYNCED) {
                            ModSyncController.cancelInRoomSync()
                            return@MultiplayerRoomView
                        }

                        val returnToMultiplayerView = !isSinglePlayerGame
                        roomExitInProgress = true

                        // 返回上一级：单人房回「单人游戏」子菜单，多人房回多人列表
                        navigateBack()

                        // 等待旧页面 dispose 和断线订阅者清理；新会话独占开始请求并等待此屏障。
                        GameSessionController.scheduleClose {
                            try {
                                // 等真实退出动画及页面 dispose，不能用固定延迟猜测 UI 生命周期。
                                roomDisposed.await()

                                if (returnToMultiplayerView) {
                                    game.cancelJoinServer()
                                }

                                game.onBanUnits(listOf())
                                game.gameRoom.disconnectAndWait()
                            } finally {
                                roomExitInProgress = false
                            }
                        }
                    }
                }

                // 叠加页空白区域也拦截点击，避免穿透到房间或底层页面。
                if (showFriendsView || showAccountView) {
                    Box(Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ))
                }

                LauncherOverlayHost(
                    showFriendsView,
                    isInteractive = { showFriendsView && !showAccountView },
                    enter = if (enableAnimations) fadeIn() + slideInVertically() else EnterTransition.None,
                    exit = if (enableAnimations) fadeOut() + slideOutVertically() else ExitTransition.None,
                ) {
                    FriendsView(
                        onExit = {
                            showFriendsView = false
                            FriendsSession.closeChat()
                        },
                        onGoLogin = {
                            showFriendsView = false
                            FriendsSession.closeChat()
                            showAccountView = true
                        },
                    )
                }

                // 账号页必须渲染在房间页之后（上层）：房间内「邀请好友」未登录时会就地打开登录，
                // 登录完成关闭后回到房间，不能与房间画面交叠。
                LauncherOverlayHost(
                    showAccountView,
                    isInteractive = { showAccountView },
                    enter = if (enableAnimations) fadeIn() + slideInVertically() else EnterTransition.None,
                    exit = if (enableAnimations) fadeOut() + slideOutVertically() else ExitTransition.None,
                ) {
                    AccountView(onExit = {
                        showAccountView = false
                    })
                }

                var warningDialogVisible by remember { mutableStateOf(false) }

                LaunchedEffect(UI.warning) {
                    if (UI.warning != null) {
                        warningDialogVisible = true
                        if (UI.warning?.isKicked == true && !ModSyncController.isInRoomSyncInProgress()) {
                            // 被踢是硬跳转：放弃返回栈直达多人列表
                            resetNavigation(LauncherPage.Multiplayer)
                            GameSessionController.onExternalSessionEnd()
                        }
                    }
                }

                AnimatedAlertDialog(
                    warningDialogVisible,
                    onDismissRequest = { warningDialogVisible = false }) { dismiss ->
                    BorderCard(
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .widthIn(max = 420.dp)
                            .wrapContentHeight()
                            .autoClearFocus(),
                        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(42.dp)
                            )
                            Text(
                                readI18n("common.notice"),
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                UI.warning?.reason ?: "",
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            RWTextButton(readI18n("common.ok"), onClick = dismiss)
                        }
                    }
                }

                var questionDialogVisible by remember { mutableStateOf(false) }
                LaunchedEffect(UI.question) {
                    questionDialogVisible = UI.question != null
                }

                AnimatedAlertDialog(questionDialogVisible,
                    onDismissRequest = {
                        questionDialogVisible = false
                        UI.question?.callback?.invoke(null)
                        if (showRoomView && !ModSyncController.isInRoomSyncInProgress()) {
                            resetNavigation(LauncherPage.Multiplayer)
                            GameSessionController.onExternalSessionEnd()
                        }

                        UI.question = null
                    }
                ) { _ ->
                    BorderCard(
                        modifier = Modifier.fillMaxWidth(if (LocalWindowManager.current == WindowManager.Small) 0.9f else 0.75f).autoClearFocus(),
                    ) {

                        Box(modifier = Modifier
                            .fillMaxWidth()
                            .height(75.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    listOf(Color(0xE9EE8888),
                                        Color(0xFFE4BD79)))
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Info, null, modifier = Modifier.size(25.dp).padding(5.dp))
                                    Text(
                                        UI.question?.title ?: "",
                                        modifier = Modifier.padding(5.dp),
                                        style = MaterialTheme.typography.headlineLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        LargeDividingLine { 0.dp }
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                UI.question?.message ?: "",
                                modifier = Modifier.padding(5.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            var message by remember { mutableStateOf("") }
                            RWSingleOutlinedTextField(
                                label = "Reply",
                                value = message,
                                modifier = Modifier.fillMaxWidth().padding(10.dp)
                                    .onKeyEvent {
                                        if(it.key == Key.Enter && message.isNotEmpty()) {
                                            UI.question?.callback?.invoke(message)
                                            message = ""
                                            questionDialogVisible = false
                                            true
                                        } else false
                                    },
                                trailingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        null,
                                        modifier = Modifier.clickable {
                                            UI.question?.callback?.invoke(message)
                                            message = ""
                                            questionDialogVisible = false
                                        }
                                    )
                                },
                                onValueChange =
                                {
                                    message = it
                                },
                            )
                        }
                    }
                }

                // 进房后同步尚未完成：进度只在房间页 RoomSelfSyncBar；进房下载不再置
                // showNetworkDialog。此条件仍挡住残留 true，避免回列表后漏出下载卡片。
                val inRoomSyncPhase = ModSyncController.inRoomSyncPhase
                val inRoomSyncActive = inRoomSyncPhase != null && inRoomSyncPhase != SyncPeerPhase.SYNCED

                var reloadingModViewVisible by remember { mutableStateOf(false) }
                GlobalEventChannel.filter(ReloadModEvent::class).onDispose {
                    subscribeAlways(Dispatchers.Main.immediate) {
                        // 清除启动阶段残留的最后一个引擎加载消息（如 "init complete"），
                        // 避免重载弹窗一直显示陈旧文本。
                        loadingMessage = ""
                        reloadingModViewVisible = true
                    }
                }

                GlobalEventChannel.filter(ReloadModFinishedEvent::class).onDispose {
                    subscribeAlways(Dispatchers.Main.immediate) {
                        reloadingModViewVisible = false
                    }
                }

                LoadingView(
                    reloadingModViewVisible && !inRoomSyncActive && !ModSyncController.cancellingReload,
                    onLoaded = {},
                    showProtectedModHint = true,
                    showMemoryUsage = true,
                ) { null }

                LoadingView(
                    ModSyncController.cancellingReload,
                    onLoaded = {},
                    cancellable = false,
                    showMemoryUsage = true,
                ) {
                    message(readI18n("modSync.cancellingDetail", I18nType.RWPP))
                    null
                }

                // 主动取消下载：进房前（尚未建立游戏连接）取消带外同步轻量段；
                // 进房后同步中（房间内联进度条）取消并退出房间。
                val onCancelDownload: () -> Unit = {
                    if (ModSyncController.inRoomSyncPhase != null) {
                        ModSyncController.cancelInRoomSync()
                    } else {
                        ModSyncController.cancelPreJoin()
                    }
                    UI.showNetworkDialog = false
                }

                AnimatedAlertDialog(
                    UI.showNetworkDialog && !inRoomSyncActive,
                    onCancelDownload, enableDismiss = false
                ) { dismiss ->
                    NetworkModDownloadingCard(onCancelDownload)
                }

                AnimatedAlertDialog(
                    UI.dialogWidget != null,
                    {
                        UI.dialogWidget = null
                    }, enableDismiss = true
                ) { dismiss ->
                    BorderCard {
                        UI.dialogWidget?.Render()
                    }
                }

                // 好友房间邀请悬浮卡片：屏幕右侧滑入，底部进度条倒计时 10 秒自动忽略
                RoomInviteToastHost(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 14.dp)
                )

                val appContext = koinInject<AppContext>()
                val exitOverlay by appContext.exitOverlayVisible.collectAsState()
                if (exitOverlay) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xCC000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            LineSpinFadeLoaderIndicator(Color.White)
                            Spacer(Modifier.height(16.dp))
                            Text(
                                readI18n("app.exiting", I18nType.RWPP),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 加入前模组同步下载进度卡片。
 * 复用 [io.github.rwpp.widget.LoadingView] 的视觉语言：左侧色条 + 阶段标签 + 计数徽章 + 字节详情 + 确定值进度条。
 * 进度数据来自 [io.github.rwpp.ui.UI] 的 receivingMod* 状态，由 ModSyncController 带外同步流程实时刷新。
 */
@Composable
private fun NetworkModDownloadingCard(onCancel: () -> Unit) {
    val name = UI.receivingModName
    val progress = UI.receivingModProgress
    val receivedBytes = UI.receivingModReceivedBytes
    val totalBytes = UI.receivingModTotalBytes
    val totalCount = UI.receivingModTotalCount.coerceAtLeast(1)
    val doneCount = UI.receivingModDoneCount.coerceIn(1, totalCount)
    val percent = (progress * 100).toInt().coerceIn(0, 100)
    val detail = readI18n(
        "mod.downloadingModDetail",
        I18nType.RWPP,
        SizeUtils.byteToMB(receivedBytes).toString(),
        SizeUtils.byteToMB(totalBytes).toString(),
    )

    BorderCard(
        modifier = Modifier
            .fillMaxWidth(0.72f)
            .widthIn(min = 300.dp, max = 500.dp)
            .wrapContentHeight(),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.Start
        ) {
            // 阶段行：左侧色条 + 标题 + 计数徽章
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        readI18n("mod.downloadingMod"),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        if (name.isBlank()) detail else "$name · $detail",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    "$doneCount/$totalCount",
                    modifier = Modifier
                        .widthIn(min = 58.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }

            // 确定值进度条 + 百分比
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                )
                Text(
                    "$percent%",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1
                )
            }

            // 取消按钮：主动中断下载并断开房间（与遮罩点解耦，避免误触）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) {
                    Text(
                        readI18n("mod.cancelDownload", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }
    }
}
