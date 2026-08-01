/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.core.p2p.ModSwarmManager
import io.github.rwpp.event.EventPriority
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.DisconnectEvent
import io.github.rwpp.event.events.GameLoadedEvent
import io.github.rwpp.event.events.HostGameEvent
import io.github.rwpp.event.events.ModCheckEvent
import io.github.rwpp.event.events.PlayerJoinEvent
import io.github.rwpp.event.events.PlayerLeaveEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.i18n.GameI18nResolver
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.io.HashUtils
import io.github.rwpp.io.SizeUtils
import io.github.rwpp.logger
import io.github.rwpp.net.Client
import io.github.rwpp.net.HostModTransferScheduler
import io.github.rwpp.net.HostModTransferSource
import io.github.rwpp.net.InternalPacketType
import io.github.rwpp.net.ModChunkAssembler
import io.github.rwpp.net.Net
import io.github.rwpp.net.ServerStatus
import io.github.rwpp.net.packets.ModPacket
import io.github.rwpp.net.packets.ModPeerPacket
import io.github.rwpp.net.packets.ServerPacket
import io.github.rwpp.net.registerPacketListener
import io.github.rwpp.ui.UI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.BitSet
import java.util.LinkedList
import java.util.Locale
import java.util.UUID

object Logic : Initialization {
    private var playerCount = 0

    private var modQueue: LinkedList<NetworkModDescriptor>? = null
    private var requiredMods: List<String>? = null
    private var requiredDescriptors: List<NetworkModDescriptor>? = null
    /** 最近一次 manifest 下发的逐块 SHA-256（mod 名 → 块哈希列表）。 */
    private var manifestChunkHashes: Map<String, List<String>> = emptyMap()
    private var currentRequestId: Long = 0L
    private var transferGeneration: Long = 0L
    private var manifestTimeoutJob: Job? = null

    /** cacheKey → 稀疏重组器（乱序接收 + 块级校验 + 断点续传播种）。 */
    private val assemblers: MutableMap<String, ModChunkAssembler> = mutableMapOf()

    private val hostPreparedManifests: MutableMap<Client, HostPreparedManifest> = mutableMapOf()
    /** manifest 预读字节的 TTL 释放任务（每次收到下载请求时刷新，防止活跃传输中被误释放）。 */
    private val hostManifestTtlJobs: MutableMap<Client, Job> = mutableMapOf()

    /** 房主侧：已 announce 的 P2P peer 登记（房间级，跨多次同步保留）。 */
    private val hostP2pPeers: MutableMap<Client, HostP2pPeer> = mutableMapOf()
    /** 房间级 P2P 会话令牌（HostGameEvent 时生成；空串 = 未在开房）。 */
    private var p2pToken: String = ""

    /** P2P 网状互传管理器（seed 服务 + peer 拉取）。init() 中赋值。 */
    private lateinit var swarmManager: ModSwarmManager

    private val scope = CoroutineScope(SupervisorJob())
    private val hostModTransferScheduler = HostModTransferScheduler(
        scope = scope,
        logInfo = { logger.info(it) },
        logError = { message, error -> logger.error("$message\n${error.stackTraceToString()}") },
    )

    private const val HOST_PROGRESS_POLL_MS = 200L
    private const val MANIFEST_TIMEOUT_MS = 30_000L
    /** 房主侧：为客户端预读的 mod 字节在未被消费时的最长保留时间，超时即释放，防止内存泄漏。 */
    private const val HOST_MANIFEST_TTL_MS = 60_000L
    /**
     * 房主掉线提示的展示延迟：引擎自身的 generic 断连弹窗与 [DisconnectEvent] 几乎同时到达，
     * 稍作延迟再写，确保订阅者最终看到的是「房主掉线导致模组同步中断」而非笼统的连接断开。
     */
    private const val HOST_DISCONNECT_HINT_DELAY_MS = 300L
    private var hostProgressPollJob: Job? = null

