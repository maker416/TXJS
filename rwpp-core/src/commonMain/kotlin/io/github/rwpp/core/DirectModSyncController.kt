/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.content.PortalExpansionHandle
import io.github.rwpp.net.sync.direct.DIRECT_SYNC_ONLY_CAPABILITY

import io.github.rwpp.appKoin
import io.github.rwpp.game.Game
import io.github.rwpp.game.GameStartSafety
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.game.mod.findMatchingModSyncKeys
import io.github.rwpp.game.mod.isEnabledModSetExact
import io.github.rwpp.game.mod.selectModSyncFiles
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.logger
import io.github.rwpp.net.ConnectionFailure
import io.github.rwpp.net.ConnectionFailureReason
import io.github.rwpp.net.ConnectionFailureStage
import io.github.rwpp.net.connectionFailure
import io.github.rwpp.net.sync.direct.DirectModSyncErrorCode
import io.github.rwpp.net.sync.direct.DirectModSyncServerException
import io.github.rwpp.net.sync.direct.DirectJoinEndpoint
import io.github.rwpp.net.sync.direct.DirectModManifest
import io.github.rwpp.net.sync.direct.DirectModDownloadPhase
import io.github.rwpp.net.sync.direct.DirectModDownloadProgress
import io.github.rwpp.net.sync.direct.DirectModSyncClient
import io.github.rwpp.net.sync.direct.DirectModSyncTransport
import io.github.rwpp.net.sync.direct.DirectModSyncUnsupportedException
import io.github.rwpp.net.sync.direct.validateManifest
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DirectJoinSyncResult {
    data class Applied(val reservation: DirectJoinReservation? = null, val expansionToken: Long? = null,
        val pinnedAddress: String? = null, val resume: DirectResumeConnection? = null) : DirectJoinSyncResult
    data object Unsupported : DirectJoinSyncResult
    data class Failed(val failure: ConnectionFailure) : DirectJoinSyncResult
}

/** 可替换的引擎边界，便于在不启动原版连接的情况下验证 Direct 编排。 */
interface DirectModSyncEnvironment {
    val cache: NetworkModCache
    val gameVersion: String get() = io.github.rwpp.gameVersion.toString()
    fun getAllMods(): List<Mod>
    /** Only a successfully loaded content snapshot may bypass engine parsing. */
    suspend fun isLoadedModSetExact(descriptors: List<NetworkModDescriptor>): Boolean = false
    suspend fun applyMods(descriptors: List<NetworkModDescriptor>)
    fun getUnitsChecksum(): Int
    fun refreshHandshakeChecksumCache()
    suspend fun applyExpansion(artifact: io.github.rwpp.net.sync.direct.DirectContentArtifact,
        transport: DirectModSyncTransport, expected: io.github.rwpp.content.ExpansionJoinExpectation?,
        allowDownload: Boolean, progress: suspend (DirectModDownloadProgress) -> Unit): Long =
        throw java.io.IOException("Game expansion loading is unavailable")
    suspend fun cancelExpansion(token: Long) {}
}

private class DirectSyncFailure(val failure: ConnectionFailure) : IllegalStateException(failure.details)

private object DefaultDirectModSyncEnvironment : DirectModSyncEnvironment {
    override val cache: NetworkModCache get() = appKoin.get()
    override fun getAllMods(): List<Mod> = appKoin.get<ModManager>().getAllMods()
    override fun getUnitsChecksum(): Int = appKoin.get<Game>().getUnitsChecksum()
    override fun refreshHandshakeChecksumCache() = appKoin.get<Game>().refreshHandshakeChecksumCache()
    override suspend fun applyExpansion(artifact: io.github.rwpp.net.sync.direct.DirectContentArtifact,
        transport: DirectModSyncTransport, expected: io.github.rwpp.content.ExpansionJoinExpectation?, allowDownload: Boolean,
        progress: suspend (DirectModDownloadProgress) -> Unit): Long =
        io.github.rwpp.content.ExpansionDirectSync.prepareAndActivate(artifact, transport, expected, allowDownload, progress)
    override suspend fun cancelExpansion(token: Long) = io.github.rwpp.content.ExpansionContentController.cancel(token)

    override suspend fun isLoadedModSetExact(descriptors: List<NetworkModDescriptor>): Boolean {
        GameStartSafety.requireCanReload()
        return appKoin.get<ModManager>().isLoadedModSetExact(descriptors, cache)
    }

