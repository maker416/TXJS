/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.MultiplayerPreferences
import io.github.rwpp.event.EventPriority
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.DisconnectEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.gameVersion
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.io.SizeUtils
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import io.github.rwpp.net.RoomDescription
import io.github.rwpp.net.sync.CODE_PREFIX
import io.github.rwpp.net.sync.ModSyncClient
import io.github.rwpp.net.sync.RoomRegisterRequest
import io.github.rwpp.net.sync.SID_PREFIX
import io.github.rwpp.net.sync.SyncPeerPhase
import io.github.rwpp.net.sync.SyncPeerSnapshot
import io.github.rwpp.net.sync.SyncPeerUpsertRequest
import io.github.rwpp.net.sync.forAddress
import io.github.rwpp.net.sync.forRoomDescription
import io.github.rwpp.net.sync.toNetwork
import io.github.rwpp.net.sync.toSnapshot
import io.github.rwpp.net.sync.toSync
import io.github.rwpp.ui.UI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 模组同步控制器（带外同步方案）。
 *
 * 游戏联机通讯保持原版；模组同步完全由启动器在公网同步服务器（relaymod）上带外完成：
 * - 房主侧：进房拿到短码后注册同步房间、上传缺失的 mod blob、心跳续期，离房时注销；
 *   并轮询加入者 Presence 进度供房间页展示。
 * - 加入者侧：在 [io.github.rwpp.game.Game.directJoinServer] 之前执行 [preJoinSync]，
 *   从同步服务器拉取清单并下载缺失 mod，本地启用并重载后再建立游戏连接；
 *   同步期间向同步服上报进度，供房主可见。
 */
object ModSyncController {
    private val scope = CoroutineScope(SupervisorJob())

    /** 开房对话框「传输模组」开关的选择，由 HostGameDialog 在点击开房时写入。 */
    var hostSyncRequested by mutableStateOf(false)

    /** 房主侧同步会话状态，供房间页展示一行状态。 */
    var hostSyncState by mutableStateOf<HostSyncState>(HostSyncState.Off)
        private set

    /** 房主侧：正在带外同步、尚未进房的加入者进度快照。 */
    var hostPeerSnapshots by mutableStateOf<List<SyncPeerSnapshot>>(emptyList())
        private set

    /** 是否有活跃的待进房同步者（开局门控用）。 */
    val hasActiveSyncPeers: Boolean get() = hostPeerSnapshots.isNotEmpty()

    /** 等待房主 preparing 的轮询次数上限（每次 2s，共约 60s）。 */
    private const val PREPARING_POLL_MAX = 30
    private const val PREPARING_POLL_MS = 2_000L
    private const val HEARTBEAT_MS = 60_000L
    private const val PEER_POLL_MS = 1_000L
    private const val PEER_REPORT_MIN_INTERVAL_MS = 500L
    /** applying / joining 阶段 Presence 心跳间隔（须明显短于 relaymod PeerTTL=45s）。 */
    private const val PEER_PHASE_HEARTBEAT_MS = 5_000L

    private var hostJob: Job? = null
    private var heartbeatJob: Job? = null
    private var peerPollJob: Job? = null
    private var hostKey: String? = null
    private var hostSecret: String? = null

    private var preJoinJob: Job? = null
    /** 同步成功后、进房完成前保留的 Presence reporter；由 [finishJoinerPresence] 清理。 */
    private var pendingJoinReporter: PeerProgressReporter? = null
    private val pendingJoinMutex = Mutex()
    private var eventsBound = false

    sealed interface HostSyncState {
        data object Off : HostSyncState
        data class Preparing(val doneCount: Int, val totalCount: Int, val uploadedBytes: Long, val totalBytes: Long) : HostSyncState
        data object Ready : HostSyncState
        data class Error(val message: String) : HostSyncState
    }

    fun init() {
        if (eventsBound) return
        eventsBound = true
        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways(priority = EventPriority.MONITOR) {
            stopHostSession()
        }
    }

    private fun newClient(): ModSyncClient {
        val prefs = appKoin.get<MultiplayerPreferences>()
        val baseUrls = prefs.modSyncApiUrls
            .split(';')
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
        return ModSyncClient(baseUrls, appKoin.get<Net>().client)
    }

    // ---------------- 房主侧 ----------------