    override fun init() {
        swarmManager = ModSwarmManager(scope, appKoin.get<Net>()) { logger.info(it) }
        registerListeners()

        GlobalEventChannel.filter(PlayerJoinEvent::class).subscribeAlways(priority = EventPriority.MONITOR) { e ->
            logger.info("New player: ${e.player.name}")
            synchronized(Logic) {
                val game = appKoin.get<Game>()
                val room = game.gameRoom
                room.teamMode?.onPlayerJoin(room, e.player)
            }
        }

        GlobalEventChannel.filter(ModCheckEvent::class).subscribeAlways(priority = EventPriority.MONITOR) { e ->
            val game = appKoin.get<Game>()
            val room = game.gameRoom
            val net = appKoin.get<Net>()
            if (room.isRWPPRoom && room.option.canTransferMod) {
                val requestId = synchronized(Logic) {
                    transferGeneration++
                    currentRequestId = transferGeneration
                    requiredMods = e.requiredMods
                    requiredDescriptors = null
                    modQueue = null
                    clearReceivingLocked()
                    currentRequestId
                }
                e.intercept()
                logger.info("[MODSYNC] requesting host manifest: requestId=$requestId, required=${e.requiredMods}")
                net.sendPacketToServer(ModPacket.ManifestRequestPacket().apply {
                    this.requestId = requestId
                    requiredNames = e.requiredMods.distinct()
                })
                // P2P：开启新一轮会话（旧令牌/旧 peer 表作废）。relay 房不监听（port=0 宣告不支持），
                // 此时整条链路退化为纯房主星型分发。
                swarmManager.resetSession()
                val p2pPort = if (room.isRelayRoom) 0 else swarmManager.startListening(null)
                net.sendPacketToServer(ModPeerPacket.AnnouncePacket().apply {
                    this.requestId = requestId
                    listenPort = p2pPort
                    lanAddresses = if (p2pPort > 0) ModSwarmManager.localLanAddresses() else emptyList()
                })
                manifestTimeoutJob?.cancel()
                manifestTimeoutJob = scope.launch {
                    delay(MANIFEST_TIMEOUT_MS)
                    val stillWaiting = synchronized(Logic) { currentRequestId == requestId && requiredDescriptors == null }
                    if (stillWaiting) {
                        cleanupTransfer()
                        room.disconnect(readI18n("mod.manifestTimeout"))
                        withContext(Dispatchers.Main.immediate) {
                            UI.showWarning(readI18n("mod.manifestTimeout"), true)
                        }
                    }
                }
            }
        }

        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways(priority = EventPriority.MONITOR) {
            // 传输活跃期掉线：对订阅者明确提示「是房主走了」，而不是静默清理或笼统的传输失败。
            // 本地主动断开（失败/取消）路径会先 cleanupTransfer 清掉状态，interrupted=false，不会覆盖原有提示。
            val interrupted = synchronized(Logic) { requiredMods != null || modQueue != null }
            cleanupTransfer()
            swarmManager.stopAll()
            if (interrupted) {
                scope.launch {
                    delay(HOST_DISCONNECT_HINT_DELAY_MS)
                    withContext(Dispatchers.Main.immediate) {
                        UI.showWarning(readI18n("mod.hostDisconnected"), true)
                    }
                }
            }
        }

        GlobalEventChannel.filter(PlayerLeaveEvent::class).subscribeAlways(priority = EventPriority.MONITOR) { e ->
            if (!appKoin.get<Game>().gameRoom.isHost) return@subscribeAlways
            val c = e.player.client ?: return@subscribeAlways
            val hadActiveTransfer = hostModTransferScheduler.snapshot().any { it.client == c }
            hostModTransferScheduler.cancel(c)
            synchronized(Logic) {
                hostPreparedManifests.remove(c)?.release()
                hostManifestTtlJobs.remove(c)?.cancel()
                hostP2pPeers.remove(c)
            }
            if (hadActiveTransfer) {
                runCatching {
                    appKoin.get<Game>().gameRoom.sendSystemMessage(
                        readI18n("mod.peerLeftDuringTransfer", I18nType.RWPP, e.player.name)
                    )
                }
            }
        }

        GlobalEventChannel.filter(HostGameEvent::class).subscribeAlways(priority = EventPriority.MONITOR) {
            // 开房即生成新的 P2P 房间令牌并清空 peer 登记（上局的令牌/成员全部作废）
            p2pToken = UUID.randomUUID().toString()
            synchronized(Logic) { hostP2pPeers.clear() }
            scope.launch(Dispatchers.IO) {
                val remaining = appKoin.get<ModManager>().getAllMods().filter { it.isNetworkMod && it.isEnabled }
                if (remaining.isNotEmpty()) {
                    logger.warn("[MODSYNC-HOST] network cache mods still enabled after hosting: ${remaining.map { it.name }}")
                    abortHostDueToNetworkMods()
                }
            }
        }

        GlobalEventChannel.filter(GameLoadedEvent::class).subscribeAlways {
            val game = appKoin.get<Game>()
            val settings = appKoin.get<Settings>()

            // 引擎就绪后把 language 解析结果写入 SettingsEngine.forceEnglish，避免启动器/游戏双轨不同步
            runCatching { appKoin.get<GameI18nResolver>().syncGameLanguage() }

            when (settings.effectLimitForAllEffects) {
                "Zero" -> game.setEffectLimitForAllEffects(0)
                "Unlimited" -> game.setEffectLimitForAllEffects(Int.MAX_VALUE)
                else -> {}
            }
        }
    }

    fun getNextPlayerId(): Int {
        synchronized(Logic) {
            return ++playerCount
        }
    }

    /**
     * 开房前禁用所有已启用的网络缓存模组（`*.network.rwmod`），并重建单位表。
     *
     * 使用 [ModManager.modReload] 的 forceImmediate，避免开房 Loading 阶段主循环尚未启动时
     * [ModManager.modSaveChange] 投递到游戏线程后永久等待。
     *
     * @return 是否实际禁用了至少一个网络模组。
     */
    suspend fun disableNetworkModsBeforeHosting(): Boolean {
        val manager = appKoin.get<ModManager>()
        val mods = manager.getAllMods()
        val networkEnabled = mods.filter { it.isNetworkMod && it.isEnabled }
        if (networkEnabled.isEmpty()) return false

        logger.info(
            "[MODSYNC-HOST] disabling network cache mods before hosting: ${networkEnabled.map { it.name }}"
        )
        val enabledByFileName = mods.associate { mod ->
            File(mod.path).name.lowercase() to (mod.isEnabled && !mod.isNetworkMod)
        }
        networkEnabled.forEach { it.isEnabled = false }
        manager.modReload(forceImmediate = true, enabledByFileName = enabledByFileName)
        return true
    }