    override suspend fun applyMods(descriptors: List<NetworkModDescriptor>) {
        val manager = appKoin.get<ModManager>()
        val allMods = manager.getAllMods()
        val selectedFiles = selectModSyncFiles(allMods, descriptors, cache) { mod, error ->
            logger.warn("[MODSYNC-DIRECT] failed to hash local mod '${mod.name}': ${error.message}")
        }
        // 只提交目标选择。平台重载在实际解析前应用；排队超时或取消时不能提前改坏当前开关。
        val enabledByFileName = allMods.associate {
            val file = File(it.path).canonicalFile
            file.path to (file in selectedFiles)
        }.toMutableMap()
        selectedFiles.forEach { enabledByFileName[it.path] = true }
        // 此时尚无游戏连接：使用普通重载，绝不能改成房内保连接重载。
        manager.modReload(forceImmediate = true, enabledByFileName = enabledByFileName)
        GameStartSafety.requireCanReload()
        if (!isEnabledModSetExact(manager.getAllMods(), descriptors, cache)) {
            throw DirectSyncFailure(ConnectionFailure(
                ConnectionFailureReason.MISSING_MODS,
                ConnectionFailureStage.SYNC_APPLY,
                details = "Local mod set does not match direct manifest after reload",
            ))
        }
    }
}

/**
 * 入房前 Direct 同步。合法 MANIFEST/ERROR 之后的一切失败都终止加入，不再尝试 relay。
 * 本控制器不创建 Presence，也不调用 relay finalize/failInRoom。
 */
