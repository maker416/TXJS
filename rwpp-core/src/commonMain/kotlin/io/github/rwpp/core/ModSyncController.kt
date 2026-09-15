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
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.DisconnectEvent
import io.github.rwpp.event.events.JoinGameEvent
import io.github.rwpp.event.events.StartGameEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.KeepConnectedReload
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
import io.github.rwpp.net.sync.ModSyncException
import io.github.rwpp.net.sync.RoomManifestResponse
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
import io.github.rwpp.widget.loadingMessage
import io.github.rwpp.widget.parseEngineLoadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 模组同步控制器（带外同步方案，v2：进房后同步）。
 *
 * 游戏联机通讯保持原版；模组同步完全由启动器在公网同步服务器（relaymod）上带外完成：
 * - 房主侧：进房拿到短码后注册同步房间（含单位校验和）、上传缺失的 mod blob、心跳续期，
 *   离房时注销；并轮询加入者 Presence 进度供房间页展示与握手放行判定（[shouldAllowMismatch]）。
 * - 加入者侧：进房前 [preJoinSync] 只做轻量段——查清单、本地已完全一致则按原版加入，
 *   否则上报 joining Presence 供房主握手放行；进房成功后由 [onJoinedRoom] 启动
 *   [runPostJoinSync]，在房间内后台等待/下载/重载/校验，全程可正常聊天，
 *   进度显示在玩家列表该玩家名字旁（[roomPeerBadges]）。
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

    /** 房客视角的行内徽章快照（peer 脱敏轮询结果），按显示名索引。 */
    private var peerViewSnapshots by mutableStateOf<Map<String, SyncPeerSnapshot>>(emptyMap())

    /**
     * 房间页玩家名字旁的同步徽章数据源，按显示名索引。
     * 房主取 peers 轮询快照（含 ip）；同步中的房客取 peer 视角脱敏轮询结果。
     */
    val roomPeerBadges: Map<String, SyncPeerSnapshot>
        get() = if (hostKey != null) {
            hostPeerSnapshots.associateBy { it.displayName }
        } else {
            peerViewSnapshots
        }

    // ---------------- 握手放行（v2 进房后同步） ----------------

    /** 注册握手暂存：包 110（REGISTER_CONNECTION）入口由注入层解析写入，仅房主侧有意义。 */
    class PendingRegistration(
        val name: String,
        val ip: String?,
        val clientChecksum: Int,
    )

    /**
     * 当前正在处理的注册请求。ad.c / ae.a 均为 synchronized，注册包在单网络线程内串行处理，
     * 且单位校验和读取仅在包 110 分支内发生，因此该暂存无需加锁也不会被跨包串扰。
     */
    @Volatile
    var pendingRegistration: PendingRegistration? = null

    /**
     * 握手放行判定（跑在持锁的网络线程上，必须纯内存、不阻塞）：
     * 仅当房主同步会话活跃、peers 中存在同名且尚未 synced 的加入者、且 IP 不冲突时放行。
     * IP 双匹配是防同名撞车的加固；relay 未记录 ip（旧服务端）或连接取不到 ip 时退化为仅名字匹配。
     */
    fun shouldAllowMismatch(name: String, ip: String?): Boolean {
        if (hostKey == null) return false
        val peer = hostPeerSnapshots.firstOrNull {
            it.displayName == name.trim() && it.phase != SyncPeerPhase.SYNCED
        } ?: return false
        return peer.ip == null || ip == null || peer.ip == ip
    }

    /**
     * 进房后同步尚未完成（waiting / downloading / applying / joining）。
     * 真正失败走 [failInRoom]（先清 phase 再 isKicked 警告），此时本方法为 false，允许回列表。
     */
    fun isInRoomSyncInProgress(): Boolean {
        val phase = inRoomSyncPhase
        return phase != null && phase != SyncPeerPhase.SYNCED
    }

    /** 引擎 Loading/`Missing`/`Kicked` 不得当成踢人回列表（含保连接重载尚未结束）。 */
    fun shouldSuppressEngineKickUi(): Boolean =
        KeepConnectedReload.active || isInRoomSyncInProgress() || cancellingReload

    /**
     * 进房后同步期间推迟客户端「缺单位 / 缺模组」自断。
     * 原版在注册后仍会核对主机要求的单位表，缺失即 `Disconnect: Missing unit`（`ae.C=false`），
     * 聊天退化成 `sendChatMessage: not networked` 本地回显。握手放行只绕过包 110 校验和，
     * 这一关必须另外拦住，下载期才能真正出网聊天。
     */
    fun shouldDeferMissingUnitsCheck(): Boolean {
        if (KeepConnectedReload.active) return true
        if (cancellingReload) return true
        if (isInRoomSyncInProgress()) return true
        if (syncEntryActive) return true
        if (pendingPostJoin != null) return true
        return postJoinJob?.isActive == true
    }

    /** 等待房主 preparing 的轮询次数上限（每次 2s，共约 60s）。 */
    private const val PREPARING_POLL_MAX = 30
    private const val PREPARING_POLL_MS = 2_000L
    private const val HEARTBEAT_MS = 60_000L
    private const val PEER_POLL_MS = 1_000L
    private const val PEER_REPORT_MIN_INTERVAL_MS = 500L
    /** applying / joining 阶段 Presence 心跳间隔（须明显短于 relaymod PeerTTL=45s）。 */
    private const val PEER_PHASE_HEARTBEAT_MS = 5_000L
    /** 应用阶段从引擎 loading 文案刷 Presence 单位计数的间隔。 */
    private const val APPLY_UNIT_PROGRESS_MS = 200L
    /** 上报 joining Presence 后等待房主轮询看到自己的时间（房主侧 1s 轮询）。 */
    private const val JOIN_SETTLE_MS = 1_500L
    /** 同步进房被踢的静默重试次数上限。 */
    private const val SYNC_ENTRY_MAX_RETRY = 2
    /** 进房成功后被踢重试窗口的宽限期（覆盖注册后异步到达的 kick 包）。 */
    private const val ENTRY_GRACE_MS = 4_000L
    /** apply 后等待连接稳定：须长于 relay `PACKET_RECONNECT_TO` 换线窗口（约数百毫秒）。 */
    private const val CONNECT_STABLE_MS = 1_000L
    /** apply 后若已在房，短等确认不是换线瞬间的假连接。 */
    private const val APPLY_CONNECT_WAIT_MS = 1_500L
    /** 重连后等待真正进房（过完 relay 换线 + 第二次 REGISTER）的上限。 */
    private const val REJOIN_CONNECT_TIMEOUT_MS = 8_000L

    private var hostJob: Job? = null
    private var heartbeatJob: Job? = null
    private var peerPollJob: Job? = null
    private var hostKey: String? = null
    private var hostSecret: String? = null

    private var preJoinJob: Job? = null
    private var postJoinJob: Job? = null
    /** [preJoinSync] 轻量段暂存、[onJoinedRoom] 启动进房后同步时消费的上下文。 */
    private var pendingPostJoin: PostJoinContext? = null
    /** 同步进房窗口（含被踢静默重试宽限期）是否活跃；平台层被踢告警据此判定是否抑制。 */
    @Volatile
    var syncEntryActive = false
        private set
    private var syncEntryRetriesLeft = 0
    private var syncEntryAddress: String? = null
    private var syncEntryRelayUuid: String? = null
    /** 进房后同步期间保留的加入地址，供 apply 后连接已死时重连（不依赖 4s 后清掉的 syncEntryActive）。 */
    private var inRoomJoinAddress: String? = null
    private var inRoomJoinRelayUuid: String? = null
    /** 被踢静默重试是否已排定尚未执行；排定期间 [finishJoinerPresence] 不得清理 ctx/Presence。 */
    @Volatile
    private var syncEntryRetryScheduled = false
    /** 进房成功后继续保留的 Presence reporter（synced 心跳）；开局或断线时由 [clearRoomPresence] 清理。 */
    private var roomPresenceReporter: PeerProgressReporter? = null
    private val roomPresenceMutex = Mutex()
    private var peerViewPollJob: Job? = null
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
            clearRoomPresence()
        }
        GlobalEventChannel.filter(StartGameEvent::class).subscribeAlways(priority = EventPriority.MONITOR) {
            // 开局后行内徽章失去意义；未广播该事件的端由 DisconnectEvent / relay TTL 兜底
            clearRoomPresence()
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
                    // 房主当前单位校验和：加入者重载后据此自查是否与房主一致（v2 进房后同步）
                    hostUnitsChecksum = appKoin.get<Game>().getUnitsChecksum().toLong(),
                    mods = descriptors.map { it.toSync() },
                )
            )
            // 注册成功后即轮询加入者进度（preparing 阶段也会有 waiting_host 上报）
            startPeerPolling(key, secret)

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

    private fun startPeerPolling(key: String, secret: String) {
        peerPollJob?.cancel()
        peerPollJob = scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching {
                    val peers = newClient().listPeers(key, secret).map { it.toSnapshot() }
                    withContext(Dispatchers.Main.immediate) { hostPeerSnapshots = peers }
                }.onFailure {
                    logger.warn("[MODSYNC-HOST] listPeers failed: ${it.message}")
                }
                delay(PEER_POLL_MS)
            }
        }
    }

    /** 房主清空异常/伪造的 Presence 占槽，并立即刷新本地快照。 */
    fun clearHostPeers() {
        val key = hostKey ?: return
        val secret = hostSecret ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { newClient().clearPeers(key, secret) }
                .onSuccess {
                    withContext(Dispatchers.Main.immediate) { hostPeerSnapshots = emptyList() }
                    logger.info("[MODSYNC-HOST] cleared all peer presence for key=$key")
                }
                .onFailure { logger.warn("[MODSYNC-HOST] clearPeers failed: ${it.message}") }
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
     * 加入者进房前的轻量段：查同步清单；本地模组已与清单完全一致则按原版加入；
     * 否则上报 joining Presence（供房主握手放行识别），等待房主轮询窗口后放行连接。
     * 真正的等待/下载/重载在进房后由 [runPostJoinSync] 完成，期间房间内可正常聊天。
     *
     * @return true 表示可以继续加入（无需同步或已登记放行）；false 表示中止加入。
     */
    suspend fun preJoinSync(desc: RoomDescription?, address: String, loadingContext: LoadingContext): Boolean {
        val keys = if (desc != null) forRoomDescription(desc) else forAddress(address)
        if (keys.isEmpty()) return true
        preJoinJob = currentCoroutineContext().job
        try {
            val client = newClient()
            val found = fetchFirstAvailable(client, keys) ?: return true
            val usedKey = found.second
            val response = found.first
            if (!response.isReady && !response.isPreparing) return true
            val descriptors = response.mods.map { it.toNetwork() }
            if (descriptors.isEmpty()) return true

            // 本地启用集合已与清单完全一致：原版握手校验和本就能通过，无需带外同步与放行
            val cache = appKoin.get<NetworkModCache>()
            val manager = appKoin.get<ModManager>()
            if (isEngineAlreadyExact(manager.getAllMods(), descriptors, cache)) {
                logger.info("[MODSYNC] local mods already exact match, plain join")
                return true
            }

            // 上报 joining Presence 并起心跳保活；房主 1s 轮询看到后才会在握手时放行。
            // 留出轮询窗口再连接；若仍在窗口内被踢，由 onKickedDuringSyncEntry 静默重试
            val reporter = PeerProgressReporter(client, usedKey, resolveDisplayName())
            val joiningRequest = SyncPeerUpsertRequest(
                displayName = reporter.displayName,
                phase = SyncPeerPhase.JOINING,
                modCount = descriptors.size,
            )
            reporter.report(joiningRequest, force = true)
            reporter.startPhaseHeartbeat(joiningRequest)
            pendingPostJoin = PostJoinContext(reporter, usedKey, response)
            syncEntryActive = true
            syncEntryRetriesLeft = SYNC_ENTRY_MAX_RETRY
            syncEntryAddress = address
            syncEntryRelayUuid = desc?.joinRelayUuid()
            inRoomJoinAddress = address
            inRoomJoinRelayUuid = syncEntryRelayUuid
            loadingContext.message(readI18n("modSync.phaseJoining", I18nType.RWPP))
            delay(JOIN_SETTLE_MS)
            return true
        } catch (e: CancellationException) {
            logger.info("[MODSYNC] pre-join sync cancelled by user")
            return false
        } catch (e: Exception) {
            logger.error("[MODSYNC] pre-join sync failed: ${e.stackTraceToString()}")
            fail(loadingContext, readI18n("modSync.syncFailed"), readI18n("modSync.syncFailedDetail", I18nType.RWPP, e.message.orEmpty()))
            return false
        } finally {
            preJoinJob = null
        }
    }

    /** 进房后同步上下文：[preJoinSync] 轻量段暂存，[onJoinedRoom] 启动 [runPostJoinSync] 时消费。 */
    private class PostJoinContext(
        val reporter: PeerProgressReporter,
        val roomKey: String,
        val manifest: RoomManifestResponse,
    )

    /**
     * 加入者进房流程结束（失败/取消）后清理 Presence 与同步进房状态。
     * 幂等；可在 LoadingView onLoaded 与 loadContent finally 中重复调用。
     * 进房成功（ctx 已被 [onJoinedRoom] 消费）或被踢重试已排定时为空操作——
     * 后者必须保留 ctx/Presence/放行窗口，否则重试连接时房主轮询看不到 peer 会再次被踢。
     */
    fun finishJoinerPresence() {
        scope.launch(Dispatchers.IO) {
            val ctx = pendingPostJoin ?: return@launch
            if (syncEntryRetryScheduled) return@launch
            pendingPostJoin = null
            syncEntryActive = false
            ctx.reporter.clear()
        }
    }

    /**
     * 用户主动取消加入（LoadingView 关闭/取消）：取消轻量段、解除被踢重试武装并清理 Presence。
     * 与 [finishJoinerPresence] 的区别：取消语义下绝不保留重试。
     */
    fun cancelJoinEntry() {
        preJoinJob?.cancel()
        syncEntryRetriesLeft = 0
        syncEntryRetryScheduled = false
        syncEntryActive = false
        inRoomJoinAddress = null
        inRoomJoinRelayUuid = null
        scope.launch(Dispatchers.IO) {
            val ctx = pendingPostJoin
            pendingPostJoin = null
            ctx?.reporter?.clear()
        }
    }

    /**
     * 加入者成功进房后调用：启动进房后同步（[runPostJoinSync]），Presence 心跳保留，
     * 供房主/房客在玩家列表行内展示同步徽章；开局或断线时由 [clearRoomPresence] 清理。
     * 无待同步上下文（无需同步的原版加入）时为空操作。
     */
    fun onJoinedRoom() {
        val ctx = pendingPostJoin
        pendingPostJoin = null
        if (ctx == null) return
        scope.launch(Dispatchers.IO) {
            roomPresenceMutex.withLock { roomPresenceReporter = ctx.reporter }
            startPeerViewPolling(ctx.reporter)
        }
        // 被踢静默重试窗口：进房成功后再留一段宽限期（校验和踢人在注册后 ~1s 内异步到达）
        scope.launch { delay(ENTRY_GRACE_MS); syncEntryActive = false }
        postJoinJob = scope.launch(Dispatchers.IO) { runPostJoinSync(ctx) }
    }

    /** 清理进房后保留的 Presence、同步任务与 peer 视角轮询（开局/断线/失败触发；幂等）。 */
    fun clearRoomPresence() {
        peerViewPollJob?.cancel()
        peerViewPollJob = null
        // 应用阶段取消要先退房：DisconnectEvent 会走进这里，但不能掐掉原版回落。
        val keepReloadJob = cancellingReload || KeepConnectedReload.active || KeepConnectedReload.abortToVanilla
        if (!keepReloadJob) {
            postJoinJob?.cancel()
            postJoinJob = null
        }
        inRoomJoinAddress = null
        inRoomJoinRelayUuid = null
        scope.launch(Dispatchers.IO) {
            val reporter = roomPresenceMutex.withLock {
                val current = roomPresenceReporter
                roomPresenceReporter = null
                current
            }
            reporter?.clear()
            receivingEpoch.incrementAndGet()
            withContext(Dispatchers.Main.immediate) {
                peerViewSnapshots = emptyMap()
                inRoomSyncPhase = null
                resetReceivingStateOnMain()
            }
        }
    }

    /** 房客视角：进房后用自己的 peer secret 轮询本房 peers 的脱敏进度，驱动行内徽章。 */
    private fun startPeerViewPolling(reporter: PeerProgressReporter) {
        peerViewPollJob?.cancel()
        peerViewPollJob = scope.launch(Dispatchers.IO) {
            var delayMs = PEER_POLL_MS
            var lastError: String? = null
            var secretMismatchStreak = 0
            var stopPolling = false
            while (isActive && !stopPolling) {
                val secret = reporter.currentPeerSecret()
                if (secret != null) {
                    val result = runCatching {
                        newClient().listPeersAsPeer(reporter.roomKey, secret)
                    }
                    result.onSuccess { peers ->
                        delayMs = PEER_POLL_MS
                        secretMismatchStreak = 0
                        lastError = null
                        withContext(Dispatchers.Main.immediate) {
                            peerViewSnapshots = peers.map { it.toSnapshot() }.associateBy { it.displayName }
                        }
                    }.onFailure {
                        val msg = it.message.orEmpty()
                        if (msg != lastError) {
                            logger.warn("[MODSYNC] listPeersAsPeer failed: $msg")
                            lastError = msg
                        }
                        if (msg.contains("secret does not match")) {
                            secretMismatchStreak++
                            delayMs = (delayMs * 2).coerceAtMost(8_000L)
                            if (secretMismatchStreak >= 3) {
                                logger.warn("[MODSYNC] peer secret expired, stopping peer view polling")
                                stopPolling = true
                            }
                        }
                    }
                }
                if (stopPolling) break
                delay(delayMs)
            }
        }
    }

    /**
     * 进房后同步主流程：等房主 ready → diff → 下载缺失 → 启用 + modReload（连接保持）→
     * 校验（本地集合 + 单位校验和 vs 房主）→ synced 心跳保留。
     * 任何失败都提示并自动退房（模组不一致留在房内没有意义），清理由 DisconnectEvent 完成。
     */
    private suspend fun runPostJoinSync(ctx: PostJoinContext) {
        val reporter = ctx.reporter
        try {
            val client = newClient()
            var response = ctx.manifest

            // 房主仍在准备：房间内等待（可正常聊天），上报 waiting_host；
            // 轮询中 404 说明房主已注销/离房，不得当作「无需同步」继续
            var polls = 0
            while (response.isPreparing) {
                setInRoomPhase(SyncPeerPhase.WAITING_HOST)
                reporter.report(
                    SyncPeerUpsertRequest(
                        displayName = reporter.displayName,
                        phase = SyncPeerPhase.WAITING_HOST,
                    ),
                    force = true,
                )
                if (polls >= PREPARING_POLL_MAX) {
                    failInRoom(readI18n("modSync.hostPreparingTimeout"), null)
                    return
                }
                polls++
                delay(PREPARING_POLL_MS)
                val polled = client.fetchManifest(ctx.roomKey)
                if (polled == null) {
                    failInRoom(readI18n("modSync.hostPreparingGone"), null)
                    return
                }
                response = polled
            }
            if (!response.isReady) {
                failInRoom(readI18n("modSync.syncFailed"), null)
                return
            }

            val descriptors = response.mods.map { it.toNetwork() }
            val cache = appKoin.get<NetworkModCache>()
            val manager = appKoin.get<ModManager>()
            val allMods = manager.getAllMods()
            val localMatches = findLocalMatchKeys(allMods, descriptors, cache)
            val missing = descriptors.filter { it.cacheKey() !in localMatches }
            logger.info("[MODSYNC] in-room sync: required=${descriptors.size}, missing=${missing.size}")

            if (missing.isNotEmpty()) {
                setInRoomPhase(SyncPeerPhase.DOWNLOADING)
                setDownloadingTitle(missing)
                val downloadingHeartbeat = SyncPeerUpsertRequest(
                    displayName = reporter.displayName,
                    phase = SyncPeerPhase.DOWNLOADING,
                    currentModName = missing.first().name,
                    currentBytes = 0L,
                    currentTotal = missing.first().payloadSize,
                    modIndex = 0,
                    modCount = missing.size,
                )
                reporter.report(downloadingHeartbeat, force = true)
                reporter.startPhaseHeartbeat(downloadingHeartbeat)
                missing.forEachIndexed { index, descriptor ->
                    lastSpeedSampleAt = 0L
                    lastSpeedSampleBytes = 0L
                    lastSpeedBps = 0L
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
                        failInRoom(
                            readI18n("mod.integrityFailed"),
                            readI18n("mod.integrityFailedDetail", I18nType.RWPP, descriptor.name),
                        )
                        return
                    }
                    cache.storeVerified(descriptor, bytes)
                    cache.activate(descriptor)
                    logger.info("[MODSYNC] mod cached and activated OK: ${descriptor.name}")
                }
            }

            // 启用 + 重载（保连接重载：引擎线程与网络保活保持运行，连接不断、聊天可用）
            val applyModCount = missing.size.coerceAtLeast(descriptors.size)
            setInRoomPhase(SyncPeerPhase.APPLYING)
            hideNetworkDownloadDialog()
            val applyingRequest = SyncPeerUpsertRequest(
                displayName = reporter.displayName,
                phase = SyncPeerPhase.APPLYING,
                currentModName = "",
                modIndex = missing.size,
                modCount = applyModCount,
            )
            reporter.report(applyingRequest, force = true)
            reporter.startPhaseHeartbeat(applyingRequest)
            val applyProgressJob = scope.launch(Dispatchers.IO) {
                reportApplyUnitProgress(reporter, applyingRequest)
            }
            val applied = try {
                finalizeModSync(descriptors, cache, manager)
            } finally {
                applyProgressJob.cancel()
            }
            if (!applied) {
                if (cancellingReload) finishCancelReload()
                return
            }
            if (cancellingReload) {
                finishCancelReload()
                return
            }

            // 双校验之二：单位校验和与房主一致（房主注册清单时写入；旧 relay 无此字段则跳过）
            val expectedChecksum = response.hostUnitsChecksum
            if (expectedChecksum != null) {
                val local = appKoin.get<Game>().getUnitsChecksum().toLong()
                if (local != expectedChecksum) {
                    failInRoom(
                        readI18n("modSync.checksumMismatch"),
                        readI18n(
                            "modSync.checksumMismatchDetail",
                            I18nType.RWPP,
                            local.toString(),
                            expectedChecksum.toString(),
                        ),
                    )
                    return
                }
            }

            // apply 后必须确认游戏连接仍活着；保活失败则保持 applying Presence 并重连，禁止僵尸房。
            if (!ensureConnectedOrRejoin()) return

            // 同步完成：synced 心跳保留至开局/断线，玩家行内徽章显示 ✓
            val syncedRequest = SyncPeerUpsertRequest(
                displayName = reporter.displayName,
                phase = SyncPeerPhase.SYNCED,
                modIndex = descriptors.size,
                modCount = descriptors.size,
            )
            reporter.report(syncedRequest, force = true)
            reporter.startPhaseHeartbeat(syncedRequest)
            withContext(Dispatchers.Main.immediate) { inRoomSyncPhase = SyncPeerPhase.SYNCED }
            resetReceivingState()
            logger.info("[MODSYNC] in-room sync completed")
        } catch (e: CancellationException) {
            // clearRoomPresence / cancelInRoomSync 触发，清理由调用方负责
            throw e
        } catch (e: Exception) {
            logger.error("[MODSYNC] in-room sync failed: ${e.stackTraceToString()}")
            failInRoom(readI18n("modSync.syncFailed"), readI18n("modSync.syncFailedDetail", I18nType.RWPP, e.message.orEmpty()))
        }
    }

    /**
     * apply 后若连接已死：保持 applying 心跳（握手放行仍认未 synced），用本轮加入地址重连。
     * 成功则打开房间视图；失败则 [failInRoom]。
     */
    private suspend fun ensureConnectedOrRejoin(): Boolean {
        val game = appKoin.get<Game>()
        // 不能在 relay TCP 刚连上（directJoinServer 返回点）就当真进房：随后 PACKET_RECONNECT_TO
        // 会再断一次。须连续稳定 [CONNECT_STABLE_MS] 才算活着。
        if (waitUntilConnecting(APPLY_CONNECT_WAIT_MS)) return true
        val address = inRoomJoinAddress
        if (address.isNullOrBlank()) {
            failInRoom(readI18n("modSync.reconnectFailed"), null)
            return false
        }
        logger.warn("[MODSYNC] connection lost during apply, rejoining $address")
        withContext(Dispatchers.Main.immediate) {
            UI.showWarning(readI18n("modSync.reconnecting"), false)
        }
        syncEntryActive = true
        syncEntryRetriesLeft = SYNC_ENTRY_MAX_RETRY
        syncEntryAddress = address
        syncEntryRelayUuid = inRoomJoinRelayUuid
        game.cancelJoinServer()
        val result = runCatching {
            game.directJoinServer(address, inRoomJoinRelayUuid, LoadingContext {})
        }.getOrElse { Result.failure(it) }
        if (result.isSuccess && waitUntilConnecting(REJOIN_CONNECT_TIMEOUT_MS)) {
            logger.info("[MODSYNC] rejoin after apply succeeded")
            withContext(Dispatchers.Main.immediate) { UI.showRoomView = true }
            JoinGameEvent(address).broadcastIn()
            scope.launch { delay(ENTRY_GRACE_MS); syncEntryActive = false }
            return true
        }
        logger.warn("[MODSYNC] rejoin after apply failed: ${result.exceptionOrNull()?.message}")
        failInRoom(
            readI18n("modSync.reconnectFailed"),
            result.exceptionOrNull()?.message,
        )
        return false
    }

    /**
     * 等到 [GameRoom.isConnecting] 连续为 true 至少 [CONNECT_STABLE_MS]，以滤掉
     * `PACKET_RECONNECT_TO` 换线时「relay 已连、真主机未注册」的假成功。
     */
    private suspend fun waitUntilConnecting(timeoutMs: Long): Boolean {
        val game = appKoin.get<Game>()
        val deadline = System.currentTimeMillis() + timeoutMs
        var stableSince = 0L
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            if (game.gameRoom.isConnecting) {
                if (stableSince == 0L) stableSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - stableSince >= CONNECT_STABLE_MS) return true
            } else {
                stableSince = 0L
            }
            delay(200L)
        }
        return game.gameRoom.isConnecting
    }

    /** 进房后同步失败：提示并自动退房（模组不一致留在房内无意义）。
     *  Presence/心跳在这里直接清理（[clearRoomPresence] 最后调用，它会取消本协程所在 job，
     *  因此放在所有挂起点之后）；DisconnectEvent 的清理仍作兜底，二者幂等。 */
    private suspend fun failInRoom(message: String, detail: String?) {
        if (cancellingReload) {
            finishCancelReload()
            return
        }
        receivingEpoch.incrementAndGet()
        withContext(Dispatchers.Main.immediate) {
            inRoomSyncPhase = null
            resetReceivingStateOnMain()
            UI.showWarning(detail ?: message, true)
        }
        runCatching { appKoin.get<Game>().gameRoom.disconnect(message) }
        clearRoomPresence()
    }

    /** 房间内取消进行中的模组同步（RoomSelfSyncBar 取消按钮）：取消任务、提示并退房。 */
    fun cancelInRoomSync() {
        if (cancellingReload) return
        if (postJoinJob?.isActive != true && !KeepConnectedReload.active) return
        val applyCancel = KeepConnectedReload.active ||
            inRoomSyncPhase == SyncPeerPhase.APPLYING ||
            KeepConnectedReload.abortToVanilla
        receivingEpoch.incrementAndGet()
        if (applyCancel) {
            KeepConnectedReload.requestAbortToVanilla()
            cancellingReload = true
            scope.launch(Dispatchers.Main.immediate) {
                inRoomSyncPhase = null
                resetReceivingStateOnMain()
                UI.showRoomView = false
                UI.showMultiplayerView = true
            }
            scope.launch(Dispatchers.IO) {
                // 只断网，不立刻 activityResume/`i.q()`：单位表回落仍在工作线程。
                runCatching { appKoin.get<Game>().gameRoom.disconnect("mod sync cancelled") }
                if (!KeepConnectedReload.active && !KeepConnectedReload.didVanillaFallback) {
                    postJoinJob?.cancel()
                    runCatching { appKoin.get<ModManager>().modReloadKeepConnectedVanillaOnly() }
                    finishCancelReload()
                }
            }
            clearRoomPresence()
            return
        }
        // 先作废进度回调，再清 phase：否则 inRoomSyncActive 变 false 后迟到的
        // showNetworkDialog=true 会把下载卡片漏到多人列表上。
        postJoinJob?.cancel()
        scope.launch(Dispatchers.Main.immediate) {
            inRoomSyncPhase = null
            resetReceivingStateOnMain()
            UI.showWarning(readI18n("modSync.syncCancelled"), true)
        }
        scope.launch(Dispatchers.IO) {
            runCatching { appKoin.get<Game>().gameRoom.disconnect("mod sync cancelled") }
        }
        // 直接停心跳并删除 Presence：不再依赖 DisconnectEvent（连接已死等场景它不会来）
        clearRoomPresence()
    }

    private suspend fun finishCancelReload() {
        withContext(Dispatchers.Main.immediate) {
            cancellingReload = false
        }
    }

    /**
     * 平台层被踢告警回调：同步进房窗口内（握手放行依赖房主 1s 轮询看到自己，存在窗口期）
     * 被踢时静默重连重试；窗口外或重试耗尽返回 false，走正常踢人提示与清理。
     *
     * 重试成功时直接打开房间视图并走 [onJoinedRoom] 启动进房后同步——
     * 此时 LoadingView 的首次加入已失败返回，房间视图不会由正常流程打开。
     */
    fun onKickedDuringSyncEntry(kickMessage: String): Boolean {
        // 只抑制校验和踢人（握手放行窗口期）；密码/满员/封禁等其他原因立即走正常提示
        if (!kickMessage.contains("core units are different", ignoreCase = true)) return false
        if (!syncEntryActive) return false
        val address = syncEntryAddress
        if (address == null || syncEntryRetriesLeft <= 0) {
            // 重试耗尽：按同步失败清理（Presence/任务），让用户看到原版踢人提示
            syncEntryActive = false
            scope.launch(Dispatchers.IO) {
                val ctx = pendingPostJoin
                pendingPostJoin = null
                ctx?.reporter?.clear()
            }
            clearRoomPresence()
            return false
        }
        syncEntryRetriesLeft--
        val relayUuid = syncEntryRelayUuid
        logger.warn("[MODSYNC] kicked during sync entry, retry in 1s ($syncEntryRetriesLeft left)")
        syncEntryRetryScheduled = true
        scope.launch(Dispatchers.IO) {
            delay(1_000L)
            syncEntryRetryScheduled = false
            runCatching {
                val game = appKoin.get<Game>()
                game.cancelJoinServer()
                val result = game.directJoinServer(address, relayUuid, LoadingContext {})
                if (result.isSuccess) {
                    logger.info("[MODSYNC] sync entry retry connected")
                    withContext(Dispatchers.Main.immediate) { UI.showRoomView = true }
                    onJoinedRoom()
                    JoinGameEvent(address).broadcastIn()
                } else {
                    // 连接级失败（非踢人，踢人会再走本回调）：放弃重试，按同步失败收尾
                    logger.warn("[MODSYNC] sync entry retry failed: ${result.exceptionOrNull()?.message}")
                    syncEntryActive = false
                    val ctx = pendingPostJoin
                    pendingPostJoin = null
                    ctx?.reporter?.clear()
                    withContext(Dispatchers.Main.immediate) {
                        UI.showWarning(readI18n("modSync.syncFailed"), true)
                    }
                }
            }.onFailure {
                logger.error("[MODSYNC] sync entry retry threw: ${it.stackTraceToString()}")
                syncEntryActive = false
                val ctx = pendingPostJoin
                pendingPostJoin = null
                ctx?.reporter?.clear()
            }
        }
        return true
    }

    /** 依次尝试候选 key；全部 404 返回 null；服务器不可达抛异常。 */
    private suspend fun fetchFirstAvailable(
        client: ModSyncClient,
        keys: List<String>,
    ): Pair<RoomManifestResponse, String>? {
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

    /** 取消正在进行的加入前同步轻量段（LoadingView 取消按钮调用）；进房后的取消走 [cancelInRoomSync]。 */
    fun cancelPreJoin() {
        preJoinJob?.cancel()
    }

    private suspend fun finalizeModSync(
        descriptors: List<NetworkModDescriptor>,
        cache: NetworkModCache,
        manager: ModManager,
    ): Boolean {
        withContext(Dispatchers.Main.immediate) { UI.showNetworkDialog = false }
        val activated = descriptors.mapNotNull { descriptor ->
            cache.find(descriptor)?.let { cache.activate(descriptor) }
        }
        val exactNames = descriptors.map { it.name }.toSet()
        val allMods = manager.getAllMods()
        allMods.forEach { mod -> mod.isEnabled = mod.name in exactNames }
        // 完整状态表随重载传入：磁盘上未登记的游离文件（陈旧 .network.rwmod、导入后从未
        // 重载的模组）扫描后按 ModReloadSelection 语义默认禁用，保证引擎启用集合与房主
        // 清单严格一致；本次新激活的同步文件尚未登记进引擎，必须显式标记启用。
        val enabledByFileName = allMods.associate { mod ->
            java.io.File(mod.path).name.lowercase() to (mod.name in exactNames)
        }.toMutableMap()
        activated.forEach { entry -> enabledByFileName[entry.payloadFile.name.lowercase()] = true }
        // 保连接重载：不停止引擎线程/不重建菜单场景（t.f() 会停主循环使网络保活泵停摆、
        // 连接被超时断开；t.q() 会清空玩家数组摧毁房间状态；k.bo 置位会被网络 tick 直接断连）。
        // 单位表重建后连接与房间不受影响，可继续聊天。
        logger.info("[MODSYNC] calling modReloadKeepConnected ...")
        manager.modReloadKeepConnected(enabledByFileName)
        if (cancellingReload) {
            if (!KeepConnectedReload.didVanillaFallback) {
                manager.modReloadKeepConnectedVanillaOnly()
            }
            return false
        }
        val matched = findLocalMatchKeys(manager.getAllMods(), descriptors, cache)
        if (!descriptors.all { it.cacheKey() in matched }) {
            failInRoom(readI18n("mod.downloadFailed"), readI18n("mod.downloadFailedMissing"))
            return false
        }
        logger.info("[MODSYNC] SUCCESS: mods synced")
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

    /** 加入者进房后同步的当前阶段（驱动房间页 RoomSelfSyncBar；null 表示无进行中同步）。 */
    var inRoomSyncPhase by mutableStateOf<String?>(null)
        private set

    /** 应用阶段取消：已退房，正在回落到原版单位（驱动「正在取消」进度框）。 */
    var cancellingReload by mutableStateOf(false)
        private set

    /** 下载 UI 世代：取消/失败时自增，迟到的进度回调对不上则丢弃。 */
    private val receivingEpoch = AtomicInteger(0)
    @Volatile private var lastUiProgressAt = 0L
    @Volatile private var lastSpeedSampleAt = 0L
    @Volatile private var lastSpeedSampleBytes = 0L
    @Volatile private var lastSpeedBps = 0L

    private suspend fun setInRoomPhase(phase: String) {
        withContext(Dispatchers.Main.immediate) { inRoomSyncPhase = phase }
    }

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
        receivingEpoch.incrementAndGet()
        lastUiProgressAt = 0L
        lastSpeedSampleAt = 0L
        lastSpeedSampleBytes = 0L
        lastSpeedBps = 0L
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
            UI.receivingModSpeedBps = 0L
            // 进房后进度只走 RoomSelfSyncBar，禁止打开全局下载卡片。
            UI.showNetworkDialog = false
        }
    }

    private fun updateDownloadingProgress(
        descriptor: NetworkModDescriptor,
        progress: Float,
        totalCount: Int,
        doneCount: Int,
    ) {
        val epoch = receivingEpoch.get()
        val now = System.currentTimeMillis()
        if (progress < 1f && now - lastUiProgressAt < 200L) return
        lastUiProgressAt = now
        val received = (descriptor.payloadSize * progress).toLong()
        val speedBps = sampleDownloadSpeed(received, now)
        scope.launch(Dispatchers.Main.immediate) {
            if (epoch != receivingEpoch.get()) return@launch
            if (inRoomSyncPhase != SyncPeerPhase.DOWNLOADING) return@launch
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
            UI.receivingModSpeedBps = speedBps
        }
    }

    private fun sampleDownloadSpeed(received: Long, now: Long): Long {
        if (lastSpeedSampleAt == 0L) {
            lastSpeedSampleAt = now
            lastSpeedSampleBytes = received
            return 0L
        }
        val dt = now - lastSpeedSampleAt
        if (dt < 200L) return lastSpeedBps
        val delta = received - lastSpeedSampleBytes
        lastSpeedSampleAt = now
        lastSpeedSampleBytes = received
        lastSpeedBps = if (delta <= 0L) 0L else (delta * 1000L) / dt
        return lastSpeedBps
    }

    private suspend fun hideNetworkDownloadDialog() {
        withContext(Dispatchers.Main.immediate) { UI.showNetworkDialog = false }
    }

    private fun resetReceivingStateOnMain() {
        UI.showNetworkDialog = false
        UI.receivingNetworkDialogTitle = ""
        UI.receivingModName = ""
        UI.receivingModProgress = 0f
        UI.receivingModReceivedBytes = 0L
        UI.receivingModTotalBytes = 0L
        UI.receivingModTotalCount = 0
        UI.receivingModDoneCount = 0
        UI.receivingModSpeedBps = 0L
    }

    private suspend fun resetReceivingState() {
        withContext(Dispatchers.Main.immediate) { resetReceivingStateOnMain() }
    }

    /**
     * 应用阶段把引擎 `Loading units - N (name)` 写进 Presence：
     * [SyncPeerUpsertRequest.currentBytes] = 已加载单位数，[currentModName] = 当前单位名。
     * 与重载弹窗同一数据源，房主待同步条才能显示计数。
     */
    private suspend fun reportApplyUnitProgress(
        reporter: PeerProgressReporter,
        base: SyncPeerUpsertRequest,
    ) {
        var lastCount = -1
        var lastName = ""
        while (currentCoroutineContext().isActive) {
            val parsed = withContext(Dispatchers.Main.immediate) {
                parseEngineLoadProgress(loadingMessage)
            }
            if (parsed != null && (parsed.count != lastCount || parsed.detail.orEmpty() != lastName)) {
                lastCount = parsed.count
                lastName = parsed.detail.orEmpty()
                reporter.report(
                    base.copy(
                        currentModName = lastName,
                        currentBytes = parsed.count.toLong(),
                        currentTotal = 0L,
                    ),
                )
            }
            delay(APPLY_UNIT_PROGRESS_MS)
        }
    }

    /**
     * 加入者向同步服上报 Presence；[force]=false 时按 [PEER_REPORT_MIN_INTERVAL_MS] 节流。
     * [report] 可在任意线程调用（下载进度回调非 suspend）；失败仅记日志。
     * applying / joining / downloading 阶段通过 [startPhaseHeartbeat] 周期刷新，避免超过 relaymod PeerTTL。
     */
    private class PeerProgressReporter(
        private val client: ModSyncClient,
        /** 房间 key（peer 视角轮询 listPeersAsPeer 用）。 */
        val roomKey: String,
        val displayName: String,
    ) {
        private val peerId: String = "p" + UUID.randomUUID().toString().replace("-", "")
        private val peerSecret = AtomicReference<String?>(null)
        private val upsertMutex = Mutex()
        private val lastReportAt = AtomicLong(0L)
        private val heartbeatRequest = AtomicReference<SyncPeerUpsertRequest?>(null)
        private var heartbeatJob: Job? = null
        private val lastErrorKey = AtomicReference<String?>(null)
        private val failCount = AtomicInteger(0)
        private val nextRetryAt = AtomicLong(0L)

        /** 当前持有的一次性 peer secret；首次 PUT 返回前为 null。 */
        fun currentPeerSecret(): String? = peerSecret.get()

        fun report(request: SyncPeerUpsertRequest, force: Boolean = false) {
            heartbeatRequest.set(request)
            val now = System.currentTimeMillis()
            if (now < nextRetryAt.get()) return
            if (!force && now - lastReportAt.get() < PEER_REPORT_MIN_INTERVAL_MS) return
            lastReportAt.set(now)
            scope.launch(Dispatchers.IO) {
                runCatching {
                    upsertMutex.withLock {
                        val issued = client.upsertPeer(roomKey, peerId, request, peerSecret.get())
                        if (!issued.isNullOrBlank()) peerSecret.set(issued)
                    }
                }.onSuccess {
                    failCount.set(0)
                    lastErrorKey.set(null)
                    nextRetryAt.set(0L)
                }.onFailure { error ->
                    val msg = error.message.orEmpty()
                    if (lastErrorKey.getAndSet(msg) != msg) {
                        logger.warn("[MODSYNC] upsertPeer failed: $msg")
                    }
                    if (request.phase == SyncPeerPhase.SYNCED &&
                        (msg.contains("invalid peer progress") ||
                            (error as? ModSyncException)?.statusCode == 400)
                    ) {
                        logger.error(
                            "[MODSYNC] relaymod rejected phase=synced; falling back to applying heartbeat " +
                                "(deploy relaymod with synced support)"
                        )
                        val fallback = request.copy(phase = SyncPeerPhase.APPLYING)
                        heartbeatRequest.set(fallback)
                        failCount.set(0)
                        nextRetryAt.set(0L)
                        report(fallback, force = true)
                        return@onFailure
                    }
                    val n = failCount.incrementAndGet().coerceAtMost(3)
                    val backoff = (2_000L shl (n - 1)).coerceAtMost(8_000L)
                    nextRetryAt.set(System.currentTimeMillis() + backoff)
                }
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
                runCatching { client.deletePeer(roomKey, peerId, peerSecret = peerSecret.get()) }
                    .onFailure { logger.warn("[MODSYNC] deletePeer failed: ${it.message}") }
            }
        }
    }
}