    /**
     * 进房提取到房间短码后调用（可能随 UI 刷新反复触发，必须幂等）。
     */
    fun startHostSession(roomCode: String) {
        if (!hostSyncRequested) return
        val key = CODE_PREFIX + roomCode.uppercase()
        if (hostKey == key && (hostJob?.isActive == true || hostSyncState is HostSyncState.Ready)) return
        stopHostSession(clearRequest = false)
        hostKey = key
        val secret = UUID.randomUUID().toString().replace("-", "")
        hostSecret = secret
        hostJob = scope.launch(Dispatchers.IO) { runHostSession(key, secret) }
    }

    private suspend fun runHostSession(key: String, secret: String) {
        try {
            val manager = appKoin.get<ModManager>()
            val enabled = manager.getAllMods().filter { it.isEnabled }
            val networkMods = enabled.filter { it.isNetworkMod }
            if (networkMods.isNotEmpty()) {
                // 安全兜底：LoadingView 开房前已禁用网络缓存模组，正常不会走到这里
                logger.warn("[MODSYNC-HOST] refused to sync network cache mods: ${networkMods.map { it.name }}")
                val message = readI18n("multiplayer.networkModHostBlocked")
                withContext(Dispatchers.Main.immediate) { UI.showWarning(message, true) }
                appKoin.get<Game>().gameRoom.disconnect(message)
                return
            }
            if (enabled.isEmpty()) {
                logger.info("[MODSYNC-HOST] no enabled mods, skip sync registration")
                setHostState(HostSyncState.Off)
                return
            }

            logger.info("[MODSYNC-HOST] preparing manifest for ${enabled.size} mod(s), key=$key")
            setHostState(HostSyncState.Preparing(0, enabled.size, 0L, 0L))
            // 读取/压缩 + SHA-256；与旧协议方案一致，全部字节驻留内存直至上传完成
            val prepared = enabled.map { mod ->
                val bytes = mod.getBytes()
                NetworkModDescriptor.fromBytes(mod.name, bytes) to bytes
            }
            val descriptors = prepared.map { it.first }
            val totalBytes = descriptors.sumOf { it.payloadSize }

            val client = newClient()
            client.register(
                RoomRegisterRequest(
                    key = key,
                    secret = secret,
                    gameVersion = gameVersion.toString(),
                    mods = descriptors.map { it.toSync() },
                )
            )
            // 注册成功后即轮询加入者进度（preparing 阶段也会有 waiting_host 上报）
            startPeerPolling(key)

            val missing = client.checkFiles(key, secret, descriptors.map { it.normalizedSha256 }).toSet()
            logger.info("[MODSYNC-HOST] registered; server missing ${missing.size}/${descriptors.size} blob(s)")

            var uploadedBytes = 0L
            var doneCount = 0
            prepared.forEach { (descriptor, bytes) ->
                if (descriptor.normalizedSha256 in missing) {
                    client.uploadFile(descriptor.normalizedSha256, secret, bytes) { progress ->
                        setHostState(
                            HostSyncState.Preparing(
                                doneCount, prepared.size,
                                uploadedBytes + (descriptor.payloadSize * progress).toLong(), totalBytes,
                            )
                        )
                    }
                }
                uploadedBytes += descriptor.payloadSize
                doneCount++
                setHostState(HostSyncState.Preparing(doneCount, prepared.size, uploadedBytes, totalBytes))
            }
            client.markReady(key, secret)
            logger.info("[MODSYNC-HOST] sync room ready: key=$key, total=${totalBytes}B")
            setHostState(HostSyncState.Ready)
            startHeartbeat(key, secret)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("[MODSYNC-HOST] host session failed: ${e.stackTraceToString()}")
            setHostState(HostSyncState.Error(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun startHeartbeat(key: String, secret: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(HEARTBEAT_MS)
                runCatching { newClient().heartbeat(key, secret) }
                    .onFailure { logger.warn("[MODSYNC-HOST] heartbeat failed: ${it.message}") }
            }
        }
    }

    private fun startPeerPolling(key: String) {
        peerPollJob?.cancel()
        peerPollJob = scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching {
                    val peers = newClient().listPeers(key).map { it.toSnapshot() }
                    withContext(Dispatchers.Main.immediate) { hostPeerSnapshots = peers }
                }.onFailure {
                    logger.warn("[MODSYNC-HOST] listPeers failed: ${it.message}")
                }
                delay(PEER_POLL_MS)
            }
        }
    }

