/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

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
import io.github.rwpp.net.sync.direct.DirectJoinEndpoint
import io.github.rwpp.net.sync.direct.DirectModManifest
import io.github.rwpp.net.sync.direct.DirectModSyncClient
import io.github.rwpp.net.sync.direct.DirectModSyncTransport
import io.github.rwpp.net.sync.direct.DirectModSyncUnsupportedException
import io.github.rwpp.net.sync.direct.validateManifest
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface DirectJoinSyncResult {
    data object Applied : DirectJoinSyncResult
    data object Unsupported : DirectJoinSyncResult
    data class Failed(val message: String) : DirectJoinSyncResult
}

/** 可替换的引擎边界，便于在不启动原版连接的情况下验证 Direct 编排。 */
interface DirectModSyncEnvironment {
    val cache: NetworkModCache
    fun getAllMods(): List<Mod>
    suspend fun applyMods(descriptors: List<NetworkModDescriptor>)
    fun getUnitsChecksum(): Int
    fun refreshHandshakeChecksumCache()
}

private object DefaultDirectModSyncEnvironment : DirectModSyncEnvironment {
    override val cache: NetworkModCache get() = appKoin.get()
    override fun getAllMods(): List<Mod> = appKoin.get<ModManager>().getAllMods()
    override fun getUnitsChecksum(): Int = appKoin.get<Game>().getUnitsChecksum()
    override fun refreshHandshakeChecksumCache() = appKoin.get<Game>().refreshHandshakeChecksumCache()

    override suspend fun applyMods(descriptors: List<NetworkModDescriptor>) {
        val manager = appKoin.get<ModManager>()
        val allMods = manager.getAllMods()
        val selectedFiles = selectModSyncFiles(allMods, descriptors, cache) { mod, error ->
            logger.warn("[MODSYNC-DIRECT] failed to hash local mod '${mod.name}': ${error.message}")
        }
        allMods.forEach { mod -> mod.isEnabled = File(mod.path).canonicalFile in selectedFiles }
        val enabledByFileName = allMods.associate { File(it.path).canonicalPath to it.isEnabled }.toMutableMap()
        selectedFiles.forEach { enabledByFileName[it.path] = true }
        // 此时尚无游戏连接：使用普通重载，绝不能改成房内保连接重载。
        manager.modReload(forceImmediate = true, enabledByFileName = enabledByFileName)
        GameStartSafety.requireCanReload()
        if (!isEnabledModSetExact(manager.getAllMods(), descriptors, cache)) {
            throw IllegalStateException("Local mod set does not match direct manifest after reload")
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
    suspend fun preJoinSync(address: String, loadingContext: LoadingContext): DirectJoinSyncResult {
        val endpoint = DirectJoinEndpoint.parseOrNull(address) ?: return DirectJoinSyncResult.Unsupported
        val transport = transportFactory(endpoint, environment.cache)
        val first = try {
            transport.getManifest()
        } catch (_: DirectModSyncUnsupportedException) {
            return DirectJoinSyncResult.Unsupported
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return failed(error)
        }

        return try {
            prepare(first)
            loadingContext.message(readI18n("modSync.directChecking", I18nType.RWPP))
            val missing = first.mods.map { it.cacheKey() }.toSet() - findMatchingModSyncKeys(
                environment.getAllMods(), first.mods, environment.cache,
            )
            first.mods.filter { it.cacheKey() in missing }.forEachIndexed { index, descriptor ->
                currentCoroutineContext().ensureActive()
                loadingContext.message(readI18n("modSync.directDownloading", I18nType.RWPP, (index + 1).toString(), first.mods.size.toString(), descriptor.name))
                transport.download(descriptor)
            }
            currentCoroutineContext().ensureActive()
            loadingContext.message(readI18n("modSync.directRechecking", I18nType.RWPP))
            val second = transport.getManifest()
            validateManifest(second)
            requireReady(second)
            if (!first.sameRoomContent(second)) throw IllegalStateException("Direct room manifest changed during download")

            loadingContext.message(readI18n("modSync.directApplying", I18nType.RWPP))
            environment.applyMods(second.mods)
            currentCoroutineContext().ensureActive()
            val localChecksum = environment.getUnitsChecksum().toLong()
            if (second.hostUnitsChecksum != -1L && localChecksum != second.hostUnitsChecksum) {
                throw IllegalStateException("Direct unit checksum mismatch (local $localChecksum / host ${second.hostUnitsChecksum})")
            }
            environment.refreshHandshakeChecksumCache()
            currentCoroutineContext().ensureActive()
            DirectJoinSyncResult.Applied
        } catch (error: CancellationException) {
            throw error
        } catch (error: OutOfMemoryError) {
            io.github.rwpp.game.mod.ModLoadSafety.recordMemoryFailure("direct-pre-join-transfer")
            DirectJoinSyncResult.Failed(readI18n("modSync.directFailed", I18nType.RWPP, error.message.orEmpty()))
        } catch (error: Exception) {
            failed(error)
        }
    }

    private fun prepare(manifest: DirectModManifest) {
        validateManifest(manifest)
        requireReady(manifest)
    }

    private fun requireReady(manifest: DirectModManifest) {
        if (!manifest.lobby) throw IllegalStateException("Direct room is not in lobby")
        if (manifest.status != "ready") throw IllegalStateException("Direct room is not ready: ${manifest.status}")
    }

    private fun failed(error: Exception): DirectJoinSyncResult.Failed {
        org.slf4j.LoggerFactory.getLogger(DirectModSyncController::class.java)
            .error("[MODSYNC-DIRECT] direct join sync failed: ${error.stackTraceToString()}")
        return DirectJoinSyncResult.Failed(readI18n("modSync.directFailed", I18nType.RWPP, error.message.orEmpty()))
    }

    companion object {
        val shared by lazy { DirectModSyncController(DefaultDirectModSyncEnvironment) }
    }
}