    fun registerListeners() {
        val game = appKoin.get<Game>()
        val net = appKoin.get<Net>()

        net.registerPacketListener<ServerPacket.ServerInfoGetPacket>(
            InternalPacketType.PRE_GET_SERVER_INFO_FROM_LIST.type
        ) { client, _ ->
            val room = game.gameRoom

            if (room.isHost) return@registerPacketListener true

            client?.sendPacketToClient(
                ServerPacket.ServerInfoReceivePacket(
                    room.localPlayer.name + "'s game",
                    room.getPlayers().size,
                    room.maxPlayerCount,
                    room.selectedMap.mapName,
                    "",
                    "v1.15 - RWPP Client",
                    room.mods.joinToString(", "),
                    if (room.isStartGame) ServerStatus.InGame else ServerStatus.BattleRoom
                )
            )

            true
        }

        net.registerPacketListener<ModPacket.ManifestRequestPacket>(
            ModPacket.MOD_MANIFEST_REQUEST
        ) { client, packet ->
            val room = game.gameRoom
            if (!room.isHost) return@registerPacketListener true
            val conn = client ?: return@registerPacketListener true
            // 模组同步一开始就标记未就绪，覆盖「本地/缓存全命中」不发下载请求的窗口；
            // 直到客户端发来 ModReloadFinish 才恢复 ready，避免房主抢先开局。
            val requestingPlayer = room.getPlayerByClient(conn)
            if (requestingPlayer != null) {
                synchronized(Logic) { requestingPlayer.data.ready = false }
                logger.info(
                    "[MODSYNC-HOST] ManifestRequest from ${requestingPlayer.name}, " +
                        "requestId=${packet.requestId}, set ready=false"
                )
            } else {
                logger.warn(
                    "[MODSYNC-HOST] ManifestRequest from unknown client, requestId=${packet.requestId}"
                )
            }
            scope.launch(Dispatchers.IO) {
                val response = runCatching {
                    prepareHostManifest(conn, packet.requestId, packet.requiredNames)
                }.fold(
                    onSuccess = { prepared ->
                        synchronized(Logic) {
                            hostPreparedManifests.remove(conn)?.release()
                            hostPreparedManifests[conn] = prepared
                        }
                        ModPacket.ManifestResponsePacket().apply {
                            requestId = packet.requestId
                            success = true
                            descriptors = prepared.sources.map { it.descriptor }
                            chunkHashes = prepared.chunkHashes
                        }
                    },
                    onFailure = { error ->
                        logger.error("[MODSYNC-HOST] manifest request failed: ${error.stackTraceToString()}")
                        ModPacket.ManifestResponsePacket().apply {
                            requestId = packet.requestId
                            success = false
                            errorMessage = error.message ?: "Failed to prepare mod manifest"
                        }
                    }
                )
                conn.sendPacketToClient(response)
                // P2P：紧接着在同一条连接上下发房间令牌 + 当前已知 peer 表（不含请求者）。
                // 后到的 peer 通过 HavePacket 转发增量知晓已完成的 seed。
                if (p2pToken.isNotEmpty()) {
                    val peers = synchronized(Logic) {
                        hostP2pPeers.values
                            .filter { it.client != conn && it.connectHexId.isNotBlank() }
                            .map { info ->
                                ModPeerPacket.PeerInfo(
                                    connectHexId = info.connectHexId,
                                    observedAddress = info.observedAddress,
                                    listenPort = info.listenPort,
                                    lanAddresses = info.lanAddresses,
                                    completedMods = info.completedMods.toList(),
                                )
                            }
                    }
                    runCatching {
                        conn.sendPacketToClient(ModPeerPacket.PeerListPacket().apply {
                            requestId = packet.requestId
                            token = p2pToken
                            this.peers = peers
                        })
                    }.onFailure { logger.warn("[MODSYNC-HOST] failed to send peer list: ${it.message}") }
                }
                // Safety net: 预读字节保活 TTL。客户端可能先 P2P、失败后再回炉请求，
                // 因此 manifest 不再随首个下载请求消费，而是保留到 ModReloadFinish/离房；
                // TTL 仅作兜底，且每次收到下载请求时刷新。
                refreshHostManifestTtl(conn, packet.requestId)
            }
            true
        }

        net.registerPacketListener<ModPacket.ManifestResponsePacket>(
            ModPacket.MOD_MANIFEST_RESPONSE
        ) { _, packet ->
            val room = game.gameRoom
            if (room.isHost) return@registerPacketListener true
            val gen = synchronized(Logic) { currentRequestId }
            if (packet.requestId != gen) return@registerPacketListener true
            manifestTimeoutJob?.cancel()
            if (!packet.success) {
                cleanupTransfer()
                room.disconnect(packet.errorMessage.ifBlank { readI18n("mod.manifestFailed") })
                return@registerPacketListener true
            }
            scope.launch(Dispatchers.IO) {
                runCatching { handleManifestResponse(packet, gen, room, net) }.onFailure {
                    logger.error("[MODSYNC] manifest handling failed: ${it.stackTraceToString()}")
                    cleanupTransfer()
                    room.disconnect(readI18n("mod.manifestFailed"))
                    withContext(Dispatchers.Main.immediate) {
                        UI.showWarning(readI18n("mod.manifestFailedDetail", I18nType.RWPP, it.message.orEmpty()), true)
                    }
                }
            }
            true
        }

        net.registerPacketListener<ModPeerPacket.PeerListPacket>(
            ModPeerPacket.MOD_PEER_LIST
        ) { _, packet ->
            val room = game.gameRoom
            if (room.isHost) return@registerPacketListener true
            val gen = synchronized(Logic) { currentRequestId }
            if (packet.requestId != gen) return@registerPacketListener true
            val selfHexId = room.localPlayer.connectHexId
            // 刷新房间令牌（幂等，不动监听端口）并更新 peer 表（排除自己）
            swarmManager.startListening(packet.token)
            packet.peers.forEach { info ->
                if (info.connectHexId.isNotBlank() && info.connectHexId != selfHexId) {
                    swarmManager.peers[info.connectHexId] = info
                }
            }
            logger.info("[MODSYNC] peer list received: ${packet.peers.size} peer(s)")
            true
        }

        net.registerPacketListener<ModPeerPacket.AnnouncePacket>(
            ModPeerPacket.MOD_PEER_ANNOUNCE
        ) { client, packet ->
            val room = game.gameRoom
            if (!room.isHost) return@registerPacketListener true
            val conn = client ?: return@registerPacketListener true
            val player = room.getPlayerByClient(conn)
            synchronized(Logic) {
                val existing = hostP2pPeers[conn]
                hostP2pPeers[conn] = HostP2pPeer(
                    client = conn,
                    connectHexId = player?.connectHexId.orEmpty(),
                    observedAddress = conn.remoteAddress.orEmpty(),
                    listenPort = packet.listenPort,
                    lanAddresses = packet.lanAddresses,
                    completedMods = existing?.completedMods ?: mutableSetOf(),
                )
            }
            logger.info("[MODSYNC-HOST] peer announced: ${player?.name}, port=${packet.listenPort}, lan=${packet.lanAddresses}")
            true
        }

        net.registerPacketListener<ModPeerPacket.HavePacket>(
            ModPeerPacket.MOD_PEER_HAVE
        ) { client, packet ->
            val room = game.gameRoom
            if (room.isHost) {
                // 房主：登记 seed 并转发给其他已 announce 的 peer（填上原始持有者 hexId）
                val conn = client ?: return@registerPacketListener true
                val peer = synchronized(Logic) { hostP2pPeers[conn] } ?: return@registerPacketListener true
                peer.completedMods.addAll(packet.modNames)
                val forwarded = ModPeerPacket.HavePacket().apply {
                    requestId = packet.requestId
                    modNames = packet.modNames
                    ownerHexId = peer.connectHexId
                }
                synchronized(Logic) {
                    hostP2pPeers.keys.forEach { other ->
                        if (other != conn) runCatching { other.sendPacketToClient(forwarded) }
                    }
                }
            } else {
                // 客户端：更新 peer 表中的 completedMods（未知 peer 忽略，需要时会从房主路径兜底）
                if (packet.ownerHexId.isNotBlank()) {
                    swarmManager.peers[packet.ownerHexId]?.let { info ->
                        info.completedMods = (info.completedMods + packet.modNames).distinct()
                    }
                }
            }
            true
        }

        net.registerPacketListener<ModPacket.RequestPacket>(
            ModPacket.MOD_DOWNLOAD_REQUEST
        ) { client, packet ->
            val room = game.gameRoom
            if (!room.isHost) return@registerPacketListener true
            logger.info("[MODSYNC-HOST] received download request from client: requestId=${packet.requestId}, requested=${packet.requestedDescriptors.map { it.name }}")
            runCatching {
                val conn = client!!
                val player = room.getPlayerByClient(conn)
                    ?: throw IllegalStateException("Could not find player for mod download client")
                // manifest 预读字节**保留**（不 remove）：客户端 P2P 拉取失败后会回炉重请求剩余分块；
                // 释放时机为 ModReloadFinish / PlayerLeave / cleanup / TTL 兜底。
                val prepared = synchronized(Logic) { hostPreparedManifests[conn] }
                    ?: throw IllegalStateException("Missing prepared manifest for download request")
                require(prepared.requestId == packet.requestId) { "Download request id does not match prepared manifest" }
                val requestedKeys = packet.requestedDescriptors.map { it.cacheKey() }.toSet()
                val sources = prepared.sources.filter { it.descriptor.cacheKey() in requestedKeys }
                require(sources.size == packet.requestedDescriptors.size) { "Requested descriptor was not in host manifest" }
                synchronized(Logic) { player.data.ready = false }
                refreshHostManifestTtl(conn, packet.requestId)
                if (sources.isEmpty()) {
                    player.data.ready = false
                    logger.info("[MODSYNC-HOST] no payload requested by ${player.name}")
                } else {
                    // 断点续传：客户端位图中已持有的块不再重发
                    val haveBitmaps = packet.haveBitmaps.mapValues { (_, bits) -> BitSet.valueOf(bits) }
                    hostModTransferScheduler.submit(conn, player.name, packet.requestId, sources, haveBitmaps)
                    ensureHostProgressPoll()
                }
            }.onFailure {
                logger.error(it.stackTraceToString())
            }

            true
        }

        net.registerPacketListener<ModPacket.ModChunkPacket>(
            ModPacket.DOWNLOAD_MOD_CHUNK
        ) { _, packet ->
            val room = game.gameRoom
            if (room.isHost) return@registerPacketListener true
            logger.info("[MODSYNC] chunk received: requestId=${packet.requestId}, name='${packet.name}', idx=${packet.chunkIndex}/${packet.totalChunks}, bytes=${packet.chunkBytes.size}, totalSize=${packet.totalSize}")
            val gen = synchronized(Logic) { currentRequestId }
            scope.launch(Dispatchers.IO) {
                runCatching {
                    handleChunk(packet, gen, room, net)
                }.onFailure {
                    logger.error("[MODSYNC] handleChunk FAILED: ${it.stackTraceToString()}")
                    cleanupTransfer()
                    room.disconnect(readI18n("mod.downloadFailed"))
                    withContext(Dispatchers.Main.immediate) {
                        UI.showWarning(readI18n("mod.downloadFailedDetail", I18nType.RWPP, it.stackTraceToString()), true)
                    }
                }
            }

            true
        }

        net.registerPacketListener<ModPacket.ModPackPacket>(
            ModPacket.DOWNLOAD_MOD_PACK
        ) { _, _ -> true }

        net.registerPacketListener<ModPacket.ModReloadFinishPacket>(
            ModPacket.MOD_RELOAD_FINISH
        ) { client, packet ->
            val room = game.gameRoom
            runCatching {
                val player = room.getPlayerByClient(client!!)!!
                player.data.ready = true
                synchronized(Logic) {
                    hostPreparedManifests.remove(client)?.release()
                    hostManifestTtlJobs.remove(client)?.cancel()
                }
                logger.info("[MODSYNC-HOST] ModReloadFinishPacket received from ${player.name}, requestId=${packet.requestId}, set ready=true")
            }.onFailure {
                logger.error("[MODSYNC-HOST] ModReloadFinishPacket handling FAILED: ${it.stackTraceToString()}")
            }

            true
        }

        net.registerPacketListener<ModPacket.ModChunkAckPacket>(
            ModPacket.MOD_CHUNK_ACK
        ) { client, packet ->
            val room = game.gameRoom
            if (!room.isHost) return@registerPacketListener true
            val conn = client ?: return@registerPacketListener true
            hostModTransferScheduler.onAck(conn, packet.requestId, packet.name, packet.ackChunkIndex)
            true
        }

        net.registerPacketListener<ModPacket.ModChunkNakPacket>(
            ModPacket.MOD_CHUNK_NAK
        ) { client, packet ->
            val room = game.gameRoom
            if (!room.isHost) return@registerPacketListener true
            val conn = client ?: return@registerPacketListener true
            logger.info("[MODSYNC-HOST] chunk NAK from client: requestId=${packet.requestId}, name='${packet.name}', idx=${packet.chunkIndex}")
            hostModTransferScheduler.onNak(conn, packet.requestId, packet.name, packet.chunkIndex)
            true
        }
    }