class DirectModSyncController(
    private val environment: DirectModSyncEnvironment,
    private val transportFactory: (DirectJoinEndpoint, NetworkModCache) -> DirectModSyncTransport =
        { endpoint, cache -> DirectModSyncClient(endpoint, cache) },
) {
    private val applyingMods = MutableStateFlow(false)
    /** The existing join loader owns progress while Direct applies mods. */
    val isApplyingMods = applyingMods.asStateFlow()

    suspend fun preJoinSync(address: String, loadingContext: LoadingContext,
        expectedExpansion: io.github.rwpp.content.ExpansionJoinExpectation? = null,
        expectedOnlineGameId: String? = expectedExpansion?.gameAppId,
        preloadedExpansion: PortalExpansionHandle? = null,
        expectedServerId: String? = null,
    ): DirectJoinSyncResult {
        val endpoint = DirectJoinEndpoint.parseOrNull(address) ?: return DirectJoinSyncResult.Unsupported
        val transport = transportFactory(endpoint, environment.cache)
        val first = try {
            transport.getManifest()
        } catch (error: DirectModSyncUnsupportedException) {
            if (connectionFailure(error, ConnectionFailureStage.SYNC_MANIFEST) == null) throw error
            return DirectJoinSyncResult.Unsupported
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return failed(error, ConnectionFailureStage.SYNC_MANIFEST)
        }

        var stage = ConnectionFailureStage.SYNC_MANIFEST
        var reservation: DirectJoinReservation? = null
        var expansionToken: Long? = null
        var resumed: DirectResumeConnection? = null
        var handedOff = false
        return try {
            validateManifest(first)
            if (first.capabilities and DIRECT_SYNC_ONLY_CAPABILITY != 0) {
                throw DirectModSyncServerException(DirectModSyncErrorCode.NOT_LOBBY)
            }
            if (first.gameVersion != environment.gameVersion) throw DirectSyncFailure(ConnectionFailure(
                ConnectionFailureReason.DATA_MISMATCH, ConnectionFailureStage.SYNC_MANIFEST,
                details = "Direct game protocol version mismatch (client ${environment.gameVersion} / server ${first.gameVersion})"))
            if (expectedExpansion != null && first.artifact == null) throw java.io.IOException("Server no longer provides the required expansion")
            if (expectedOnlineGameId != null && expectedExpansion == null && first.artifact != null) throw DirectSyncFailure(
                ConnectionFailure(ConnectionFailureReason.MANIFEST_CHANGED, details = "The game now requires a new expansion; confirm its updated details before joining"))
            val peer = transport.peerEndpoint?.let(DirectJoinEndpoint::parseOrNull) ?: endpoint
            if (!first.lobby && first.phase == "match") {
                resumed = DirectResumeSessions.open(peer, first, transport)
                    ?: throw DirectModSyncServerException(DirectModSyncErrorCode.RESUME_INVALID)
                val artifact = first.artifact
                if (artifact != null) {
                    expansionToken = preloadedExpansion?.validateRoom(first, transport, expectedExpansion, expectedServerId)
                        ?: environment.applyExpansion(artifact, transport, expectedExpansion, false) { progress ->
                            loadingContext.message(directDownloadMessage(1, 1, artifact.name, progress))
                        }
                } else if (!environment.isLoadedModSetExact(first.mods)) throw java.io.IOException("Recovery content is not loaded")
                if (first.hostUnitsChecksum != environment.getUnitsChecksum().toLong()) throw java.io.IOException("Recovery content checksum mismatch")
                environment.refreshHandshakeChecksumCache()
                val checked = transport.getManifest()
                requireSameContent(first, checked)
                handedOff = true
                return DirectJoinSyncResult.Applied(expansionToken = expansionToken, pinnedAddress = transport.peerEndpoint, resume = resumed)
            }
            prepare(first)
            if (first.protocolVersion >= 2) {
                loadingContext.message(readI18n("modSync.directReserving", I18nType.RWPP))
                reservation = DirectJoinReservation.acquire(peer, transport, first.scope, manifest = first)
            }
            suspend fun synchronize(): DirectJoinSyncResult {
                currentCoroutineContext().ensureActive()
                loadingContext.message(readI18n("modSync.directChecking", I18nType.RWPP))
                val artifact = first.artifact
                if (artifact != null) {
                    expansionToken = if (preloadedExpansion != null) {
                        preloadedExpansion.validateRoom(first, transport, expectedExpansion, expectedServerId)
                    } else {
                        stage = ConnectionFailureStage.SYNC_APPLY
                        applyingMods.value = true
                        try {
                            environment.applyExpansion(artifact, transport, expectedExpansion, true) { progress ->
                                loadingContext.message(directDownloadMessage(1, 1, artifact.name, progress))
                            }
                        } finally { applyingMods.value = false }
                    }
                    stage = ConnectionFailureStage.SYNC_MANIFEST
                    val after = transport.getManifest()
                    prepare(after); requireSameContent(first, after)
                    if (after.hostUnitsChecksum == -1L || after.hostUnitsChecksum != environment.getUnitsChecksum().toLong()) {
                        throw DirectSyncFailure(ConnectionFailure(ConnectionFailureReason.CHECKSUM_MISMATCH, stage, details = "Expansion unit checksum mismatch"))
                    }
                    environment.refreshHandshakeChecksumCache()
                    reservation?.markContentReady()
                    val last = transport.getManifest()
                    prepare(last); requireSameContent(after, last)
                    reservation?.renewNow()
                    return DirectJoinSyncResult.Applied(reservation, expansionToken, transport.peerEndpoint)
                }
                val missing = first.mods.map { it.cacheKey() }.toSet() - findMatchingModSyncKeys(
                    environment.getAllMods(), first.mods, environment.cache,
                )
                stage = ConnectionFailureStage.SYNC_DOWNLOAD
                val downloads = first.mods.filter { it.cacheKey() in missing }
                downloads.forEachIndexed { index, descriptor ->
                    currentCoroutineContext().ensureActive()
                    loadingContext.message(readI18n("modSync.directDownloading", I18nType.RWPP, (index + 1).toString(), downloads.size.toString(), descriptor.name))
                    transport.downloadWithProgress(descriptor) { progress ->
                        loadingContext.message(directDownloadMessage(index + 1, downloads.size, descriptor.name, progress))
                    }
                }
                currentCoroutineContext().ensureActive()
                stage = ConnectionFailureStage.SYNC_MANIFEST
                loadingContext.message(readI18n("modSync.directRechecking", I18nType.RWPP))
                val second = transport.getManifest()
                prepare(second)
                requireSameContent(first, second)

                stage = ConnectionFailureStage.SYNC_APPLY
                loadingContext.message(readI18n("modSync.directApplying", I18nType.RWPP))
                applyingMods.value = true
                try {
                    if (environment.isLoadedModSetExact(second.mods)) {
                        org.slf4j.LoggerFactory.getLogger(DirectModSyncController::class.java)
                            .info("[MODSYNC-DIRECT] manifest matches successfully loaded content; skipping engine reload")
                    } else {
                        environment.applyMods(second.mods)
                    }
                } finally {
                    applyingMods.value = false
                }
                currentCoroutineContext().ensureActive()
                val localChecksum = environment.getUnitsChecksum().toLong()
                if (second.hostUnitsChecksum != -1L && localChecksum != second.hostUnitsChecksum) {
                    return DirectJoinSyncResult.Failed(ConnectionFailure(
                        ConnectionFailureReason.CHECKSUM_MISMATCH,
                        stage,
                        details = "Direct unit checksum mismatch (local $localChecksum / host ${second.hostUnitsChecksum})",
                    ))
                }
                environment.refreshHandshakeChecksumCache()
                currentCoroutineContext().ensureActive()
                // 重载可能耗时数十秒；下载后检查不能代表此时仍是同一大厅。
                // v2 保留预约直到原版握手真正注册；密码、版本等仍由原版验证。
                stage = ConnectionFailureStage.SYNC_MANIFEST
                loadingContext.message(readI18n("modSync.directRechecking", I18nType.RWPP))
                val finalManifest = transport.getManifest()
                prepare(finalManifest)
                requireSameContent(second, finalManifest)
                currentCoroutineContext().ensureActive()
                reservation?.renewNow()
                return DirectJoinSyncResult.Applied(reservation, pinnedAddress = transport.peerEndpoint)
            }
            val result = reservation?.protect { synchronize() } ?: synchronize()
            handedOff = result is DirectJoinSyncResult.Applied
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: OutOfMemoryError) {
            io.github.rwpp.game.mod.ModLoadSafety.recordMemoryFailure("direct-pre-join-transfer")
            DirectJoinSyncResult.Failed(ConnectionFailure(
                if (stage == ConnectionFailureStage.SYNC_APPLY) ConnectionFailureReason.RELOAD_FAILED else ConnectionFailureReason.SYNC_FAILED,
                stage,
                details = error.message.orEmpty(),
            ))
        } catch (error: Exception) {
            failed(error, stage)
        } finally {
            if (!handedOff) {
                reservation?.close()
                resumed?.close()
                if (preloadedExpansion == null) {
                    expansionToken?.let { token -> kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { environment.cancelExpansion(token) } }
                }
            }
        }
    }

    private fun prepare(manifest: DirectModManifest) {
        validateManifest(manifest)
        requireReady(manifest)
        if (manifest.gameVersion != environment.gameVersion) {
            throw DirectSyncFailure(ConnectionFailure(
                ConnectionFailureReason.DATA_MISMATCH,
                ConnectionFailureStage.SYNC_MANIFEST,
                details = "Direct game protocol version mismatch (client ${environment.gameVersion} / server ${manifest.gameVersion})",
            ))
        }
    }

    private fun requireSameContent(expected: DirectModManifest, actual: DirectModManifest) {
        if (!expected.sameRoomContent(actual)) {
            throw DirectSyncFailure(ConnectionFailure(
                ConnectionFailureReason.MANIFEST_CHANGED,
                ConnectionFailureStage.SYNC_MANIFEST,
                details = "Direct room manifest changed during synchronization",
            ))
        }
    }

    private fun requireReady(manifest: DirectModManifest) {
        if (!manifest.lobby) throw DirectModSyncServerException(DirectModSyncErrorCode.NOT_LOBBY)
        if (manifest.status != "ready") throw DirectModSyncServerException(DirectModSyncErrorCode.PREPARING)
    }

    private fun failed(error: Exception, stage: ConnectionFailureStage): DirectJoinSyncResult.Failed {
        // cause 链中的取消也必须传播，不能变成一次「未知失败」。
        val failure = (error as? DirectSyncFailure)?.failure ?: connectionFailure(error, stage) ?: throw error
        org.slf4j.LoggerFactory.getLogger(DirectModSyncController::class.java)
            .error("[MODSYNC-DIRECT] direct join sync failed: ${error.stackTraceToString()}")
        return DirectJoinSyncResult.Failed(failure)
    }

    companion object {
        val shared by lazy { DirectModSyncController(DefaultDirectModSyncEnvironment) }
    }
}

internal fun directDownloadMessage(index: Int, count: Int, name: String, progress: DirectModDownloadProgress): String {
    val key = when (progress.phase) {
        DirectModDownloadPhase.DOWNLOADING -> "modSync.directDownloading"
        DirectModDownloadPhase.VERIFYING -> "modSync.directVerifying"
        DirectModDownloadPhase.CACHING -> "modSync.directCaching"
    }
    val title = readI18n(key, I18nType.RWPP, index.toString(), count.toString(), name)
    val percent = if (progress.totalBytes > 0) {
        (progress.downloadedBytes.coerceIn(0, progress.totalBytes) * 100 / progress.totalBytes).toString()
    } else "100"
    fun mebibytes(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / 1048576.0)
    return readI18n("modSync.directDownloadProgress", I18nType.RWPP, title, percent,
        mebibytes(progress.downloadedBytes), mebibytes(progress.totalBytes))
}