    /** 发布到房间列表成功后绑定 server_id 别名，让加入者可用 sid 查到同一记录。 */
    fun bindPublishedServerId(serverId: String) {
        val key = hostKey ?: return
        val secret = hostSecret ?: return
        if (serverId.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching { newClient().addAlias(key, secret, SID_PREFIX + serverId) }
                .onFailure { logger.warn("[MODSYNC-HOST] bind server_id alias failed: ${it.message}") }
        }
    }

    fun stopHostSession(clearRequest: Boolean = true) {
        val key = hostKey
        val secret = hostSecret
        hostJob?.cancel()
        heartbeatJob?.cancel()
        peerPollJob?.cancel()
        hostJob = null
        heartbeatJob = null
        peerPollJob = null
        hostKey = null
        hostSecret = null
        if (clearRequest) hostSyncRequested = false
        setHostState(HostSyncState.Off)
        scope.launch(Dispatchers.Main.immediate) { hostPeerSnapshots = emptyList() }
        if (key != null && secret != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { newClient().unregister(key, secret) }
                    .onFailure { logger.warn("[MODSYNC-HOST] unregister failed: ${it.message}") }
            }
        }
    }

    // ---------------- 加入者侧 ----------------

    /**
     * 在建立游戏连接前执行带外模组同步。
     *
     * @return true 表示可以继续加入（无需同步或同步成功）；false 表示中止加入。
     */
    suspend fun preJoinSync(desc: RoomDescription?, address: String, loadingContext: LoadingContext): Boolean {
        val keys = if (desc != null) forRoomDescription(desc) else forAddress(address)
        if (keys.isEmpty()) return true
        preJoinJob = currentCoroutineContext().job
        var reporter: PeerProgressReporter? = null
        var retainPresence = false
        try {
            val client = newClient()
            val found = fetchFirstAvailable(client, keys) ?: return true
            val usedKey = found.second
            var response = found.first
            reporter = PeerProgressReporter(client, usedKey, resolveDisplayName())

            // 房主仍在准备（注册后尚未 ready）：轮询等待，并上报 waiting_host
            var polls = 0
            while (response.isPreparing) {
                reporter.report(
                    SyncPeerUpsertRequest(
                        displayName = reporter.displayName,
                        phase = SyncPeerPhase.WAITING_HOST,
                    ),
                    force = true,
                )
                if (polls >= PREPARING_POLL_MAX) {
                    fail(loadingContext, readI18n("modSync.hostPreparingTimeout"), null)
                    return false
                }
                polls++
                loadingContext.message(readI18n("modSync.hostPreparing", I18nType.RWPP, (polls * PREPARING_POLL_MS / 1000).toString()))
                delay(PREPARING_POLL_MS)
                response = client.fetchManifest(usedKey) ?: return true
            }
            if (!response.isReady) return true

            val descriptors = response.mods.map { it.toNetwork() }
            if (descriptors.isEmpty()) return true
            val cache = appKoin.get<NetworkModCache>()
            val manager = appKoin.get<ModManager>()
            val allMods = manager.getAllMods()
            val localMatches = findLocalMatchKeys(allMods, descriptors, cache)
            val missing = descriptors.filter { it.cacheKey() !in localMatches }
            logger.info("[MODSYNC] manifest ready: required=${descriptors.size}, missing=${missing.size}")

            // 本地已齐且引擎当前启用集合与清单完全一致：跳过下载与 modReload，直接进房
            if (missing.isEmpty() && isEngineAlreadyExact(allMods, descriptors, cache)) {
                logger.info("[MODSYNC] local mods already exact match, skip download/reload")
                retainJoiningPresence(reporter, modIndex = descriptors.size, modCount = descriptors.size)
                retainPresence = true
                return true
            }

            if (missing.isNotEmpty()) {
                setDownloadingTitle(missing)
                missing.forEachIndexed { index, descriptor ->
                    reporter.report(
                        SyncPeerUpsertRequest(
                            displayName = reporter.displayName,
                            phase = SyncPeerPhase.DOWNLOADING,
                            currentModName = descriptor.name,
                            currentBytes = 0L,
                            currentTotal = descriptor.payloadSize,
                            modIndex = index,
                            modCount = missing.size,
                        ),
                        force = true,
                    )
                    val bytes = client.downloadFile(descriptor.normalizedSha256, descriptor.payloadSize) { progress ->
                        updateDownloadingProgress(descriptor, progress, missing.size, index + 1)
                        val received = (descriptor.payloadSize * progress).toLong()
                        reporter.report(
                            SyncPeerUpsertRequest(
                                displayName = reporter.displayName,
                                phase = SyncPeerPhase.DOWNLOADING,
                                currentModName = descriptor.name,
                                currentBytes = received,
                                currentTotal = descriptor.payloadSize,
                                modIndex = index,
                                modCount = missing.size,
                            ),
                        )
                    }
                    if (!descriptor.matches(bytes)) {
                        fail(
                            loadingContext,
                            readI18n("mod.integrityFailed"),
                            readI18n("mod.integrityFailedDetail", I18nType.RWPP, descriptor.name),
                        )
                        return false
                    }
                    cache.storeVerified(descriptor, bytes)
                    cache.activate(descriptor)
                    logger.info("[MODSYNC] mod cached and activated OK: ${descriptor.name}")
                }
            }

            val applyModIndex = missing.size.coerceAtLeast(0)
            val applyModCount = missing.size.coerceAtLeast(descriptors.size)
            reporter.report(
                SyncPeerUpsertRequest(
                    displayName = reporter.displayName,
                    phase = SyncPeerPhase.APPLYING,
                    currentModName = "",
                    modIndex = applyModIndex,
                    modCount = applyModCount,
                ),
                force = true,
            )
            reporter.startPhaseHeartbeat(
                SyncPeerUpsertRequest(
                    displayName = reporter.displayName,
                    phase = SyncPeerPhase.APPLYING,
                    modIndex = applyModIndex,
                    modCount = applyModCount,
                ),
            )
            if (!finalizeModSync(descriptors, cache, manager, loadingContext)) {
                return false
            }

            // 应用成功：切到 joining 并保留 Presence，直到进房流程结束再清理
            retainJoiningPresence(reporter, modIndex = applyModIndex, modCount = applyModCount)
            retainPresence = true
            return true
        } catch (e: CancellationException) {
            logger.info("[MODSYNC] pre-join sync cancelled by user")
            return false
        } catch (e: Exception) {
            logger.error("[MODSYNC] pre-join sync failed: ${e.stackTraceToString()}")
            fail(loadingContext, readI18n("modSync.syncFailed"), readI18n("modSync.syncFailedDetail", I18nType.RWPP, e.message.orEmpty()))
            return false
        } finally {
            if (!retainPresence) {
                reporter?.clear()
            }
            preJoinJob = null
            resetReceivingState()
        }
    }

    /**
     * 加入者进房流程结束（成功或失败/取消）后清理 Presence。
     * 幂等；可在 LoadingView onLoaded 与 loadContent finally 中重复调用。
     */
    fun finishJoinerPresence() {
        scope.launch(Dispatchers.IO) {
            val reporter = pendingJoinMutex.withLock {
                val current = pendingJoinReporter
                pendingJoinReporter = null
                current
            } ?: return@launch
            reporter.clear()
        }
    }

    /** 上报 joining 并挂到 [pendingJoinReporter]，供进房结束后清理。 */
    private suspend fun retainJoiningPresence(
        reporter: PeerProgressReporter,
        modIndex: Int,
        modCount: Int,
    ) {
        val request = SyncPeerUpsertRequest(
            displayName = reporter.displayName,
            phase = SyncPeerPhase.JOINING,
            modIndex = modIndex,
            modCount = modCount,
        )
        reporter.report(request, force = true)
        reporter.startPhaseHeartbeat(request)
        val previous = pendingJoinMutex.withLock {
            val old = pendingJoinReporter
            pendingJoinReporter = reporter
            old
        }
        previous?.clear()
    }

    /** 依次尝试候选 key；全部 404 返回 null；服务器不可达抛异常。 */
    private suspend fun fetchFirstAvailable(
        client: ModSyncClient,
        keys: List<String>,
    ): Pair<io.github.rwpp.net.sync.RoomManifestResponse, String>? {
        var lastError: Exception? = null
        for (key in keys) {
            try {
                val manifest = client.fetchManifest(key)
                if (manifest != null) return manifest to key
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        lastError?.let { throw it }
        return null
    }

    /** 取消正在进行的加入前同步（下载卡片取消按钮调用）。 */
    fun cancelPreJoin() {
        preJoinJob?.cancel()
    }

    private suspend fun finalizeModSync(
        descriptors: List<NetworkModDescriptor>,
        cache: NetworkModCache,
        manager: ModManager,
        loadingContext: LoadingContext,
    ): Boolean {
        withContext(Dispatchers.Main.immediate) { UI.showNetworkDialog = false }
        descriptors.forEach { descriptor -> cache.find(descriptor)?.let { cache.activate(descriptor) } }
        val exactNames = descriptors.map { it.name }.toSet()
        manager.getAllMods().forEach { mod -> mod.isEnabled = mod.name in exactNames }
        logger.info("[MODSYNC] calling modReload(forceImmediate=true) ...")
        manager.modReload(forceImmediate = true)
        val matched = findLocalMatchKeys(manager.getAllMods(), descriptors, cache)
        if (!descriptors.all { it.cacheKey() in matched }) {
            fail(loadingContext, readI18n("mod.downloadFailed"), readI18n("mod.downloadFailedMissing"))
            return false
        }
        logger.info("[MODSYNC] SUCCESS: mods synced, proceeding to join")
        return true
    }

    /**
     * 引擎当前已启用的模组集合是否与房主清单完全一致（名称集合相同，且每个启用模组内容哈希匹配）。
     * 仅用于跳过多余的 [finalizeModSync]/modReload；有多余启用、缺启用或内容不一致时返回 false。
     */
    private fun isEngineAlreadyExact(
        mods: List<Mod>,
        descriptors: List<NetworkModDescriptor>,
        cache: NetworkModCache,
    ): Boolean {
        if (descriptors.isEmpty()) return true
        val requiredByName = descriptors.associateBy { it.name }
        val enabled = mods.filter { it.isEnabled }
        if (enabled.size != descriptors.size) return false
        if (enabled.map { it.name }.toSet() != requiredByName.keys) return false
        return enabled.all { mod ->
            val expected = requiredByName[mod.name] ?: return@all false
            if (mod.isNetworkMod) {
                cache.descriptorForManagedPath(mod.path)?.cacheKey() == expected.cacheKey()
            } else {
                runCatching {
                    NetworkModDescriptor.fromBytes(mod.name, mod.getBytes()) == expected
                }.getOrElse {
                    logger.warn("[MODSYNC] failed to hash enabled mod '${mod.name}': ${it.message}")
                    false
                }
            }
        }
    }

    /**
     * 返回本地已满足的 descriptor cacheKey 集合：验证过的网络缓存、哈希一致的本地网络模组、
     * 或哈希一致的本地模组。身份永远是 name + payloadSize + sha256；同名不同内容不算命中。
     */
    private fun findLocalMatchKeys(
        mods: List<Mod>,
        descriptors: List<NetworkModDescriptor>,
        cache: NetworkModCache,
    ): Set<String> {
        val targetKeys = descriptors.map { it.cacheKey() }.toSet()
        val matches = mutableSetOf<String>()
        descriptors.forEach { descriptor ->
            if (cache.find(descriptor) != null) matches.add(descriptor.cacheKey())
        }
        val namesToHashes = descriptors.associateBy { it.name }
        mods.forEach { mod ->
            if (mod.isNetworkMod) {
                val descriptor = cache.descriptorForManagedPath(mod.path)
                if (descriptor != null && descriptor.cacheKey() in targetKeys) matches.add(descriptor.cacheKey())
            } else {
                val expected = namesToHashes[mod.name] ?: return@forEach
                runCatching {
                    val descriptor = NetworkModDescriptor.fromBytes(mod.name, mod.getBytes())
                    if (descriptor.cacheKey() in targetKeys && descriptor == expected) matches.add(descriptor.cacheKey())
                }.onFailure { logger.warn("[MODSYNC] failed to hash local mod '${mod.name}': ${it.message}") }
            }
        }
        return matches
    }

    private fun resolveDisplayName(): String {
        val raw = runCatching {
            appKoin.get<ConfigIO>().getGameConfig<String?>("lastNetworkPlayerName")
        }.getOrNull().orEmpty().trim()
        val name = raw.ifBlank { "Player" }
        return if (name.length <= 64) name else name.take(64)
    }

    // ---------------- UI 状态 ----------------

    private fun setHostState(state: HostSyncState) {
        scope.launch(Dispatchers.Main.immediate) { hostSyncState = state }
    }

    private suspend fun fail(loadingContext: LoadingContext, message: String, detail: String?) {
        loadingContext.message(message)
        if (detail != null) {
            withContext(Dispatchers.Main.immediate) { UI.showWarning(detail, true) }
        }
    }

    private suspend fun setDownloadingTitle(missing: List<NetworkModDescriptor>) {
        val totalSize = missing.sumOf { it.payloadSize }
        withContext(Dispatchers.Main.immediate) {
            UI.receivingNetworkDialogTitle = readI18n(
                "mod.downloadingTitleStart",
                I18nType.RWPP,
                missing.first().name,
                SizeUtils.byteToMB(totalSize).toString(),
                missing.size.toString(),
            )
            UI.receivingModName = missing.first().name
            UI.receivingModProgress = 0f
            UI.receivingModReceivedBytes = 0L
            UI.receivingModTotalBytes = 0L
            UI.receivingModTotalCount = missing.size
            UI.receivingModDoneCount = 0
            UI.showNetworkDialog = true
        }
    }

    private fun updateDownloadingProgress(
        descriptor: NetworkModDescriptor,
        progress: Float,
        totalCount: Int,
        doneCount: Int,
    ) {
        val received = (descriptor.payloadSize * progress).toLong()
        scope.launch(Dispatchers.Main.immediate) {
            UI.receivingNetworkDialogTitle = readI18n(
                "mod.downloadingTitleProgress",
                I18nType.RWPP,
                descriptor.name,
                SizeUtils.byteToMB(received).toString(),
                SizeUtils.byteToMB(descriptor.payloadSize).toString(),
                doneCount.toString(),
                totalCount.toString(),
            )
            UI.receivingModName = descriptor.name
            UI.receivingModProgress = progress.coerceIn(0f, 1f)
            UI.receivingModReceivedBytes = received
            UI.receivingModTotalBytes = descriptor.payloadSize
            UI.receivingModTotalCount = totalCount
            UI.receivingModDoneCount = doneCount
            UI.showNetworkDialog = true
        }
    }

    private suspend fun resetReceivingState() {
        withContext(Dispatchers.Main.immediate) {
            UI.showNetworkDialog = false
            UI.receivingNetworkDialogTitle = ""
            UI.receivingModName = ""
            UI.receivingModProgress = 0f
            UI.receivingModReceivedBytes = 0L
            UI.receivingModTotalBytes = 0L
            UI.receivingModTotalCount = 0
            UI.receivingModDoneCount = 0
        }
    }

    /**
     * 加入者向同步服上报 Presence；[force]=false 时按 [PEER_REPORT_MIN_INTERVAL_MS] 节流。
     * [report] 可在任意线程调用（下载进度回调非 suspend）；失败仅记日志。
     * applying / joining 阶段通过 [startPhaseHeartbeat] 周期刷新，避免超过 relaymod PeerTTL。
     */
    private class PeerProgressReporter(
        private val client: ModSyncClient,
        private val roomKey: String,
        val displayName: String,
    ) {
        private val peerId: String = "p" + UUID.randomUUID().toString().replace("-", "")
        private val lastReportAt = AtomicLong(0L)
        private val heartbeatRequest = AtomicReference<SyncPeerUpsertRequest?>(null)
        private var heartbeatJob: Job? = null

        fun report(request: SyncPeerUpsertRequest, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastReportAt.get() < PEER_REPORT_MIN_INTERVAL_MS) return
            lastReportAt.set(now)
            heartbeatRequest.set(request)
            scope.launch(Dispatchers.IO) {
                runCatching { client.upsertPeer(roomKey, peerId, request) }
                    .onFailure { logger.warn("[MODSYNC] upsertPeer failed: ${it.message}") }
            }
        }

        /** 启动/切换 phase 心跳：每 [PEER_PHASE_HEARTBEAT_MS] force upsert 一次。 */
        fun startPhaseHeartbeat(request: SyncPeerUpsertRequest) {
            heartbeatRequest.set(request)
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(PEER_PHASE_HEARTBEAT_MS)
                    val current = heartbeatRequest.get() ?: continue
                    report(current, force = true)
                }
            }
        }

        fun stopHeartbeat() {
            heartbeatJob?.cancel()
            heartbeatJob = null
        }

        /** 停止心跳并 DELETE peer；使用 NonCancellable，保证取消路径也能清掉 Presence。 */
        suspend fun clear() {
            stopHeartbeat()
            withContext(NonCancellable) {
                runCatching { client.deletePeer(roomKey, peerId) }
                    .onFailure { logger.warn("[MODSYNC] deletePeer failed: ${it.message}") }
            }
        }
    }
}
