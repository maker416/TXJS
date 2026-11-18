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
import kotlinx.coroutines.*
import org.koin.compose.koinInject

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
    val controller = remember(approved, session, platformUrl, appKey) {
        val client = GamePortalClient(platformUrl, appKey, AccountSession.httpOverride ?: appKoin.get<Net>().client)
        GamePortalController(
            approved = approved,
            currentSession = { launcherPage == route && AccountSession.isCurrentSession(session) &&
                resolveOpenPlatformUrl() == platformUrl && resolveAccountAppKey(prefs.appKey) == appKey },
            refresh = { withContext(Dispatchers.IO) { OnlineGameClient(platformUrl).game(appId) } },
            player = { client.player(session.token, appId, approved.portalRevision) },
            authorize = { client.authorize(session.token, appId, approved.portalRevision, it) },
            needsConsent = { current ->
                val expansion = current.expansionBinding
                if (expansion != null) {
                    ExpansionPackages.refresh()
                    ExpansionPackages.installed.none { it.gameAppId == appId && it.binding == expansion }
                } else OnlineGameThemeController.requiresDownload(current)
            },
            prepare = { current, art -> if (art) OnlineGameThemeController.prepare(current) else OnlineGameThemeController.useDefault(current) },
            cancelPrepared = { OnlineGameThemeController.cancelPrepared(it) },
            cancelNative = { ModSyncController.cancelJoinEntry(); game.cancelJoinServer() },
            scope = scope,
        )
    }
    val browser = remember(controller) { EmbeddedBrowserState(approved.portalUrl, gamePortalBridge = controller) }
    DisposableEffect(controller, browser) {
        controller.bindEvents(browser::emitPortalEvent)
        onDispose { controller.close() }
    }
    LaunchedEffect(active) { if (!active) controller.close() }
    val state by controller.state.collectAsState()
    BrowserPage(route, browser, onExit = { controller.close(); onExit() }, clientLogin = null,
        nativeBusy = state.attempt != null || state.error != null)

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
    AnimatedAlertDialog(true, onDismissRequest = { consent.decision.complete(false) }) { _ ->
        Surface {
            Column(Modifier.widthIn(max = 500.dp).padding(24.dp).testTag("game-portal-content-consent"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(consent.game.name, style = MaterialTheme.typography.titleLarge)
                Text(readI18n(if (consent.required) "gamePortal.expansionConsent" else "gamePortal.artConsent"))
                consent.game.expansionBinding?.let { Text("${it.version} · ${expansionDisplaySize(it.size)}") }
                    ?: consent.game.artBinding?.let { Text(it.version) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { consent.decision.complete(false) }, modifier = Modifier.testTag("portal-content-decline")) {
                        Text(readI18n(if (consent.required) "gamePortal.cancel" else "gamePortal.useDefault"))
                    }
                    Button(onClick = { consent.decision.complete(true) }, modifier = Modifier.testTag("portal-content-accept")) {
                        Text(readI18n("gamePortal.download"))
                    }
                }
            }
        }
    }
}