    private fun prepareHostManifest(client: Client, requestId: Long, requiredNames: List<String>): HostPreparedManifest {
        val manager = appKoin.get<ModManager>()
        val enabled = manager.getAllMods().filter { it.isEnabled && it.name in requiredNames }
        val networkMods = enabled.filter { it.isNetworkMod }
        if (networkMods.isNotEmpty()) {
            logger.warn(
                "[MODSYNC-HOST] refused to transfer network cache mods: ${networkMods.map { it.name }}"
            )
            abortHostDueToNetworkMods()
            throw IllegalStateException("Network cache mods cannot be transferred when hosting")
        }
        val byName = enabled.groupBy { it.name }
        val sources = requiredNames.distinct().map { name ->
            val candidates = byName[name].orEmpty()
            require(candidates.size == 1) { "Host mod '$name' is missing or ambiguous" }
            val mod = candidates.single()
            val bytes = mod.getBytes()
            HostModTransferSource(NetworkModDescriptor.fromBytes(mod.name, bytes), bytes)
        }
        // 逐块 SHA-256：接收端据此做块级校验（坏块只重传单块而非整轮作废），也是多源互传的安全基础
        val chunkHashes = sources.associate { source ->
            source.descriptor.name to HashUtils.sha256Chunks(source.bytes, ModPacket.CHUNK_SIZE)
        }
        return HostPreparedManifest(client, requestId, sources, chunkHashes)
    }

    /** 预读 manifest 的 TTL 兜底释放：每次收到 manifest/下载请求时刷新计时。 */
    private fun refreshHostManifestTtl(conn: Client, requestId: Long) {
        val job = scope.launch {
            delay(HOST_MANIFEST_TTL_MS)
            synchronized(Logic) {
                val pending = hostPreparedManifests[conn]
                if (pending != null && pending.requestId == requestId) {
                    hostPreparedManifests.remove(conn)
                    pending.release()
                    logger.info("[MODSYNC-HOST] released unconsumed prepared manifest for client after TTL (requestId=$requestId)")
                }
            }
        }
        synchronized(Logic) {
            hostManifestTtlJobs.remove(conn)?.cancel()
            hostManifestTtlJobs[conn] = job
        }
    }

