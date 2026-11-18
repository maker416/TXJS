/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import io.github.rwpp.event.EventPriority
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.DisconnectEvent
import io.github.rwpp.net.appearance.VerifiedGameServer
import io.github.rwpp.net.games.OnlineGame
import io.github.rwpp.net.games.OnlineGameArtBinding
import io.github.rwpp.platform.checkUiThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** Explicit consent prepares a version; only the serialized join lifecycle applies it to the engine. */
object OnlineGameThemeController {
    private class Prepared(val token: Long, val game: OnlineGame, val theme: ArtThemeController.LoadedTheme?, val defaults: Boolean) {
        var selection: ArtThemeController.TemporarySelection? = null
        var opened = false
    }

    private var sequence = 0L
    private var prepared: Prepared? = null
    private var eventsBound = false

    fun init() {
        if (eventsBound) return
        eventsBound = true
        GlobalEventChannel.filter(DisconnectEvent::class)
            .subscribeAlways(Dispatchers.Main.immediate, priority = EventPriority.MONITOR) {
                val session = prepared
                if (session?.opened == true) cancelPrepared(session.token)
            }
    }

    suspend fun prepare(game: OnlineGame): Long = prepare(game, defaults = false)
    suspend fun useDefault(game: OnlineGame): Long = prepare(game, defaults = true)

    /** A cached/installed verified release needs no new download permission. */
    suspend fun requiresDownload(game: OnlineGame): Boolean = withContext(Dispatchers.IO) {
        val binding = game.artBinding ?: return@withContext false
        ArtThemeController.scan()
        ArtThemeController.installedThemes.none { matches(binding, it) } &&
            ArtThemeController.loadTheme(ArtThemeController.onlineGameCacheRoot.resolve(binding.packId).resolve(binding.catalogHash))
                ?.let { matches(binding, it) } != true
    }

    private suspend fun prepare(game: OnlineGame, defaults: Boolean): Long {
        val token = withContext(Dispatchers.Main.immediate) {
            if (prepared?.selection != null) throw IOException("Leave the current game before preparing another appearance")
            prepared = null
            ++sequence
        }
        val theme = if (defaults || game.artBinding == null) null else {
            val binding = requireNotNull(game.artBinding)
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                ArtThemeController.scan()
                ArtThemeController.installedThemes.firstOrNull { matches(binding, it) }
                    ?: run {
                        val cached = ArtThemeController.onlineGameCacheRoot.resolve(binding.packId).resolve(binding.catalogHash)
                        val existing = ArtThemeController.loadTheme(cached)?.takeIf { matches(binding, it) }
                        existing ?: run {
                            if (binding.downloadUrl.isBlank()) throw IOException("The required art package is not installed")
                            val operation = currentCoroutineContext()
                            val dir = OnlineGameThemePackage().install(binding, ArtThemeController.onlineGameCacheRoot) { operation.ensureActive() }
                            currentCoroutineContext().ensureActive()
                            ArtThemeController.loadTheme(dir)?.takeIf { matches(binding, it) }
                                ?: throw IOException("The art package does not match the approved release")
                        }
                    }
            }
        }
        return withContext(Dispatchers.Main.immediate) {
            if (sequence != token) throw CancellationException("Game appearance preparation superseded")
            prepared = Prepared(token, game, theme, defaults)
            token
        }
    }

    /** Called inside beginSession after the preceding disconnect/cleanup barrier, before any game connection. */
    suspend fun activatePrepared(token: Long) = withContext(Dispatchers.Main.immediate) {
        val session = prepared?.takeIf { it.token == token } ?: throw IOException("Game appearance preparation expired")
        waitForOverlay()
        if (prepared !== session) throw CancellationException("Game appearance preparation superseded")
        if (session.selection != null) return@withContext
        session.selection = ArtThemeController.applyTemporary(session.theme, session.game.artBinding?.let {
            "online:${it.packId}:${it.catalogHash}"
        }) ?: throw IOException("Cannot apply the game art package during an active session")
        try {
            waitForOverlay()
            if (GameArtOverlayManager.failure != null) throw IOException("Game textures could not be prepared")
        } catch (error: Throwable) {
            withContext(NonCancellable) { cancelPrepared(token) }
            throw error
        }
    }

    fun onRoomOpened(token: Long) {
        checkUiThread()
        prepared?.takeIf { it.token == token }?.opened = true
    }

    /** The attempt token prevents a late failed join from restoring over a newer game's selection. */
    suspend fun cancelPrepared(token: Long) = withContext(Dispatchers.Main.immediate) {
        val session = prepared?.takeIf { it.token == token } ?: return@withContext
        val selection = session.selection
        if (selection != null) {
            waitForOverlay()
            if (prepared !== session) return@withContext
            if (!ArtThemeController.restoreTemporary(selection)) {
                throw IOException("Cannot restore the previous appearance before the game disconnects")
            }
            waitForOverlay()
        }
        if (prepared === session) {
            prepared = null
            ++sequence
        }
    }

    /** A catalog declaration or directory selection alone never proves which server the player joined. */
    internal fun permitsServer(server: VerifiedGameServer): Boolean {
        val session = prepared?.takeIf { it.selection != null } ?: return true
        return server.gameAppId == session.game.appId && session.game.servers.any { it.serverId == server.serverId }
    }

    internal fun permitsAppearance(server: VerifiedGameServer, theme: ArtThemeController.LoadedTheme): Boolean {
        if (!permitsServer(server)) return false
        val session = prepared?.takeIf { it.selection != null }
        if (session?.defaults == true) return false
        if (server.gameAppId == null) return session == null
        val binding = server.artBinding ?: return false
        return matches(binding, theme)
    }

    private fun matches(binding: OnlineGameArtBinding, theme: ArtThemeController.LoadedTheme): Boolean =
        OnlineGameThemePackage.validBinding(binding) && theme.spec.personalization.platformPackId == binding.packId &&
            theme.spec.theme.version == binding.version && theme.personalizedCatalog?.catalogHash == binding.catalogHash

    private suspend fun waitForOverlay() = withTimeout(90_000) {
        while (GameArtOverlayManager.applying) delay(25)
    }
}
