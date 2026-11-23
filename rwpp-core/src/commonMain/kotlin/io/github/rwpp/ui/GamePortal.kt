/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.rwpp.account.AccountSession
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
import io.github.rwpp.core.ModSyncController
import io.github.rwpp.game.Game
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.Net
import io.github.rwpp.net.games.*
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.platform.EmbeddedBrowserState
import io.github.rwpp.theme.OnlineGameThemeController
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.ConnectionFailureCard
import io.github.rwpp.widget.LoadingView
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
    var gameInfo by remember(appId) { mutableStateOf<OnlineGame?>(null) }
    var failed by remember(appId) { mutableStateOf(false) }
    var retry by remember(appId) { mutableIntStateOf(0) }
    val active = launcherPage == route
    LaunchedEffect(AccountSession.token, AccountSession.loggedIn, active) {
        if (active && (session == null || !AccountSession.isCurrentSession(session))) onExit()
    }
    LaunchedEffect(appId, retry) {
        if (session == null) return@LaunchedEffect
        failed = false
        try {
            gameInfo = withContext(Dispatchers.IO) { OnlineGameClient(resolveOpenPlatformUrl()).game(appId) }
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

    val game = koinInject<Game>()
    val config = koinInject<ConfigIO>()
    val prefs = koinInject<AccountPreferences>()
    val scope = rememberCoroutineScope()
    val platformUrl = resolveOpenPlatformUrl()
    val appKey = resolveAccountAppKey(prefs.appKey)
    val expansionKey = remember(approved) { PortalExpansionKey.from(approved) }
    var consented by remember(expansionKey, approved.portalUrl, approved.portalRevision, session, platformUrl, appKey) {
        mutableStateOf(false)
    }
    if (expansionKey != null && !consented) {
        BackHandler(active, onExit)
        if (active) PortalExpansionConsentDialog(approved, onDecline = onExit, onAccept = { consented = true })
        return
    }

    var expansion by remember(approved, session, platformUrl, appKey) {
        mutableStateOf<PortalExpansionState?>(expansionKey?.let { PortalExpansionState.Syncing() })
    }
    var syncRetry by remember(approved, session, platformUrl, appKey) { mutableIntStateOf(0) }
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
                UI.showWarning(readI18n(when (code) {
                    "expansion_sync_in_progress" -> "gamePortal.expansionSyncWait"
                    "expansion_sync_required" -> "gamePortal.expansionSyncChanged"
                    else -> "gamePortal.expansionSyncFailed"
                }))
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
    BrowserPage(
        route, browser, onExit = { controller.close(); onExit() }, clientLogin = null,
        nativeBusy = state.attempt != null || state.error != null,
        topContent = {
            PortalExpansionStatus(expansion, onRetry = {
                if (invalidated) {
                    consented = false
                    gameInfo = null
                    retry++
                } else {
                    expansion = PortalExpansionState.Syncing()
                    syncRetry++
                }
            })
        },
    )

    state.consent?.let { consent -> PortalContentConsentDialog(consent) }
    if (active && state.cancelling) {
        AnimatedAlertDialog(true, onDismissRequest = {}, enableDismiss = false) { _ ->
            Surface {
                Column(Modifier.widthIn(max = 460.dp).padding(24.dp).testTag("game-portal-cancelling"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    UiCircularProgressIndicator()
                    Text(readI18n(if (state.error == "cleanup_failed") "gamePortal.cleanupFailed" else "gamePortal.cancelling"))
                }
            }
        }
    }
    if (active && state.attempt != null && state.ready == null && state.consent == null && !state.cancelling) {
        AnimatedAlertDialog(true, onDismissRequest = controller::cancelJoin) { dismiss ->
            Surface {
                Column(Modifier.widthIn(max = 460.dp).padding(24.dp).testTag("game-portal-preparing"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    UiCircularProgressIndicator()
                    Text(readI18n("gamePortal.preparing"))
                    TextButton(onClick = dismiss) { Text(readI18n("gamePortal.cancel")) }
                }
            }
        }
    }
    val attempt = state.attempt
    val ready = state.ready
    val failureAttempt = remember(attempt) { ConnectionFailureAttempt() }
    DisposableEffect(attempt) {
        val release = if (attempt != null) UI.captureConnectionFailures { failureAttempt.report(it) } else null
        onDispose { failureAttempt.finish(); release?.invoke() }
    }
    LoadingView(
        visible = active && attempt != null && ready != null && !state.cancelling,
        onLoaded = { controller.cancelJoin() },
        cancellable = true,
        showProtectedModHint = true,
        showMemoryUsage = true,
        externalFailure = failureAttempt.failure,
        inlineDesktop = false,
    ) {
        if (attempt == null || ready == null) return@LoadingView true
        controller.attachNative(attempt, currentCoroutineContext().job)
        var opened = false
        val success = try {
            joinNativeRoom(
                NativeRoomEntry(ready.endpoint, userName = AccountSession.displayName, onlineGame = ready,
                    returnPage = LauncherPage.GameplayHall),
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
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        if (!opened) withContext(Dispatchers.Main.immediate) {
            // A portal failure remains on its webpage; no detached relay retry may open a later room.
            ModSyncController.cancelJoinEntry()
            controller.finished(attempt, success, failureAttempt.failure)
        }
        true
    }
    state.error?.takeIf { !state.cancelling }?.let {
        AnimatedAlertDialog(active, onDismissRequest = controller::dismissError) { dismiss ->
            state.failure?.let { ConnectionFailureCard(it, dismiss) } ?: Surface {
                Column(Modifier.widthIn(max = 460.dp).padding(24.dp).testTag("game-portal-join-error"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(readI18n(if (it == "content_declined") "gamePortal.contentDeclined" else "gamePortal.joinFailed"))
                    TextButton(onClick = dismiss) { Text(readI18n("common.ok")) }
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
internal fun PortalExpansionStatus(state: PortalExpansionState?, onRetry: () -> Unit) {
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
