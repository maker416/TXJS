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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import coil3.size.Precision
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import io.github.rwpp.coil.ImageableFetcherFactory
import io.github.rwpp.coil.ImageableKeyer
import io.github.rwpp.config.CoreData
import io.github.rwpp.config.Settings
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.broadcast
import io.github.rwpp.event.events.KeyboardEvent
import io.github.rwpp.event.events.ReloadModEvent
import io.github.rwpp.event.events.ReloadModFinishedEvent
import io.github.rwpp.event.onDispose
import io.github.rwpp.game.Game
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.app.AutoUpdater
import io.github.rwpp.net.LatestVersionProfile
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
import io.github.rwpp.ui.UI.showSettingsView
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.LineSpinFadeLoaderIndicator
import io.github.rwpp.widget.v2.bounceClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

var LocalWindowManager = staticCompositionLocalOf { WindowManager.Large }

private const val ROOM_EXIT_DISCONNECT_DELAY_MS = 120L

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
    var isSinglePlayerGame by remember { mutableStateOf(false) }
    var roomExitInProgress by remember { mutableStateOf(false) }
    val appScope = rememberCoroutineScope()

    var checkUpdateDialogVisible by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<LatestVersionProfile?>(null) }

    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        coreData.lastPlayTime = now
        // per day
        if ((now - (coreData.lastAutoCheckUpdateTime + 1000 * 60 * 60 * 24) > 0) || coreData.debug) {
            withContext(Dispatchers.IO) {
                if (settings.autoCheckUpdate) {
                    val _profile = net.getLatestVersionProfile()

                    if (_profile != null) {
                        coreData.lastAutoCheckUpdateTime = now

                        if ((compareVersions(_profile.version, projectVersion) > 0 && settings.ignoreVersion != _profile.version) || coreData.debug) {
                            profile = _profile
                            checkUpdateDialogVisible = true
                        }
                    }
                }
            }
        }
    }

    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .components {
                add(ImageableFetcherFactory())
                add(ImageableKeyer())
            }
            .precision(Precision.EXACT)
            .build()
    }

    val showMainMenu = !(showMultiplayerView
            || showMissionView
            || showSettingsView
            || showModsView
            || showRoomView
            || showExtensionView
            || showReplayView
            || showResourceBrowser
            || showOpenSourceInfoView)

    val game = koinInject<Game>()

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
                LocalWindowManager provides ConstraintWindowManager(maxWidth, maxHeight)
            ) {

                val enableAnimations = settings.enableAnimations
                Scaffold(
                    containerColor = Color.Transparent,
                    floatingActionButton = {
                        if(game.isGameCouldContinue() && (showMainMenu || showMissionView)) {
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
                    AnimatedVisibility(
                        showMainMenu,
                        enter = if(enableAnimations) fadeIn() else EnterTransition.None,
                        exit = if(enableAnimations) fadeOut() else ExitTransition.None,
                    ) {
                        UI.UiProvider.MainMenu(
                            multiplayer = {
                                isSinglePlayerGame = false
                                showMultiplayerView = true
                            },
                            mission = {
                                showMissionView = true
                            },
                            skirmish = {
                                showRoomView = true
                                isSinglePlayerGame = true
                                game.hostNewSinglePlayer(false)
                            },
                            settings = {
                                showSettingsView = true
                            },
                            mods = {
                                showModsView = true
                            },
                            sandbox = {
                                isSinglePlayerGame = true
                                showRoomView = true
                                game.hostNewSinglePlayer(sandbox = true)
                            },
                            extension = {
                                showExtensionView = true
                            },
                            replay = {
                                showReplayView = true
                            },
                            resourceBrowser = {
                                showResourceBrowser = true
                            },
                            openSourceInfo = {
                                showOpenSourceInfoView = true
                            }
                        )
                    }

                    AnimatedVisibility(
                        showMissionView,
                        enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                        exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                    ) {
                        MissionView { showMissionView = false }
                    }
                }

                AnimatedVisibility(
                    showMultiplayerView,
                    enter = if(enableAnimations) fadeIn() + slideInVertically() else EnterTransition.None,
                    exit = if(enableAnimations) fadeOut() + slideOutVertically() else ExitTransition.None,
                ) {
                    MultiplayerView(
                        { showMultiplayerView = false },
                        {
                            isSinglePlayerGame = false
                            showRoomView = true
                        },
                    )
                }

                AnimatedVisibility(
                    showSettingsView,
                    enter = if (enableAnimations) fadeIn() + slideInVertically() else EnterTransition.None,
                    exit = if (enableAnimations) fadeOut() + slideOutVertically() else ExitTransition.None,
                ) {
                    SettingsView(
                        {
                            if (it.version == projectVersion || settings.ignoreVersion == it.version) return@SettingsView
                            profile = it
                            checkUpdateDialogVisible = true
                        },
                        selectedColorSchemeName,
                        { theme ->
                            settings.selectedTheme = theme
                            selectedColorSchemeName = theme
                        },
                        onChangeBackgroundImage
                    ) { showSettingsView = false }
                }

                AnimatedVisibility(
                    showModsView,
                    enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                    exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                ) {
                    ModsView { showModsView = false }
                }

                AnimatedVisibility(
                    showResourceBrowser,
                    enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                    exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                ) {
                    ResourceBrowser { showResourceBrowser = false }
                }

                AnimatedVisibility(
                    showExtensionView,
                    enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                    exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                ) {
                    ExtensionView {
                        showExtensionView = false
                    }
                }

                AnimatedVisibility(
                    showReplayView,
                    enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                    exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                ) {
                    ReplaysViewDialog {
                        showReplayView = false
                    }
                }

                AnimatedVisibility(
                    showOpenSourceInfoView,
                    enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                    exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                ) {
                    OpenSourceInfoView {
                        showOpenSourceInfoView = false
                    }
                }

                AnimatedVisibility(
                    showRoomView,
                    enter = if (enableAnimations) fadeIn() + expandIn() else EnterTransition.None,
                    exit = if (enableAnimations) shrinkOut() + fadeOut() else ExitTransition.None,
                ) {
                    MultiplayerRoomView(isSinglePlayerGame) {
                        if (roomExitInProgress) return@MultiplayerRoomView

                        val returnToMultiplayerView = !isSinglePlayerGame
                        roomExitInProgress = true

                        showRoomView = false
                        if (returnToMultiplayerView) showMultiplayerView = true

                        appScope.launch {
                            try {
                                withFrameNanos { }
                                if (settings.enableAnimations) {
                                    delay(ROOM_EXIT_DISCONNECT_DELAY_MS)
                                }

                                if (returnToMultiplayerView) {
                                    game.cancelJoinServer()
                                }

                                game.onBanUnits(listOf())
                                game.gameRoom.disconnect()
                            } finally {
                                roomExitInProgress = false
                            }
                        }
                    }
                }

                var warningDialogVisible by remember { mutableStateOf(false) }

                LaunchedEffect(UI.warning) {
                    if (UI.warning != null) {
                        warningDialogVisible = true
                        if (UI.warning?.isKicked == true) {
                            showRoomView = false
                            showMultiplayerView = true
                        }
                    }
                }

                AnimatedAlertDialog(
                    warningDialogVisible,
                    onDismissRequest = { warningDialogVisible = false }) { dismiss ->
                    BorderCard(
                       modifier = Modifier.size(500.dp).autoClearFocus(),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(5.dp), horizontalArrangement = Arrangement.Center) {
                            Box {
                                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(50.dp))
                            }
                        }

                        LargeDividingLine { 0.dp }

                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                UI.warning?.reason ?: "",
                                modifier = Modifier.padding(5.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }

                AnimatedAlertDialog(
                    checkUpdateDialogVisible,
                    onDismissRequest = { checkUpdateDialogVisible = false }
                ) { dismiss ->
                    BorderCard(
                        modifier = Modifier.fillMaxWidth(GeneralProportion()).verticalScroll(rememberScrollState()),
                        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    readI18n("settings.updateAvailable", I18nType.RWPP),
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(
                                        (if (profile!!.prerelease) "Pre-release " else "") + profile!!.version,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
                                Spacer(modifier = Modifier.height(12.dp))

                                val appContextInner = koinInject<AppContext>()
                                val autoUpdater = remember { appKoin.getOrNull<AutoUpdater>() }
                                val exeAsset = profile!!.assets.find { it.name.endsWith(".exe") }
                                val apkAsset = profile!!.assets.find { it.name.endsWith(".apk") }
                                val autoUpdateAsset = if (appContextInner.isDesktop()) exeAsset else apkAsset
                                val scopeInner = rememberCoroutineScope()
                                var updating by remember { mutableStateOf(false) }
                                var downloadProgress by remember { mutableStateOf(0f) }

                                if (profile!!.assets.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        profile!!.assets.forEach { asset ->
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.fillMaxWidth().bounceClick {
                                                    net.openUriInBrowser(asset.downloadUrl)
                                                }
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        asset.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        readI18n("settings.noDownloadAssets", I18nType.RWPP),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                if (autoUpdater != null && autoUpdater.isSupported() && autoUpdateAsset != null && !updating) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth().bounceClick {
                                            updating = true
                                            scopeInner.launch(Dispatchers.IO) {
                                                autoUpdater.downloadAndInstall(autoUpdateAsset.downloadUrl) { progress ->
                                                    downloadProgress = progress
                                                }
                                            }
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                readI18n("settings.downloadAndInstall", I18nType.RWPP),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }
                                }

                                if (updating) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    if (downloadProgress < 0f) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                readI18n("settings.downloadFailed", I18nType.RWPP),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainer,
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.bounceClick {
                                                    downloadProgress = 0f
                                                    scopeInner.launch(Dispatchers.IO) {
                                                        autoUpdater!!.downloadAndInstall(autoUpdateAsset!!.downloadUrl) { progress ->
                                                            downloadProgress = progress
                                                        }
                                                    }
                                                }
                                            ) {
                                                Text(
                                                    readI18n("settings.retry", I18nType.RWPP),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    readI18n("settings.downloadingUpdate", I18nType.RWPP),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                                )
                                                Text(
                                                    "${(downloadProgress * 100).toInt()}%",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            LinearProgressIndicator(
                                                progress = { downloadProgress.coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.surfaceContainer
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                TextButton(
                                    onClick = {
                                        settings.ignoreVersion = profile!!.version
                                        dismiss()
                                    },
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) {
                                    Text(
                                        readI18n("settings.ignoreVersion"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    readI18n("settings.updateContent", I18nType.RWPP),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.align(Alignment.Start)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                BorderCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
                                ) {
                                    Markdown(
                                        profile!!.body,
                                        modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                        colors = markdownColor(),
                                        typography = markdownTypography()
                                    )
                                }
                            }

                            ExitButton { dismiss() }
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
                        if(showRoomView) {
                            showRoomView = false
                            showMultiplayerView = true
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

                var reloadingModViewVisible by remember { mutableStateOf(false) }
                GlobalEventChannel.filter(ReloadModEvent::class).onDispose {
                    subscribeAlways(Dispatchers.Main.immediate) {
                        reloadingModViewVisible = true
                    }
                }

                GlobalEventChannel.filter(ReloadModFinishedEvent::class).onDispose {
                    subscribeAlways(Dispatchers.Main.immediate) {
                        reloadingModViewVisible = false
                    }
                }

                LoadingView(reloadingModViewVisible, onLoaded = {}) { null }

                AnimatedAlertDialog(
                    UI.showNetworkDialog,
                    {
                        UI.showNetworkDialog = false
                        UI.receivingNetworkDialogTitle = ""
                        val game = appKoin.get<Game>()
                        game.gameRoom.disconnect("refuse to receive network data.")
                        UI.showMultiplayerView = true
                        UI.showRoomView = false
                    }, enableDismiss = true
                ) { dismiss ->
                    BorderCard(modifier = Modifier.size(500.dp)) {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            LineSpinFadeLoaderIndicator(MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(
                                UI.receivingNetworkDialogTitle,
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(20.dp).offset(y = 50.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
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
