/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

@file:Suppress("DuplicatedCode")

package io.github.rwpp

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import io.github.rwpp.app.AutoUpdater
import io.github.rwpp.app.LauncherVisibility
import io.github.rwpp.app.whileVisible
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.coil.AccountAvatarFetcherFactory
import io.github.rwpp.coil.AccountAvatarKeyer
import io.github.rwpp.coil.ImageableFetcherFactory
import io.github.rwpp.coil.ImageableKeyer
import io.github.rwpp.config.CoreData
import io.github.rwpp.config.Settings
import io.github.rwpp.core.GameSessionController
import io.github.rwpp.core.DirectModSyncController
import io.github.rwpp.content.ExpansionContentController
import io.github.rwpp.core.MatchStartController
import io.github.rwpp.core.ModPlaytimeController
import io.github.rwpp.core.ModSyncController
import io.github.rwpp.core.RoomSnapshotStore
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.RefreshUIEvent
import io.github.rwpp.event.events.ReloadModEvent
import io.github.rwpp.event.events.ReloadModFinishedEvent
import io.github.rwpp.event.onDispose
import io.github.rwpp.game.Game
import io.github.rwpp.game.map.MissionType
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.io.SizeUtils
import io.github.rwpp.net.Net
import io.github.rwpp.net.resolveAndroidUpdatePlan
import io.github.rwpp.net.resolveDesktopUpdatePlan
import io.github.rwpp.net.sync.SyncPeerPhase
import io.github.rwpp.scripts.Render
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.LauncherMusicController
import io.github.rwpp.ui.*
import io.github.rwpp.ui.UI.selectedColorSchemeName
import io.github.rwpp.ui.UI.showAccountView
import io.github.rwpp.ui.UI.showExtensionView
import io.github.rwpp.ui.UI.showFriendsView
import io.github.rwpp.ui.UI.showMissionView
import io.github.rwpp.ui.UI.showModsView
import io.github.rwpp.ui.UI.showMultiplayerView
import io.github.rwpp.ui.UI.showOpenSourceInfoView
import io.github.rwpp.ui.UI.showReplayView
import io.github.rwpp.ui.UI.showResourceBrowser
import io.github.rwpp.ui.UI.showRoomView
import io.github.rwpp.ui.UI.showSettingsView
import io.github.rwpp.ui.UI.showSinglePlayerView
import io.github.rwpp.ui.UI.showSurvivalView
import io.github.rwpp.ui.UI.showThemesView
import io.github.rwpp.utils.compareVersions
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.LineSpinFadeLoaderIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val resourceFailure by io.github.rwpp.game.GameStartSafety.state.collectAsState()
    if (resourceFailure != io.github.rwpp.game.GameStartSafety.Failure.NONE) {
        val failedGame = koinInject<Game>()
        LaunchedEffect(resourceFailure) {
            io.github.rwpp.core.GameSessionController.closeFailedResourceLoad(failedGame)
        }
        io.github.rwpp.ui.ResourceLoadFailureScreen(appContext::exitAfterResourceFailure)
        return
    }
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
                onOpenRelease = {
                    val plan = if (appContext.isAndroid()) requiredRelease.resolveAndroidUpdatePlan()
                        else requiredRelease.resolveDesktopUpdatePlan()
                    val installer = plan?.takeUnless { it.isArchive }?.partUrls?.singleOrNull()
                    net.openUriInBrowser(installer ?: "https://gitee.com/maker416/TXJS/releases")
                })
        }
        return
    }

    // 好友私信全局轮询：驱动未读徽标与房间邀请悬浮卡片。
    // 好友页/账号页打开时跳过（它们有自有轮询）；共用聊天配额，列表每 5s 刷新
    LaunchedEffect(Unit) {
        LauncherVisibility.visible.whileVisible {
            while (true) {
                if (AccountSession.loggedIn && AccountSession.networkEnabled &&
                    !UI.showFriendsView && !UI.showAccountView
                ) {
                    runCatching { FriendsSession.refreshLists() }
                }
                delay(FriendsSession.LIST_POLL_INTERVAL_MS)
            }
        }
    }

    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(coil3.intercept.Interceptor { chain ->
                    chain.withRequest(chain.request.newBuilder().crossfade(settings.enableAnimations).build()).proceed()
                })
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
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { prewarmLauncherUiClassesAndCaches() }
    }

    val game = koinInject<Game>()

    LaunchedEffect(game) { ModPlaytimeController.start(game) }

    LaunchedEffect(game) { io.github.rwpp.theme.PersonalizedAppearanceController.run(game) }
    LaunchedEffect(Unit) { io.github.rwpp.theme.OnlineGameThemeController.init() }
    LaunchedEffect(Unit) { ExpansionContentController.init() }

    // 房间状态快照的唯一采样入口：RefreshUIEvent 是引擎→UI 的既有刷新漏斗，
    // 在 Main 上重采样 RoomSnapshot，房间 UI 只采集不可变快照
    GlobalEventChannel.filter(RefreshUIEvent::class).onDispose {
        subscribeAlways(Dispatchers.Main.immediate) { RoomSnapshotStore.resample(game) }
    }

    val globalFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val interactionSource = remember { MutableInteractionSource() }

    // 桌面 ESC 经 KeyboardEvent → BackHandler；焦点若在 GL 画布上则收不到键。桌面：用户页/设置页拉回 Compose；移动端仍含好友页。
    LaunchedEffect(
        showAccountView,
        showFriendsView,
        showSettingsView,
        appContext.isDesktop(),
    ) {
        val requestLauncherFocus = if (appContext.isDesktop()) {
            showAccountView || showSettingsView
        } else {
            showAccountView || showFriendsView
        }
        if (requestLauncherFocus) {
            globalFocusRequester.requestFocus()
        }
    }

    RWPPTheme {
        BoxWithConstraints(
            modifier = Modifier
                .then(sizeModifier)
                .focusRequester(globalFocusRequester)
                .launcherKeyboardEvents(appScope)
                .clickable(
                    interactionSource,
                    null
                ) {
                    globalFocusRequester.requestFocus()
                    keyboardController?.hide()
                }
        ) {
            CompositionLocalProvider(
                LocalTextSelectionColors provides RWSelectionColors,
                LocalWindowManager provides ConstraintWindowManager(maxWidth, maxHeight),
                LocalUpdateCheckInProgress provides updateController.checking,
            ) {

                val enableAnimations = uiAnimationsEnabled()
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
                    val overlayOpen = showAccountView || showFriendsView
                    val mainMenuTransition = remember { MainMenuTransitionMemory() }
                    val enterStyle =
                        if (showMainMenu && !mainMenuTransition.lastShowMainMenu) {
                            if (!mainMenuTransition.lastOverlayOpen &&
                                mainMenuTransition.lastPage == LauncherPage.GameplayHall &&
                                launcherPage == LauncherPage.MainMenu
                            ) {
                                MainMenuTransitionStyle.HorizontalHub
                            } else {
                                MainMenuTransitionStyle.DepthZoom
                            }
                        } else {
                            mainMenuTransition.enterStyle
                        }
                    val exitStyle = when {
                        !showMainMenu && mainMenuTransition.lastShowMainMenu -> {
                            if (!overlayOpen && launcherPage == LauncherPage.GameplayHall) {
                                MainMenuTransitionStyle.HorizontalHub
                            } else {
                                MainMenuTransitionStyle.DepthZoom
                            }
                        }
                        !showMainMenu && !overlayOpen && launcherPage == LauncherPage.GameplayHall -> {
                            MainMenuTransitionStyle.HorizontalHub
                        }
                        else -> mainMenuTransition.exitStyle
                    }
                    SideEffect {
                        mainMenuTransition.lastShowMainMenu = showMainMenu
                        mainMenuTransition.lastPage = launcherPage
                        mainMenuTransition.lastOverlayOpen = overlayOpen
                        mainMenuTransition.enterStyle = enterStyle
                        mainMenuTransition.exitStyle = exitStyle
                    }
                    val mainMenuInteractive = remember {
                        { launcherPage == LauncherPage.MainMenu && !showAccountView && !showFriendsView }
                    }
                    val mainMenuContent = remember<@Composable () -> Unit> {
                        {
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
                                esports = {
                                    navigateTo(LauncherPage.Esports)
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
                    }
                    MainMenuOverlayHost(
                        visible = showMainMenu,
                        isInteractive = mainMenuInteractive,
                        enterStyle = enterStyle,
                        exitStyle = exitStyle,
                        content = mainMenuContent,
                    )

                    TacticalPanelPageHost(LauncherPage.Mission) {
                        MissionView { showMissionView = false }
                    }

                    TacticalPanelPageHost(LauncherPage.Survival) {
                        MissionView(fixedType = MissionType.Survival) { showSurvivalView = false }
                    }
                }

                TacticalPanelPageHost(LauncherPage.SinglePlayer) {
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
                        onSavedGames = {
                            UI.pendingModsMapsTab = ModsMapsTab.Saves
                            navigateTo(LauncherPage.Mods)
                        },
                    )
                }

                TacticalPanelPageHost(LauncherPage.Multiplayer) {
                    MultiplayerView(
                        { showMultiplayerView = false },
                        {
                            isSinglePlayerGame = false
                            GameSessionController.openMultiplayerRoom()
                        },
                    )
                }

                TacticalPanelPageHost(LauncherPage.Settings) {
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

                TacticalPanelPageHost(LauncherPage.Mods) {
                    ModsAndMapsView(
                        onLoadSavedGame = { map ->
                            try {
                                GameSessionController.beginSession {
                                    if (launcherPage != LauncherPage.Mods) return@beginSession
                                    isSinglePlayerGame = true
                                    game.loadSavedGame(map)
                                    closePage(LauncherPage.Mods)
                                    closePage(LauncherPage.SinglePlayer)
                                    GameSessionController.onRoomOpened()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                isSinglePlayerGame = false
                                logger.error("Failed to load saved game", e)
                                throw e
                            }
                        },
                        onExit = { showModsView = false },
                    )
                }

                GameplayHallPageHost {
                    GameplayHallView(
                        onExit = { closePage(LauncherPage.GameplayHall) },
                        onOpenPage = { page ->
                            if (page == LauncherPage.Multiplayer) isSinglePlayerGame = false
                            navigateTo(page)
                        },
                    )
                }

                val portalRoute = launcherPage as? LauncherPage.GamePortal
                if (portalRoute != null) {
                    LauncherBrowserPageHost(portalRoute, EnterTransition.None, ExitTransition.None) {
                        GamePortalView(portalRoute.appId,
                            onExit = { resetNavigation(LauncherPage.GameplayHall) },
                            onOpenRoom = {
                                isSinglePlayerGame = false
                                GameSessionController.openMultiplayerRoom()
                            })
                    }
                }

                ResourceCenterPageHost {
                    ResourceCenterView(
                        onExit = { closePage(LauncherPage.ResourceCenter) },
                        onForum = { navigateTo(LauncherPage.ResourceBrowser) },
                        onEsports = { navigateTo(LauncherPage.Esports) },
                        onExtensions = { navigateTo(LauncherPage.Extensions) },
                        extraMenus = UI.UiProvider.extraMenuList,
                    )
                }

                LauncherBrowserPageHost(
                    LauncherPage.ResourceBrowser,
                    enter = browserPageEnter(enableAnimations),
                    exit = browserPageExit(enableAnimations),
                ) {
                    ResourceBrowser { showResourceBrowser = false }
                }

                LauncherBrowserPageHost(
                    LauncherPage.Esports,
                    enter = browserPageEnter(enableAnimations),
                    exit = browserPageExit(enableAnimations),
                ) {
                    EsportsBrowser { closePage(LauncherPage.Esports) }
                }

                TacticalPanelPageHost(LauncherPage.Extensions) {
                    ExtensionView {
                        showExtensionView = false
                    }
                }

                TacticalPanelPageHost(LauncherPage.Themes) {
                    ThemesView {
                        showThemesView = false
                    }
                }

                TacticalPanelPageHost(LauncherPage.Replay) {
                    ReplaysViewDialog {
                        showReplayView = false
                    }
                }

                TacticalPanelPageHost(LauncherPage.OpenSourceInfo) {
                    OpenSourceInfoView {
                        showOpenSourceInfoView = false
                    }
                }

                TacticalPanelPageHost(LauncherPage.Room) {
                    val roomDisposed = remember { CompletableDeferred<Unit>() }
                    DisposableEffect(Unit) {
                        GameSessionController.attachRoomView(roomDisposed)
                        onDispose {
                            roomDisposed.complete(Unit)
                            GameSessionController.detachRoomView(roomDisposed)
                        }
                    }
                    MultiplayerRoomView(isSinglePlayerGame) {
                        if (roomExitInProgress || launcherPage != LauncherPage.Room) return@MultiplayerRoomView
                        val syncPhase = ModSyncController.inRoomSyncPhase
                        if (syncPhase != null && syncPhase != SyncPeerPhase.SYNCED) {
                            ModSyncController.cancelInRoomSync()
                            return@MultiplayerRoomView
                        }

                        roomExitInProgress = true
                        GameSessionController.scheduleRoomExit(game, multiplayer = !isSinglePlayerGame) {
                            roomExitInProgress = false
                        }
                        navigateBack()
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
                    enter = tacticalSheetEnter(enableAnimations),
                    exit = tacticalSheetExit(enableAnimations),
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
                    enter = if (enableAnimations) fadeIn(
                        tween(SHEET_ENTER_FADE_MS, easing = StandardDecelerateEasing),
                    ) else EnterTransition.None,
                    exit = if (enableAnimations) fadeOut(
                        tween(SHEET_EXIT_MS, easing = EmphasizedAccelerateEasing),
                    ) else ExitTransition.None,
                ) {
                    AccountView(onExit = {
                        showAccountView = false
                    })
                }

                val matchStart by MatchStartController.gate.state.collectAsState()
                MatchStartLoadingOverlay(
                    visible = matchStart.busy && launcherPage == LauncherPage.Room,
                    isDesktop = appContext.isDesktop(),
                )

                var warningDialogVisible by remember { mutableStateOf(false) }

                LaunchedEffect(UI.warning) {
                    if (UI.warning != null) {
                        warningDialogVisible = true
                        if (UI.warning?.isKicked == true && !ModSyncController.isInRoomSyncInProgress() &&
                            launcherPage !is LauncherPage.GamePortal) {
                            // 被踢后按本次入房来源重新打开页面。
                            GameSessionController.returnAfterExternalDisconnect(game)
                        }
                    }
                }

                AnimatedAlertDialog(
                    warningDialogVisible,
                    onDismissRequest = { warningDialogVisible = false }) { dismiss ->
                    val failure = UI.warning?.connectionFailure
                    if (failure != null) {
                        io.github.rwpp.widget.ConnectionFailureCard(failure, dismiss)
                    } else BorderCard(
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
                            GameSessionController.returnAfterExternalDisconnect(game)
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
                val directApplying by DirectModSyncController.shared.isApplyingMods.collectAsState()

                var reloadingModViewVisible by remember { mutableStateOf(false) }
                GlobalEventChannel.filter(ReloadModEvent::class).onDispose {
                    subscribeAlways(Dispatchers.Main.immediate) {
                        // 清除启动阶段残留的最后一个引擎加载消息（如 "init complete"），
                        // 避免重载弹窗一直显示陈旧文本。
                        if (!DirectModSyncController.shared.isApplyingMods.value) loadingMessage = ""
                        // The portal already displays its expansion reload above the browser.
                        val portalReload = launcherPage is LauncherPage.GamePortal &&
                            ExpansionContentController.portalToken != null && ExpansionContentController.switching
                        reloadingModViewVisible = !portalReload
                    }
                }

                GlobalEventChannel.filter(ReloadModFinishedEvent::class).onDispose {
                    subscribeAlways(Dispatchers.Main.immediate) {
                        reloadingModViewVisible = false
                    }
                }

                LoadingView(
                    reloadingModViewVisible && !inRoomSyncActive && !directApplying && !ModSyncController.cancellingReload,
                    onLoaded = {},
                    showProtectedModHint = true,
                    showMemoryUsage = true,
                    inlineDesktop = appContext.isDesktop(),
                ) { null }

                LoadingView(
                    ModSyncController.cancellingReload,
                    onLoaded = {},
                    cancellable = false,
                    showMemoryUsage = true,
                    inlineDesktop = appContext.isDesktop(),
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