    private fun abortHostDueToNetworkMods() {
        hostModTransferScheduler.cancelAll()
        synchronized(Logic) {
            hostPreparedManifests.values.forEach { it.release() }
            hostPreparedManifests.clear()
        }
        val message = readI18n("multiplayer.networkModHostBlocked")
        val room = appKoin.get<Game>().gameRoom
        scope.launch(Dispatchers.Main.immediate) {
            UI.showWarning(message, true)
        }
        room.disconnect(message)
    }

    private suspend fun handleManifestResponse(packet: ModPacket.ManifestResponsePacket, requestId: Long, room: GameRoom, net: Net) {
        val cache = appKoin.get<NetworkModCache>()
        val manager = appKoin.get<ModManager>()
        val descriptors = packet.descriptors
        val requiredNamesSnapshot = synchronized(Logic) { requiredMods.orEmpty() }
        require(descriptors.map { it.name }.toSet().containsAll(requiredNamesSnapshot.toSet())) {
            "Host manifest does not include all required mods"
        }
        synchronized(Logic) {
            if (requestId != currentRequestId) return
            requiredDescriptors = descriptors
        }
        val localMatches = findLocalMatchKeys(manager.getAllMods(), descriptors, cache)
        val missing = descriptors.filter { descriptor -> descriptor.cacheKey() !in localMatches }
        if (missing.isEmpty()) {
            // 仍发送空下载请求：房主侧据此保持 ready=false，并释放已预读的 manifest 字节；
            // 随后 finalizeModSync → ModReloadFinish 才把 ready 置回 true。
            logger.info(
                "[MODSYNC] all required mods matched locally/cache, " +
                    "notifying host with empty download request then finalizing"
            )
            net.sendPacketToServer(ModPacket.RequestPacket().apply {
                this.requestId = requestId
                requestedDescriptors = emptyList()
            })
            finalizeModSync(requestId, descriptors, net)
            return
        }
        synchronized(Logic) {
            if (requestId != currentRequestId) return
            clearReceivingLocked()
            modQueue = LinkedList(missing)
            requiredDescriptors = descriptors
            manifestChunkHashes = packet.chunkHashes
            missing.forEach { descriptor ->
                val assembler = ModChunkAssembler(descriptor, packet.chunkHashes[descriptor.name].orEmpty())
                // 断点续传：上次未传完但已通过块级校验的分块重新播种进重组器；
                // 播种时仍会逐块再验哈希，损坏的 partial 块自动丢弃并重下。
                runCatching {
                    cache.partialChunks(descriptor).forEach { (index, bytes) -> assembler.offer(index, bytes) }
                }.onFailure { logger.warn("[MODSYNC] failed to seed partial chunks for '${descriptor.name}': ${it.message}") }
                assemblers[descriptor.cacheKey()] = assembler
            }
        }
        // 极端情况：partial 播种直接拼齐了某个 mod（上次在落盘前进程被杀）——直接走完成路径
        missing.forEach { descriptor -> completeAssemblerIfReady(descriptor, requestId, room, net) }
        val stillMissing = synchronized(Logic) { modQueue?.toList().orEmpty() }
        if (stillMissing.isEmpty()) {
            logger.info("[MODSYNC] all missing mods satisfied by partial resume, finalizing")
            net.sendPacketToServer(ModPacket.RequestPacket().apply {
                this.requestId = requestId
                requestedDescriptors = emptyList()
            })
            finalizeModSync(requestId, descriptors, net)
            return
        }

        // P2P 源选择：peer 表中已有完成该 mod 的 seed（且非 relay 房）→ 先走 P2P 直连拉取；
        // 拉取失败/不完整会回炉房主补请求。seed 为零的 mod 维持房主路径。
        val seedPeers = swarmManager.peers.values.filter { it.listenPort > 0 && it.completedMods.isNotEmpty() }
        val p2pMods = if (room.isRelayRoom || seedPeers.isEmpty()) {
            emptyList()
        } else {
            stillMissing.filter { descriptor -> seedPeers.any { descriptor.name in it.completedMods } }
        }
        val hostMods = stillMissing.filter { descriptor -> p2pMods.none { it.cacheKey() == descriptor.cacheKey() } }

        p2pMods.forEach { descriptor ->
            logger.info("[MODSYNC] '${descriptor.name}' will be pulled from peer(s) first")
            scope.launch(Dispatchers.IO) {
                pullModFromPeers(descriptor, requestId, room, net)
            }
        }

        net.sendPacketToServer(ModPacket.RequestPacket().apply {
            this.requestId = requestId
            requestedDescriptors = hostMods
            haveBitmaps = hostMods.associate { descriptor ->
                descriptor.name to (synchronized(Logic) { assemblers[descriptor.cacheKey()]?.bitmap()?.toByteArray() } ?: ByteArray(0))
            }
        })
        logger.info("[MODSYNC] requested from host: ${hostMods.map { it.name }}; from peers: ${p2pMods.map { it.name }}")
        setDownloadingTitle(0)
    }

    /**
     * P2P 拉取一个 mod：按 peer 表逐个直连候选拉块（每块过块级 SHA-256 + 落 partial）。
     * 成功装齐走 [completeAssemblerIfReady]；失败/不完整回退房主补请求剩余分块。
     */
    private suspend fun pullModFromPeers(descriptor: NetworkModDescriptor, requestId: Long, room: GameRoom, net: Net) {
        val assembler = synchronized(Logic) { assemblers[descriptor.cacheKey()] } ?: return
        val candidates = swarmManager.peers.values
            .filter { peer -> peer.listenPort > 0 && descriptor.name in peer.completedMods }
        if (candidates.isEmpty()) {
            reRequestFromHost(descriptor, requestId, net)
            return
        }
        // wantBitmap = 缺失块（bit=1 表示需要）
        val want = BitSet(assembler.totalChunks).apply {
            set(0, assembler.totalChunks)
            andNot(assembler.bitmap())
        }
        val pulled = runCatching {
            swarmManager.pullMod(
                descriptor = descriptor,
                peerCandidates = candidates,
                wantBitmap = want,
                offerChunk = { index, bytes ->
                    when (assembler.offer(index, bytes)) {
                        is ModChunkAssembler.OfferResult.Accepted,
                        is ModChunkAssembler.OfferResult.Duplicate -> true
                        // 坏块/结构错误：内容不可信，放弃该 peer（块级哈希保证不会落坏数据）
                        else -> false
                    }
                },
                onChunkPulled = { index, bytes ->
                    runCatching {
                        appKoin.get<NetworkModCache>().storePartialChunk(descriptor, index, bytes)
                    }.onFailure {
                        logger.warn("[MODSYNC] failed to persist p2p chunk for '${descriptor.name}' idx=$index: ${it.message}")
                    }
                    updateDownloadingTitle(descriptor.name)
                },
            )
        }.getOrElse {
            logger.warn("[MODSYNC] p2p pull error for '${descriptor.name}': ${it.message}")
            false
        }
        val complete = synchronized(Logic) { assemblers[descriptor.cacheKey()]?.isComplete == true }
        if (pulled && complete) {
            logger.info("[MODSYNC] p2p pull complete for '${descriptor.name}'")
            completeAssemblerIfReady(descriptor, requestId, room, net)
        } else {
            reRequestFromHost(descriptor, requestId, net)
        }
    }

