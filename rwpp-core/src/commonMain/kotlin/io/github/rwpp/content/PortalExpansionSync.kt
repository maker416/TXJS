/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.content

import io.github.rwpp.appKoin
import io.github.rwpp.core.GameSessionController
import io.github.rwpp.expansion.ExpansionBinding
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.ModLoadSafety
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.gameVersion
import io.github.rwpp.net.games.OnlineGame
import io.github.rwpp.net.games.isPublicExpansionSyncEndpoint
import io.github.rwpp.net.sync.direct.DIRECT_SYNC_ONLY_CAPABILITY
import io.github.rwpp.net.sync.direct.DirectJoinEndpoint
import io.github.rwpp.net.sync.direct.DirectModDownloadPhase
import io.github.rwpp.net.sync.direct.DirectModManifest
import io.github.rwpp.net.sync.direct.DirectModSyncClient
import io.github.rwpp.net.sync.direct.DirectModSyncTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress

internal data class PortalExpansionKey(val appId: String, val binding: ExpansionBinding, val endpoint: String) {
    companion object {
        fun from(game: OnlineGame?): PortalExpansionKey? = game?.let {
            PortalExpansionKey(it.appId, it.expansionBinding ?: return null, it.expansionSyncEndpoint ?: return null)
        }
    }
}

internal enum class PortalExpansionStage { CHECKING, DOWNLOADING, VERIFYING, INSTALLING, RELOADING }
internal data class PortalExpansionProgress(val stage: PortalExpansionStage, val bytes: Long = 0, val total: Long = 0)
internal class PortalExpansionChangedException : IOException("The approved expansion source or release changed")
internal class PortalExpansionPausedGameException : IOException("There is a single-player game to continue")

/** Ownership stays with the portal until the native room entry commits on Main. */
class PortalExpansionHandle internal constructor(
    internal val key: PortalExpansionKey,
    internal val token: Long,
    private val loadedChecksum: Int,
) {
    fun isReady(): Boolean =
        ExpansionContentController.portalToken == token && ExpansionContentController.isActive(key.binding.sha256)

    internal suspend fun validateRoom(
        manifest: DirectModManifest,
        transport: DirectModSyncTransport,
        expected: ExpansionJoinExpectation?,
        serverId: String?,
    ): Long {
        val artifact = manifest.artifact ?: throw PortalExpansionChangedException()
        if (expected != ExpansionJoinExpectation(key.appId, key.binding) || serverId == null) {
            throw PortalExpansionChangedException()
        }
        ExpansionDirectSync.authorize(artifact, transport, expected, serverId)
        withContext(Dispatchers.Main.immediate) {
            if (!isReady() || loadedChecksum != appKoin.get<Game>().getUnitsChecksum()) {
                throw PortalExpansionChangedException()
            }
        }
        return token
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        GameSessionController.withContentSessionLock {
            if (ExpansionContentController.portalToken == token) ExpansionContentController.cancel(token)
        }
    }
}

