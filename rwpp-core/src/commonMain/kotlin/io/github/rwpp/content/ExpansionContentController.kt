/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.content

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rwpp.appKoin
import io.github.rwpp.core.GameSessionController
import io.github.rwpp.event.EventPriority
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.DisconnectEvent
import io.github.rwpp.expansion.ExpansionPackageInstaller
import io.github.rwpp.expansion.InstalledExpansion
import io.github.rwpp.game.GameStartSafety
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.ContentModSource
import io.github.rwpp.game.mod.ContentProfile
import io.github.rwpp.game.mod.ContentProfiles
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.ModSettingsSnapshot
import io.github.rwpp.game.mod.requireModSelectionApplied
import io.github.rwpp.game.mod.snapshotModSelection
import io.github.rwpp.platform.checkUiThread
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.GameArtOverlayManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** A game-only content transaction; visible theme and active package commit only after native loading succeeds. */
object ExpansionContentController {
    private class Session(
        val token: Long,
        val installed: InstalledExpansion,
        val theme: ArtThemeController.LoadedTheme,
        var portalOwned: Boolean,
    ) {
        var settings: ModSettingsSnapshot? = null
        var selection: Map<String, Boolean>? = null
        var previousTheme: ArtThemeController.LoadedTheme? = null
        var previousKey: String? = null
        var themeHandle: ArtThemeController.TemporarySelection? = null
        var applying = false
        var engineTouched = false
        var opened = false
    }

    private var generation = 0L
    private var session: Session? = null
    private var eventsBound = false
    private val transaction = Mutex()
    var activeSha by mutableStateOf<String?>(null)
        private set
    val currentToken: Long? get() = session?.token
    val switching: Boolean get() = session?.applying == true
    internal val portalToken: Long? get() = session?.takeIf { it.portalOwned }?.token

    fun init() {
        if (eventsBound) return
        eventsBound = true
        // This subscriber is awaited by disconnectAndWait, so the next beginSession cannot overtake restoration.
        GlobalEventChannel.filter(DisconnectEvent::class)
            .subscribeAlways(Dispatchers.Main.immediate, priority = EventPriority.MONITOR) {
                session?.takeIf { it.opened }?.token?.let { cancel(it) }
            }
    }

    fun isActive(installedSha: String): Boolean = activeSha == installedSha &&
        (ContentProfiles.current as? ContentProfile.Expansion)?.revision == installedSha &&
        session?.let { it.theme === ArtThemeController.activeTheme && it.themeHandle != null } == true

    fun isReserved(installedSha: String): Boolean = session?.installed?.sha256 == installedSha

    suspend fun prepare(installed: InstalledExpansion, gameAppId: String): Long =
        prepare(installed, gameAppId, portalOwned = false)

    internal suspend fun prepareForPortal(installed: InstalledExpansion, gameAppId: String): Long =
        prepare(installed, gameAppId, portalOwned = true)