    /** P2P 拉取失败/不完整：回退房主路径，按当前重组器位图补请求剩余分块。 */
    private suspend fun reRequestFromHost(descriptor: NetworkModDescriptor, requestId: Long, net: Net) {
        val stillActive = synchronized(Logic) {
            currentRequestId == requestId && modQueue?.any { it.cacheKey() == descriptor.cacheKey() } == true
        }
        if (!stillActive) return
        logger.warn("[MODSYNC] p2p pull incomplete for '${descriptor.name}', falling back to host")
        net.sendPacketToServer(ModPacket.RequestPacket().apply {
            this.requestId = requestId
            requestedDescriptors = listOf(descriptor)
            haveBitmaps = mapOf(
                descriptor.name to (synchronized(Logic) { assemblers[descriptor.cacheKey()]?.bitmap()?.toByteArray() } ?: ByteArray(0))
            )
        })
    }

    /**
     * Returns the set of cache keys for descriptors that the client already satisfies — either through a
     * verified managed network cache entry, an exact-hash local network mod, or an exact-hash local mod.
     * Identity is always name + payloadSize + sha256; a same-name but different-content mod is NOT a match.
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

    private suspend fun handleChunk(packet: ModPacket.ModChunkPacket, requestId: Long, room: GameRoom, net: Net) {
        var descriptorRef: NetworkModDescriptor? = null
        var nakChunkIndex: Int? = null
        var retryExhausted = false
        val offerResult = synchronized(Logic) {
            if (requestId != currentRequestId || packet.requestId != currentRequestId) return@synchronized null
            val queue = modQueue ?: return@synchronized null
            val descriptor = queue.firstOrNull { it.name == packet.name } ?: return@synchronized null
            if (packet.chunkIndex == 0) {
                require(packet.totalSize == descriptor.payloadSize) { "Chunk size does not match manifest" }
                require(packet.sha256.equals(descriptor.normalizedSha256, ignoreCase = true)) { "Chunk hash does not match manifest" }
                require(packet.totalChunks == maxOf(1, ((descriptor.payloadSize + ModPacket.CHUNK_SIZE - 1) / ModPacket.CHUNK_SIZE).toInt())) {
                    "Chunk count does not match manifest"
                }
            }
            val assembler = assemblers.getOrPut(descriptor.cacheKey()) {
                ModChunkAssembler(descriptor, manifestChunkHashes[descriptor.name].orEmpty())
            }
            descriptorRef = descriptor
            when (val result = assembler.offer(packet.chunkIndex, packet.chunkBytes)) {
                is ModChunkAssembler.OfferResult.Corrupted -> {
                    if (result.retriesLeft > 0) {
                        nakChunkIndex = packet.chunkIndex
                        logger.warn("[MODSYNC] corrupted chunk for '${packet.name}' idx=${packet.chunkIndex}, requesting resend (retriesLeft=${result.retriesLeft})")
                    } else {
                        retryExhausted = true
                        logger.error("[MODSYNC] corrupted chunk for '${packet.name}' idx=${packet.chunkIndex}, retries exhausted")
                    }
                    result
                }
                else -> result
            }
        } ?: return

        val descriptor = descriptorRef ?: return

        // ACK = 「包已到达」，无论内容是否通过校验都回，保证房主流量窗口不泄漏；
        // 内容被拒通过 NAK 单独表达。
        runCatching {
            net.sendPacketToServer(
                ModPacket.ModChunkAckPacket().apply {
                    this.requestId = packet.requestId
                    this.name = packet.name
                    this.ackChunkIndex = packet.chunkIndex
                }
            )
        }.onFailure {
            logger.warn("[MODSYNC] failed to send chunk ACK for '${packet.name}' idx=${packet.chunkIndex}: ${it.message}")
        }

        if (retryExhausted) {
            cleanupTransfer()
            room.disconnect(readI18n("mod.downloadFailed"))
            withContext(Dispatchers.Main.immediate) {
                UI.showWarning(readI18n("mod.chunkRetryExceeded", I18nType.RWPP, descriptor.name), true)
            }
            return
        }

        nakChunkIndex?.let { index ->
            runCatching {
                net.sendPacketToServer(
                    ModPacket.ModChunkNakPacket().apply {
                        this.requestId = packet.requestId
                        this.name = packet.name
                        this.chunkIndex = index
                    }
                )
            }.onFailure {
                logger.warn("[MODSYNC] failed to send chunk NAK for '${packet.name}' idx=$index: ${it.message}")
            }
            return
        }

        when (offerResult) {
            is ModChunkAssembler.OfferResult.Accepted -> {
                // 通过块级校验的新块立即落盘（断点续传），进程被杀/掉线后下次进房可续传
                runCatching {
                    appKoin.get<NetworkModCache>().storePartialChunk(descriptor, packet.chunkIndex, packet.chunkBytes)
                }.onFailure {
                    logger.warn("[MODSYNC] failed to persist partial chunk for '${descriptor.name}' idx=${packet.chunkIndex}: ${it.message}")
                }
                updateDownloadingTitle(packet.name)
            }
            is ModChunkAssembler.OfferResult.Duplicate -> {
                logger.info("[MODSYNC] duplicate chunk ignored for '${packet.name}' idx=${packet.chunkIndex}")
            }
            else -> {}
        }

        completeAssemblerIfReady(descriptor, requestId, room, net)
    }

    /**
     * 重组器收齐某 mod 后：整包 SHA-256 复核（块级校验之上的双保险）→ 验证落盘并激活 →
     * 清理断点续传 partial → 登记为 P2P seed 并向房主宣告 → 队列清空时 finalize。
     */
    private suspend fun completeAssemblerIfReady(descriptor: NetworkModDescriptor, requestId: Long, room: GameRoom, net: Net) {
        val fullBytes = synchronized(Logic) {
            val assembler = assemblers[descriptor.cacheKey()] ?: return
            if (!assembler.isComplete) return
            assembler.assemble()
        }
        require(fullBytes.size.toLong() == descriptor.payloadSize) { "Downloaded size does not match manifest" }
        val actualHash = HashUtils.sha256(fullBytes)
        if (!actualHash.equals(descriptor.normalizedSha256, ignoreCase = true)) {
            cleanupTransfer()
            room.disconnect(readI18n("mod.integrityFailed"))
            withContext(Dispatchers.Main.immediate) {
                UI.showWarning(readI18n("mod.integrityFailedDetail", I18nType.RWPP, descriptor.name), true)
            }
            return
        }

        val cache = appKoin.get<NetworkModCache>()
        cache.storeVerified(descriptor, fullBytes)
        cache.activate(descriptor)
        cache.discardPartial(descriptor)
        logger.info("[MODSYNC] mod cached and activated OK: ${descriptor.name}")

        // P2P：成为该 mod 的 seed（客户端侧、非 relay、正在监听），并向房主宣告（房主再转发）
        if (!room.isHost && !room.isRelayRoom && swarmManager.listenPort > 0) {
            swarmManager.offerSeed(descriptor.cacheKey(), fullBytes)
            runCatching {
                net.sendPacketToServer(ModPeerPacket.HavePacket().apply {
                    this.requestId = requestId
                    modNames = listOf(descriptor.name)
                })
            }.onFailure { logger.warn("[MODSYNC] failed to announce seed for '${descriptor.name}': ${it.message}") }
        }

        val queueEmpty = synchronized(Logic) {
            val key = descriptor.cacheKey()
            assemblers.remove(key)
            modQueue?.removeIf { it.cacheKey() == key }
            modQueue?.isEmpty() ?: true
        }

        if (queueEmpty) {
            finalizeModSync(requestId, synchronized(Logic) { requiredDescriptors.orEmpty() }, net)
        } else {
            withContext(Dispatchers.Main.immediate) { UI.showNetworkDialog = true }
        }
    }

