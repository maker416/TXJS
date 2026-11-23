/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.AccountSessionSnapshot
import io.github.rwpp.appKoin
import io.github.rwpp.config.*
import io.github.rwpp.content.ExpansionPackages
import io.github.rwpp.content.PortalExpansionChangedException
import io.github.rwpp.content.PortalExpansionHandle
import io.github.rwpp.content.PortalExpansionKey
import io.github.rwpp.content.PortalExpansionPausedGameException
import io.github.rwpp.content.PortalExpansionProgress
import io.github.rwpp.content.PortalExpansionStage
import io.github.rwpp.content.PortalExpansionSync
import io.github.rwpp.core.GameSessionController
import io.github.rwpp.core.LoadingContext
import io.github.rwpp.core.ModSyncController
import io.github.rwpp.game.Game
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.Net
import io.github.rwpp.net.ConnectionFailure
import io.github.rwpp.net.localized
import io.github.rwpp.net.games.*
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.platform.EmbeddedBrowserState
import io.github.rwpp.theme.OnlineGameThemeController
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.UiCircularProgressIndicator
import io.github.rwpp.widget.UiLinearProgressIndicator
import kotlinx.coroutines.*
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory

private val portalLogger = LoggerFactory.getLogger("GamePortal")

/** The website selects a server ID; approval, downloads, engine connection and admission stay native. */
@Composable
internal fun GamePortalView(appId: String, onExit: () -> Unit, onOpenRoom: () -> Unit) {
    val route = remember(appId) { LauncherPage.GamePortal(appId) }
    val session = remember(appId) { AccountSession.sessionSnapshotOrNull() }
    val platformUrl = resolveOpenPlatformUrl()
    var gameInfo by remember(appId, platformUrl) { mutableStateOf<OnlineGame?>(null) }
    var failed by remember(appId) { mutableStateOf(false) }
    var retry by remember(appId) { mutableIntStateOf(0) }
    val active = launcherPage == route
    LaunchedEffect(AccountSession.token, AccountSession.loggedIn, active) {
        if (active && (session == null || !AccountSession.isCurrentSession(session))) onExit()
    }
    LaunchedEffect(appId, platformUrl, retry) {
        if (session == null) return@LaunchedEffect
        failed = false
        try {
            gameInfo = withContext(Dispatchers.IO) { OnlineGameClient(platformUrl).game(appId) }
                ?.takeIf { it.portalRevision > 0 && isOnlineGamePortalUrl(it.portalUrl) }
            failed = gameInfo == null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val approved = gameInfo
    if (approved == null || session == null) {
        BackHandler(active, onExit)
        Column(Modifier.fillMaxSize().padding(24.dp).testTag("game-portal-unavailable"),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (!failed && session != null) UiCircularProgressIndicator()
            Text(readI18n(if (failed) "gamePortal.unavailable" else "gamePortal.loading"))
            if (failed) TextButton(onClick = { retry++ }) { Text(readI18n("browser.refresh")) }
            TextButton(onClick = onExit) { Text(readI18n("gamePortal.back")) }
        }
        return
    }

    val config = koinInject<ConfigIO>()
    val multiplayer = koinInject<MultiplayerPreferences>()
    PortalExpansionEntry(approved, platformUrl, multiplayer, { config.saveConfig(multiplayer) }, active, onExit) {
        GamePortalHomepage(approved, platformUrl, route, session, config, onExit, onOpenRoom, onRefresh = {
            gameInfo = null
            retry++
        })
    }
}

@Composable
internal fun PortalExpansionEntry(
    game: OnlineGame,
    platformUrl: String,
    preferences: MultiplayerPreferences,
    savePreferences: () -> Unit,
    active: Boolean,
    onDecline: () -> Unit,
    content: @Composable () -> Unit,
) {
    val binding = game.expansionBinding
    if (binding != null && game.expansionSyncEndpoint != null) {
        val gameKey = "$platformUrl/${game.appId}"
        var accepted by remember(gameKey, binding, preferences) {
            mutableStateOf(preferences.acceptedPortalExpansions[gameKey] == binding)
        }
        if (!accepted) {
            BackHandler(active, onDecline)
            if (active) PortalExpansionConsentDialog(game, onDecline, onAccept = {
                preferences.acceptedPortalExpansions += gameKey to binding
                runCatching(savePreferences).onFailure { portalLogger.warn("Could not save expansion consent", it) }
                accepted = true
            })
            return
        }
    }
    content()
}

@Composable
private fun GamePortalHomepage(
    approved: OnlineGame,
    platformUrl: String,
    route: LauncherPage.GamePortal,
    session: AccountSessionSnapshot,
    config: ConfigIO,
    onExit: () -> Unit,
    onOpenRoom: () -> Unit,
    onRefresh: () -> Unit,
) {
    val appId = approved.appId
    val active = launcherPage == route
    val game = koinInject<Game>()
    val prefs = koinInject<AccountPreferences>()
    val scope = rememberCoroutineScope()
    val appKey = resolveAccountAppKey(prefs.appKey)
    val expansionKey = remember(approved) { PortalExpansionKey.from(approved) }

    var expansion by remember(approved, session, platformUrl, appKey) {
        mutableStateOf<PortalExpansionState?>(expansionKey?.let { PortalExpansionState.Syncing() })
    }
    var syncRetry by remember(approved, session, platformUrl, appKey) { mutableIntStateOf(0) }
    var joinDuringSync by remember(approved, session, syncRetry) { mutableStateOf(false) }
    val refresh = remember(platformUrl, appId) {
        suspend { withContext(Dispatchers.IO) { OnlineGameClient(platformUrl).game(appId) } }
    }
    val controller = remember(approved, session, platformUrl, appKey) {
        val client = GamePortalClient(platformUrl, appKey, AccountSession.httpOverride ?: appKoin.get<Net>().client)
        GamePortalController(
            approved = approved,
            currentSession = { launcherPage == route && AccountSession.isCurrentSession(session) &&
                resolveOpenPlatformUrl() == platformUrl && resolveAccountAppKey(prefs.appKey) == appKey },
            refresh = refresh,
            player = { client.player(session.token, appId, approved.portalRevision) },
            authorize = { client.authorize(session.token, appId, approved.portalRevision, it) },
            needsConsent = { current ->
                val expansion = current.expansionBinding
                if (expansionKey != null) false
                else if (expansion != null) {
                    ExpansionPackages.refresh()
                    ExpansionPackages.installed.none { it.gameAppId == appId && it.binding == expansion }
                } else OnlineGameThemeController.requiresDownload(current)
            },
            prepare = { current, art -> if (art) OnlineGameThemeController.prepare(current) else OnlineGameThemeController.useDefault(current) },
            cancelPrepared = { OnlineGameThemeController.cancelPrepared(it) },
            cancelNative = { ModSyncController.cancelJoinEntry(); game.cancelJoinServer() },
            scope = scope,
            expansionJoinBlock = { expansion?.joinRejection() },
            preloadedExpansion = { (expansion as? PortalExpansionState.Ready)?.handle },
            onExpansionJoinBlocked = { code ->
                if (code == "expansion_sync_required") expansion = PortalExpansionState.Failed(PortalExpansionFailure.CHANGED)
                if (code == "expansion_sync_in_progress") joinDuringSync = true
            },
            onExpansionChanged = {
                expansion = PortalExpansionState.Failed(PortalExpansionFailure.CHANGED)
            },
        )
    }
    val browser = remember(controller) { EmbeddedBrowserState(approved.portalUrl, gamePortalBridge = controller) }
    DisposableEffect(controller, browser) {
        controller.bindEvents(browser::emitPortalEvent)
        onDispose { controller.close() }
    }
    LaunchedEffect(active) { if (!active) controller.close() }
    val invalidated = (expansion as? PortalExpansionState.Failed)?.reason == PortalExpansionFailure.CHANGED
    LaunchedEffect(controller, active, syncRetry, invalidated) {
        if (!active || invalidated || expansionKey == null) return@LaunchedEffect
        GameSessionController.withPortalContent(controller::isActive) {
            expansion = PortalExpansionState.Syncing()
            var handle: PortalExpansionHandle? = null
            try {
                val loaded = PortalExpansionSync.load(approved, controller::isActive, refresh) { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        expansion = PortalExpansionState.Syncing(progress)
                        if (progress.stage == PortalExpansionStage.RELOADING) repeat(2) { withFrameNanos {} }
                    }
                }
                handle = loaded
                expansion = PortalExpansionState.Ready(loaded)
                awaitCancellation()
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                expansion = PortalExpansionState.Failed(PortalExpansionFailure.SYNC)
            } catch (error: OutOfMemoryError) {
                io.github.rwpp.game.mod.ModLoadSafety.recordMemoryFailure("portal-expansion")
                expansion = PortalExpansionState.Failed(PortalExpansionFailure.SYNC)
            } catch (error: Exception) {
                expansion = PortalExpansionState.Failed(when (error) {
                    is PortalExpansionChangedException -> PortalExpansionFailure.CHANGED
                    is PortalExpansionPausedGameException -> PortalExpansionFailure.PAUSED_GAME
                    else -> PortalExpansionFailure.SYNC
                })
            } finally {
                handle?.let { loaded ->
                    withContext(NonCancellable) {
                        if ((expansion as? PortalExpansionState.Failed)?.reason == PortalExpansionFailure.CHANGED) {
                            controller.cancelJoin()
                        } else {
                            controller.close()
                        }
                        controller.awaitJoinCleanup()
                        try {
                            loaded.close()
                        } catch (error: Exception) {
                            portalLogger.error("Could not restore game content after closing the portal", error)
                            UI.showWarning(readI18n("gamePortal.cleanupFailed"))
                        }
                    }
                }
            }
        }
    }
    val state by controller.state.collectAsState()
    val attempt = state.attempt
    val ready = state.ready
    var joinMessage by remember(attempt) { mutableStateOf("") }
    BrowserPage(
        route, browser, onExit = { controller.close(); onExit() }, clientLogin = null,
        nativeBusy = state.consent != null,
        topContent = {
            PortalExpansionStatus(expansion, joinDuringSync = joinDuringSync, onRetry = {
                if (invalidated) {
                    onRefresh()
                } else {
                    expansion = PortalExpansionState.Syncing()
                    syncRetry++
                }
            })
            PortalJoinStatus(state, joinMessage, controller::cancelJoin, controller::dismissError)
        },
    )

    state.consent?.let { consent -> PortalContentConsentDialog(consent) }
    val failureAttempt = remember(attempt) { ConnectionFailureAttempt() }
    DisposableEffect(attempt) {
        val release = if (attempt != null) UI.captureConnectionFailures { failureAttempt.report(it) } else null
        onDispose { failureAttempt.finish(); release?.invoke() }
    }
    LaunchedEffect(controller, active, attempt, ready, state.cancelling) {
        if (!active || attempt == null || ready == null || state.cancelling) return@LaunchedEffect
        controller.attachNative(attempt, currentCoroutineContext().job)
        val loading = object : LoadingContext {
            override fun message(str: String) {
                scope.launch(Dispatchers.Main.immediate) {
                    if (controller.isCurrent(attempt)) joinMessage = str
                }
            }
            override fun failure(failure: ConnectionFailure) { failureAttempt.report(failure) }
        }
        var opened = false
        val success = try {
            withContext(Dispatchers.IO) {
                loading.joinNativeRoom(
                    NativeRoomEntry(ready.endpoint, userName = AccountSession.displayName, onlineGame = ready,
                        returnPage = route),
                    game, config,
                    isCurrent = { controller.isCurrent(attempt) },
                    currentFailure = { failureAttempt.failure },
                    revalidate = { controller.validateJoin(attempt) },
                    onOpenRoom = {
                        // Commit before navigation disposes this browser and invalidates its bridge.
                        opened = true
                        failureAttempt.finish()
                        controller.finished(attempt, true)
                        onOpenRoom()
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            controller.cancelJoin()
            return@LaunchedEffect
        }
        catch (_: Exception) { false }
        if (!opened) {
            // A portal failure remains on its webpage; no detached relay retry may open a later room.
            ModSyncController.cancelJoinEntry()
            controller.finished(attempt, success, failureAttempt.failure)
        }
    }
}

@Composable
internal fun PortalJoinStatus(state: GamePortalUiState, message: String, onCancel: () -> Unit, onDismiss: () -> Unit) {
    if (state.consent != null || (state.attempt == null && state.error == null)) return
    val failure = state.failure?.localized()
    var showDetails by remember(state.failure) { mutableStateOf(false) }
    val label = when {
        state.error == "cleanup_failed" -> readI18n("gamePortal.cleanupFailed")
        state.cancelling -> readI18n("gamePortal.cancelling")
        failure != null -> failure.title + " · " + failure.explanation
        state.error == "content_declined" -> readI18n("gamePortal.contentDeclined")
        state.error != null -> readI18n("gamePortal.joinFailed")
        state.ready == null -> readI18n("gamePortal.preparing")
        message.isBlank() || message.equals("connecting...", ignoreCase = true) -> readI18n("gamePortal.connecting")
        else -> message
    }
    Surface(Modifier.fillMaxWidth().testTag("portal-join-status"),
        color = if (state.error == null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f).padding(vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                if (failure != null && !state.cancelling) {
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(readI18n(if (showDetails) "connectionError.hideDetails" else "connectionError.showDetails"))
                    }
                }
                if (!state.cancelling) {
                    if (state.error == null) {
                        TextButton(onClick = onCancel, modifier = Modifier.testTag("portal-join-cancel")) { Text(readI18n("gamePortal.cancel")) }
                    } else {
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("portal-join-dismiss")) { Text(readI18n("common.close")) }
                    }
                }
            }
            if (state.error == null) {
                UiLinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp).testTag("portal-join-progress"))
            } else if (showDetails && failure != null && !state.cancelling) {
                Column(Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(failure.suggestion, style = MaterialTheme.typography.bodySmall)
                    val details = state.failure?.details.orEmpty()
                    if (details.isNotBlank()) {
                        val clipboard = LocalClipboardManager.current
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(readI18n("connectionError.details"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { clipboard.setText(AnnotatedString(details)) }) {
                                Text(readI18n("inject.copyDetails"))
                            }
                        }
                        SelectionContainer { Text(details, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PortalContentConsentDialog(consent: PortalContentConsent) {
    PortalContentConsentDialog(
        game = consent.game,
        description = readI18n(if (consent.required) "gamePortal.expansionConsent" else "gamePortal.artConsent"),
        declineText = readI18n(if (consent.required) "gamePortal.cancel" else "gamePortal.useDefault"),
        acceptText = readI18n("gamePortal.download"),
        onDecline = { consent.decision.complete(false) },
        onAccept = { consent.decision.complete(true) },
    )
}

@Composable
internal fun PortalExpansionConsentDialog(game: OnlineGame, onDecline: () -> Unit, onAccept: () -> Unit) {
    PortalContentConsentDialog(
        game = game,
        description = readI18n("gamePortal.expansionPreloadConsent"),
        declineText = readI18n("common.cancel"),
        acceptText = readI18n("gamePortal.expansionPreloadAccept"),
        onDecline = onDecline,
        onAccept = onAccept,
    )
}

@Composable
private fun PortalContentConsentDialog(
    game: OnlineGame,
    description: String,
    declineText: String,
    acceptText: String,
    onDecline: () -> Unit,
    onAccept: () -> Unit,
) {
    AnimatedAlertDialog(true, onDismissRequest = onDecline) { _ ->
        Surface {
            Column(Modifier.widthIn(max = 500.dp).padding(24.dp).testTag("game-portal-content-consent"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(game.name, style = MaterialTheme.typography.titleLarge)
                Text(description)
                game.expansionBinding?.let { Text("${it.version} · ${expansionDisplaySize(it.size)}") }
                    ?: game.artBinding?.let { Text(it.version) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDecline, modifier = Modifier.testTag("portal-content-decline")) {
                        Text(declineText)
                    }
                    Button(onClick = onAccept, modifier = Modifier.testTag("portal-content-accept")) {
                        Text(acceptText)
                    }
                }
            }
        }
    }
}

internal enum class PortalExpansionFailure { SYNC, CHANGED, PAUSED_GAME }

internal sealed interface PortalExpansionState {
    data class Syncing(val progress: PortalExpansionProgress = PortalExpansionProgress(PortalExpansionStage.CHECKING)) : PortalExpansionState
    data class Ready(val handle: PortalExpansionHandle) : PortalExpansionState
    data class Failed(val reason: PortalExpansionFailure) : PortalExpansionState

    fun joinRejection(): String? = when (this) {
        is Syncing -> "expansion_sync_in_progress"
        is Ready -> if (handle.isReady()) null else "expansion_sync_required"
        is Failed -> if (reason == PortalExpansionFailure.CHANGED) "expansion_sync_required" else "expansion_sync_failed"
    }
}

@Composable
internal fun PortalExpansionStatus(state: PortalExpansionState?, joinDuringSync: Boolean = false, onRetry: () -> Unit) {
    when (state) {
        is PortalExpansionState.Syncing -> {
            val progress = state.progress
            val label = readI18n(when (progress.stage) {
                PortalExpansionStage.CHECKING -> "gamePortal.expansionSyncChecking"
                PortalExpansionStage.DOWNLOADING -> "gamePortal.expansionSyncDownloading"
                PortalExpansionStage.VERIFYING -> "gamePortal.expansionSyncVerifying"
                PortalExpansionStage.INSTALLING -> "gamePortal.expansionSyncInstalling"
                PortalExpansionStage.RELOADING -> "gamePortal.expansionSyncReloading"
            })
            val fraction = if (progress.stage == PortalExpansionStage.DOWNLOADING && progress.total > 0) {
                (progress.bytes.toDouble() / progress.total).coerceIn(0.0, 1.0).toFloat()
            } else null
            Surface(Modifier.fillMaxWidth().testTag("portal-expansion-sync"), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (joinDuringSync) Text(readI18n("gamePortal.expansionSyncWait"), style = MaterialTheme.typography.labelMedium)
                    Text(if (fraction == null) label else "$label · ${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium)
                    val bar = Modifier.fillMaxWidth().height(3.dp)
                    if (fraction == null) UiLinearProgressIndicator(modifier = bar)
                    else LinearProgressIndicator(progress = { fraction }, modifier = bar)
                }
            }
        }
        is PortalExpansionState.Failed -> {
            val label = readI18n(when (state.reason) {
                PortalExpansionFailure.SYNC -> "gamePortal.expansionSyncFailed"
                PortalExpansionFailure.CHANGED -> "gamePortal.expansionSyncChanged"
                PortalExpansionFailure.PAUSED_GAME -> "gamePortal.expansionSyncPaused"
            })
            Surface(Modifier.fillMaxWidth().testTag("portal-expansion-failed"), color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("portal-expansion-retry")) {
                        Text(readI18n(if (state.reason == PortalExpansionFailure.CHANGED) "browser.refresh" else "gamePortal.expansionSyncRetry"))
                    }
                }
            }
        }
        else -> Unit
    }
}