    private suspend fun prepare(installed: InstalledExpansion, gameAppId: String, portalOwned: Boolean): Long {
        if (installed.gameAppId != gameAppId) throw IOException("Expansion belongs to another game")
        var publishedToken: Long? = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                val operation = currentCoroutineContext()
                ExpansionPackageInstaller.verify(installed, operation::ensureActive)
                ArtThemeController.loadTheme(installed.themeDir) ?: throw IOException("Expansion theme is invalid")
            }
            return withContext(Dispatchers.Main.immediate) {
                val previous = session
                if (previous?.settings != null) {
                    if (!portalOwned && !previous.portalOwned && isActive(installed.sha256)) return@withContext previous.token
                    throw IOException("Leave the current game before preparing another expansion")
                }
                ExpansionPackages.rootDir // Establish the independent source root before the first scan.
                val token = ++generation
                session = Session(token, installed, loaded, portalOwned)
                publishedToken = token
                token
            }
        } catch (cancelled: CancellationException) {
            // Main may have published state while prompt cancellation prevents delivery of the token to its caller.
            val abandoned = publishedToken
            if (abandoned != null) withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (session?.token == abandoned && session?.settings == null) {
                    session = null
                    ++generation
                }
            }
            throw cancelled
        }
    }

    /** Called inside the existing serialized join after old cleanup, before any new native connection. */
    suspend fun activate(token: Long) = withContext(Dispatchers.Main.immediate) {
        transaction.withLock {
            val attempt = session?.takeIf { it.token == token } ?: throw IOException("Expansion preparation expired")
            if (isActive(attempt.installed.sha256)) return@withLock
            val phase = GameSessionController.sessionPhase
            check(if (attempt.portalOwned) phase == GameSessionController.SessionPhase.IDLE
                else phase == GameSessionController.SessionPhase.STARTING) {
                "Expansion packages can only be activated while joining their game"
            }
            waitForArt()
            if (attempt.portalOwned && appKoin.get<Game>().isGameCouldContinue()) throw PortalExpansionPausedGameException()
            GameStartSafety.requireCanReload()
            val manager = appKoin.get<ModManager>()
            val baseline = manager.captureModSettings()
            val selection = snapshotModSelection(manager.getAllMods().filterNot { it.isExpansionMod })
            attempt.settings = baseline
            attempt.selection = selection
            attempt.previousTheme = ArtThemeController.activeTheme
            attempt.previousKey = ArtThemeController.temporaryOverlayKey
            attempt.applying = true
            val profile = ContentProfile.Expansion(attempt.installed.gameAppId, attempt.installed.sha256,
                attempt.installed.manifest.mods.map { ContentModSource(it.id, attempt.installed.dir.resolve(it.path)) })
            try {
                // The attempt owns cleanup before the IO suspension, including cancellation after the atomic move.
                withContext(Dispatchers.IO) { ContentProfiles.writeRecovery(baseline, selection) }
                attempt.engineTouched = true
                GameArtOverlayManager.synchronizeContent(attempt.theme, "expansion:${attempt.installed.sha256}") {
                    manager.applyContentProfile(profile, refreshGameArt = true)
                }
                currentCoroutineContext().ensureActive()
                if (session !== attempt) throw CancellationException("Expansion session superseded")
                GameStartSafety.requireCanReload()
                val expected = profile.sources.associate { it.file.canonicalPath to true }
                requireModSelectionApplied(manager.getAllMods(), expected)
                check(manager.getAllMods().filter { it.isEnabled }.none { it.errorMessage != null }) { "Expansion Mod failed to load" }
                manager.restoreModSettings(baseline)
                attempt.themeHandle = ArtThemeController.applyTemporary(attempt.theme,
                    "expansion:${attempt.installed.sha256}", synchronizeOverlay = false)
                    ?: throw IOException("Cannot commit expansion theme")
                activeSha = attempt.installed.sha256
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    if (!GameStartSafety.requiresRestart) runCatching { restore(attempt, manager) }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            } finally { attempt.applying = false }
        }
    }

    fun onRoomOpened(token: Long) {
        checkUiThread()
        session?.takeIf { it.token == token && activeSha == it.installed.sha256 }?.let {
            it.portalOwned = false
            it.opened = true
        }
    }

    suspend fun cancel(token: Long) = withContext(Dispatchers.Main.immediate) {
        transaction.withLock {
            val attempt = session?.takeIf { it.token == token } ?: return@withLock
            if (attempt.settings != null) {
                GameStartSafety.requireCanReload()
                waitForArt()
                attempt.applying = true
                try { restore(attempt, appKoin.get()) } finally { attempt.applying = false }
            } else {
                session = null
                ++generation
            }
        }
    }

    private suspend fun restore(attempt: Session, manager: ModManager) {
        if (session !== attempt) return
        val baseline = attempt.settings ?: return
        val selection = attempt.selection ?: emptyMap()
        GameStartSafety.requireCanReload()
        if (attempt.engineTouched) {
            waitForArt()
            GameArtOverlayManager.synchronizeContent(attempt.previousTheme, attempt.previousKey) {
                manager.applyContentProfile(ContentProfile.Normal(selection), refreshGameArt = true)
            }
            manager.restoreModSettings(baseline)
            attempt.themeHandle?.let { handle ->
                check(ArtThemeController.restoreTemporary(handle, synchronizeOverlay = false)) { "Cannot restore ordinary theme" }
            }
        }
        ContentProfiles.finishNormal()
        withContext(Dispatchers.IO) { ContentProfiles.clearRecovery() }
        if (session === attempt) {
            session = null
            activeSha = null
            ++generation
        }
    }

    private suspend fun waitForArt() = withTimeout(90_000) {
        while (GameArtOverlayManager.applying) delay(25)
    }
}