    private suspend fun finalizeModSync(requestId: Long, descriptors: List<NetworkModDescriptor>, net: Net) {
        // 下载完成 ≠ 同步完成：不关闭对话框，切换到「正在应用模组」不确定进度态，
        // 贯穿引擎重载 + 模组校验 + ModReloadFinish 上报全程。
        // 旧实现先关下载卡片再靠通用 LoadingView 的 ReloadMod 事件对覆盖引擎调用区间，
        // 事件之后的校验/上报阶段界面零反馈，表现为「加载圈提前消失」。
        val modCount = descriptors.size.coerceAtLeast(1)
        withContext(Dispatchers.Main.immediate) {
            resetReceivingModState()
            UI.receivingModApplying = true
            UI.receivingModTotalCount = modCount
            UI.receivingModDoneCount = modCount
            UI.receivingNetworkDialogTitle = readI18n("mod.downloadingModComplete", I18nType.RWPP)
            UI.showNetworkDialog = true
        }
        try {
            finalizeModSyncApply(requestId, descriptors, net)
        } catch (e: Throwable) {
            // 应用阶段卡片不再提供取消入口，异常时必须兜底关闭，避免卡片卡死在「正在应用」态
            cleanupTransfer()
            throw e
        }
    }

    private suspend fun finalizeModSyncApply(requestId: Long, descriptors: List<NetworkModDescriptor>, net: Net) {
        val cache = appKoin.get<NetworkModCache>()
        val manager = appKoin.get<ModManager>()
        val modsBefore = manager.getAllMods()
        // 按磁盘文件名选择启用状态：网络模组文件名为 `{name}-{hash}.network.rwmod`，
        // 引擎显示名未必等于 descriptor.name；旧逻辑按显示名开关会把同步模组误禁用，
        // 而校验又只认缓存命中，导致客户端未加载单位却向房主报完成（开局黑屏）。
        val enabledByFileName = linkedMapOf<String, Boolean>()
        modsBefore.forEach { mod ->
            enabledByFileName[File(mod.path).name.lowercase(Locale.ROOT)] = false
        }
        descriptors.forEach { descriptor ->
            val cached = cache.find(descriptor)
            if (cached != null) {
                val active = cache.activate(descriptor)
                enabledByFileName[active.payloadFile.name.lowercase(Locale.ROOT)] = true
                return@forEach
            }
            val local = modsBefore.firstOrNull { mod ->
                !mod.isNetworkMod && modMatchesDescriptor(mod, descriptor)
            }
            if (local != null) {
                enabledByFileName[File(local.path).name.lowercase(Locale.ROOT)] = true
            } else {
                logger.error("[MODSYNC] finalize missing on-disk source for '${descriptor.name}'")
            }
        }
        val enableFiles = enabledByFileName.filterValues { it }.keys
        logger.info(
            "[MODSYNC] calling modReload(forceImmediate=true, enabledByFileName) enable=$enableFiles"
        )
        manager.modReload(forceImmediate = true, enabledByFileName = enabledByFileName)

        val enabledMods = manager.getAllMods().filter { it.isEnabled }
        val unsatisfied = descriptors.filterNot { descriptor ->
            engineHasEnabledDescriptor(enabledMods, descriptor, cache)
        }
        if (unsatisfied.isNotEmpty()) {
            logger.error(
                "[MODSYNC] engine did not enable required mods after reload: ${unsatisfied.map { it.name }}"
            )
            cleanupTransfer()
            appKoin.get<Game>().gameRoom.disconnect(readI18n("mod.downloadFailed"))
            withContext(Dispatchers.Main.immediate) {
                UI.showWarning(readI18n("mod.downloadFailedMissing"), true)
            }
            return
        }
        logger.info(
            "[MODSYNC] SUCCESS: enabled=${enabledMods.map { it.name }}, sending ModReloadFinishPacket"
        )
        net.sendPacketToServer(ModPacket.ModReloadFinishPacket().apply { this.requestId = requestId })
        cleanupTransfer()
    }