internal object PortalExpansionSync {
    suspend fun load(
        approved: OnlineGame,
        isCurrent: () -> Boolean,
        refresh: suspend () -> OnlineGame?,
        progress: suspend (PortalExpansionProgress) -> Unit,
    ): PortalExpansionHandle {
        val key = requireNotNull(PortalExpansionKey.from(approved))
        fun checkCurrent() {
            if (!isCurrent()) throw CancellationException("Portal session expired")
        }
        suspend fun checkApproval() {
            checkCurrent()
            val current = refresh()
            checkCurrent()
            if (current == null || current.portalUrl != approved.portalUrl ||
                current.portalRevision != approved.portalRevision || PortalExpansionKey.from(current) != key) {
                throw PortalExpansionChangedException()
            }
        }
        progress(PortalExpansionProgress(PortalExpansionStage.CHECKING))
        checkApproval()
        if (!isPublicExpansionSyncEndpoint(key.endpoint)) throw IOException("Invalid expansion sync endpoint")
        val endpoint = requireNotNull(DirectJoinEndpoint.parseOrNull(key.endpoint))
        val transport = DirectModSyncClient(endpoint, appKoin.get<NetworkModCache>(), preferredProtocolVersion = 4)
        val artifact = requirePortalExpansionManifest(transport.getManifest(), key)
        val peer = transport.peerEndpoint?.let(DirectJoinEndpoint::parseOrNull)
        if (peer == null || peer.port != endpoint.port ||
            InetAddress.getByName(peer.host) != InetAddress.getByName(endpoint.host)) {
            throw IOException("Unexpected expansion sync peer")
        }

        ExpansionPackages.refresh()
        val installed = ExpansionPackages.installed.singleOrNull { it.gameAppId == key.appId && it.binding == key.binding }
            ?: run {
                val archive = transport.downloadArtifact(artifact, ExpansionPackages.rootDir.resolve(".transfers")) {
                    val stage = when (it.phase) {
                        DirectModDownloadPhase.DOWNLOADING -> PortalExpansionStage.DOWNLOADING
                        DirectModDownloadPhase.VERIFYING -> PortalExpansionStage.VERIFYING
                        DirectModDownloadPhase.CACHING -> PortalExpansionStage.INSTALLING
                    }
                    progress(PortalExpansionProgress(stage, it.downloadedBytes, it.totalBytes))
                }
                try {
                    checkApproval()
                    progress(PortalExpansionProgress(PortalExpansionStage.INSTALLING))
                    ExpansionPackages.install(archive, key.appId, key.binding)
                } finally { archive.delete() }
            }

        var token: Long? = null
        try {
            return withContext(Dispatchers.Main.immediate) {
                GameSessionController.withContentSessionLock {
                    GameSessionController.awaitSessionClose()
                    val game = appKoin.get<Game>()
                    if (game.isGameCouldContinue()) throw PortalExpansionPausedGameException()
                    val room = game.gameRoom
                    if (GameSessionController.sessionPhase != GameSessionController.SessionPhase.IDLE ||
                        room.isConnecting || room.isStartingGame || room.isStartGame) {
                        throw IOException("Leave the current game before loading the expansion")
                    }
                    checkApproval()
                    progress(PortalExpansionProgress(PortalExpansionStage.VERIFYING))
                    val prepared = ExpansionContentController.prepareForPortal(installed, key.appId)
                    token = prepared
                    try {
                        progress(PortalExpansionProgress(PortalExpansionStage.RELOADING))
                        checkCurrent()
                        ExpansionContentController.activate(prepared)
                        val checksum = game.getUnitsChecksum()
                        checkApproval()
                        PortalExpansionHandle(key, prepared, checksum)
                    } catch (error: Throwable) {
                        withContext(NonCancellable) {
                            runCatching { ExpansionContentController.cancel(prepared) }.exceptionOrNull()?.let(error::addSuppressed)
                        }
                        throw error
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is OutOfMemoryError) ModLoadSafety.recordMemoryFailure("portal-expansion")
            token?.let { abandoned ->
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    GameSessionController.withContentSessionLock {
                        if (ExpansionContentController.portalToken == abandoned) {
                            runCatching { ExpansionContentController.cancel(abandoned) }.exceptionOrNull()?.let(error::addSuppressed)
                        }
                    }
                }
            }
            throw error
        }
    }
}

internal fun requirePortalExpansionManifest(manifest: DirectModManifest, key: PortalExpansionKey) =
    manifest.artifact?.takeIf {
        manifest.protocolVersion == 4 && manifest.capabilities and DIRECT_SYNC_ONLY_CAPABILITY != 0 &&
            !manifest.lobby && manifest.phase == "unavailable" && manifest.status == "ready" &&
            manifest.gameVersion == gameVersion.toString() && manifest.hostUnitsChecksum == -1L &&
            manifest.contentRevision == key.binding.sha256 &&
            it.version == key.binding.version && it.size == key.binding.size && it.sha256 == key.binding.sha256
    } ?: throw IOException("The sync server does not serve the approved expansion")