    /** 引擎侧已启用且内容与 manifest 描述符一致（不接受「仅缓存命中」）。 */
    private fun engineHasEnabledDescriptor(
        enabledMods: List<Mod>,
        descriptor: NetworkModDescriptor,
        cache: NetworkModCache,
    ): Boolean = enabledMods.any { mod ->
        if (mod.isNetworkMod) {
            cache.descriptorForManagedPath(mod.path) == descriptor ||
                mod.path.contains(descriptor.cacheKey())
        } else {
            modMatchesDescriptor(mod, descriptor)
        }
    }

    private fun modMatchesDescriptor(mod: Mod, descriptor: NetworkModDescriptor): Boolean {
        if (mod.name != descriptor.name) return false
        return runCatching {
            NetworkModDescriptor.fromBytes(mod.name, mod.getBytes()) == descriptor
        }.onFailure {
            logger.warn("[MODSYNC] failed to hash mod '${mod.name}' for finalize check: ${it.message}")
        }.getOrDefault(false)
    }

    private fun clearReceivingLocked() {
        assemblers.clear()
        manifestChunkHashes = emptyMap()
    }

    private fun ensureHostProgressPoll() {
        if (hostProgressPollJob?.isActive == true) return
        hostProgressPollJob = scope.launch {
            try {
                while (true) {
                    val snaps = hostModTransferScheduler.snapshot()
                    withContext(Dispatchers.Main.immediate) { UI.hostTransferSnapshots = snaps }
                    if (snaps.isEmpty()) break
                    delay(HOST_PROGRESS_POLL_MS)
                }
            } finally {
                withContext(Dispatchers.Main.immediate) { UI.hostTransferSnapshots = emptyList() }
            }
        }
    }

    private fun cleanupTransfer() {
        synchronized(Logic) {
            transferGeneration++
            currentRequestId = transferGeneration
            modQueue = null
            requiredMods = null
            requiredDescriptors = null
            clearReceivingLocked()
        }
        manifestTimeoutJob?.cancel()
        manifestTimeoutJob = null
        hostModTransferScheduler.cancelAll()
        synchronized(Logic) {
            hostPreparedManifests.values.forEach { it.release() }
            hostPreparedManifests.clear()
            hostManifestTtlJobs.values.forEach { it.cancel() }
            hostManifestTtlJobs.clear()
        }
        hostProgressPollJob?.cancel()
        hostProgressPollJob = null
        scope.launch(Dispatchers.Main.immediate) {
            UI.hostTransferSnapshots = emptyList()
            UI.receivingModApplying = false
            UI.showNetworkDialog = false
        }
    }

    fun cancelTransfer() {
        cleanupTransfer()
    }

    private fun resetReceivingModState() {
        UI.receivingModName = ""
        UI.receivingModProgress = 0f
        UI.receivingModReceivedBytes = 0L
        UI.receivingModTotalBytes = 0L
        UI.receivingModTotalCount = 0
        UI.receivingModDoneCount = 0
        UI.receivingModApplying = false
        UI.receivingNetworkDialogTitle = ""
    }

    private suspend fun setDownloadingTitle(index: Int) {
        val queueSnapshot = synchronized(Logic) { modQueue?.toList().orEmpty() }
        val current = queueSnapshot.getOrNull(index) ?: queueSnapshot.firstOrNull()
        val totalSize = queueSnapshot.sumOf { it.payloadSize }
        val totalCount = synchronized(Logic) { requiredDescriptors?.size ?: queueSnapshot.size.coerceAtLeast(1) }
        withContext(Dispatchers.Main.immediate) {
            UI.receivingNetworkDialogTitle = readI18n(
                "mod.downloadingTitleStart",
                I18nType.RWPP,
                current?.name.orEmpty(),
                SizeUtils.byteToMB(totalSize).toString(),
                totalCount.toString(),
            )
            UI.receivingModName = current?.name.orEmpty()
            UI.receivingModProgress = 0f
            UI.receivingModReceivedBytes = 0L
            UI.receivingModTotalBytes = 0L
            UI.receivingModTotalCount = totalCount
            UI.receivingModDoneCount = 0
            UI.showNetworkDialog = true
        }
    }

    private suspend fun updateDownloadingTitle(name: String) {
        val data = synchronized(Logic) {
            val descriptor = modQueue?.firstOrNull { it.name == name }
            val assembler = descriptor?.let { assemblers[it.cacheKey()] }
            val totalSize = descriptor?.payloadSize ?: 0L
            val received = assembler?.receivedBytes ?: 0L
            val totalCount = requiredDescriptors?.size ?: 1
            val remaining = modQueue?.size ?: 0
            val done = (totalCount - remaining).coerceIn(0, totalCount)
            DownloadProgressData(received, totalSize, totalCount, done)
        }
        val (received, totalSize, totalCount, done) = data
        val progress = if (totalSize > 0) (received.toFloat() / totalSize).coerceIn(0f, 1f) else 0f
        withContext(Dispatchers.Main.immediate) {
            UI.receivingNetworkDialogTitle = readI18n(
                "mod.downloadingTitleProgress",
                I18nType.RWPP,
                name,
                SizeUtils.byteToMB(received).toString(),
                SizeUtils.byteToMB(totalSize).toString(),
                done.coerceAtLeast(0).toString(),
                totalCount.toString(),
            )
            UI.receivingModName = name
            UI.receivingModProgress = progress
            UI.receivingModReceivedBytes = received
            UI.receivingModTotalBytes = totalSize
            UI.receivingModTotalCount = totalCount
            UI.receivingModDoneCount = done.coerceAtLeast(1)
            UI.showNetworkDialog = true
        }
    }
}

private data class HostPreparedManifest(
    val client: Client,
    val requestId: Long,
    val sources: List<HostModTransferSource>,
    /** mod 名 → 逐块 SHA-256（与 manifest 一同下发）。 */
    val chunkHashes: Map<String, List<String>>,
) {
    fun release() = sources.forEach { it.release() }
}

/** 房主侧登记的 P2P peer（房间级）：announce 信息 + 房主观察地址 + 已完成的 seed mod。 */
private data class HostP2pPeer(
    val client: Client,
    val connectHexId: String,
    val observedAddress: String,
    val listenPort: Int,
    val lanAddresses: List<String>,
    val completedMods: MutableSet<String> = mutableSetOf(),
)

private data class DownloadProgressData(
    val receivedBytes: Long,
    val totalBytes: Long,
    val totalCount: Int,
    val doneCount: Int,
)
