/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.event.EventPriority
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.DisconnectEvent
import io.github.rwpp.event.events.GameLoadedEvent
import io.github.rwpp.event.events.ModCheckEvent
import io.github.rwpp.event.events.PlayerJoinEvent
import io.github.rwpp.event.events.PlayerLeaveEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.io.HashUtils
import io.github.rwpp.io.SizeUtils
import io.github.rwpp.io.AtomicFileUtils.writeBytesAtomic
import io.github.rwpp.logger
import io.github.rwpp.modDir
import io.github.rwpp.net.HostModTransferScheduler
import io.github.rwpp.net.HostModTransferSource
import io.github.rwpp.net.InternalPacketType
import io.github.rwpp.net.Net
import io.github.rwpp.net.ServerStatus
import io.github.rwpp.net.packets.ModPacket
import io.github.rwpp.net.packets.ServerPacket
import io.github.rwpp.net.registerPacketListener
import io.github.rwpp.ui.UI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.LinkedList

//typealias TargetPositionWithUnits = Triple<Double, Double, List<GameUnit>>

object Logic : Initialization {
    private var playerCount = 0

    // ---- mod 传输相关状态：全部在 synchronized(Logic) 下访问 ----
    /** 等待下载的 mod 名队列（客户端侧）。null 表示当前不在传输中。 */
    private var modQueue: LinkedList<String>? = null
    /** 本次房间要求的全部 mod 名（用于下载完成后校验与启用）。 */
    private var requiredMods: List<String>? = null
    /** 客户端下载传输取消标志：断连或校验失败后置 true，后续残留分块据此作废。 */
    private var transferCancelled: Boolean = false
    /**
     * 客户端传输代次号：每次开始新传输（[ModCheckEvent]）或取消（[cleanupTransfer]）时自增。
     * [handleChunk] 协程在创建时捕获当时的代次，进入锁后若发现代次已变（说明这是上一轮残留协程），
     * 立即丢弃该分块——从源头消除「旧会话分块在新会话里绊倒严格顺序校验」导致 0/0 的问题。
     */
    private var transferGeneration: Long = 0L

    /**
     * 客户端按 mod 名重组的缓冲区。
     * value.first = 已收字节流，value.second = 首块携带的元数据（大小/哈希/总块数）。
     */
    private val receivingBuffers: MutableMap<String, ModReceiving> = mutableMapOf()
    /** 客户端侧各 mod 已收到的块数（按序写入校验用）。 */
    private val receivedChunkCounts: MutableMap<String, Int> = mutableMapOf()
    /** 客户端侧各 mod 已收到的字节数（进度展示用）。 */
    private val receivedBytes: MutableMap<String, Long> = mutableMapOf()

    private val scope = CoroutineScope(SupervisorJob())
    /**
     * 房主侧：所有客户端共享一个轮询调度器，避免单个加入者的大 MOD 队列独占上传窗口。
     * 同一客户端重新请求时替换上一轮会话；[PlayerLeaveEvent] 时移除其会话。
     */
    private val hostModTransferScheduler = HostModTransferScheduler(
        scope = scope,
        logInfo = { logger.info(it) },
        logError = { message, error -> logger.error("$message\n${error.stackTraceToString()}") },
    )

    override fun init() {
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
            val manager = appKoin.get<ModManager>()
            val allMods = manager.getAllMods()
            if (room.isRWPPRoom && room.option.canTransferMod) {
                // TODO check different mod data.
                val missingMods = e.requiredMods.toMutableList().apply { removeAll(allMods.map { it.name }) }
                logger.info("[MODSYNC] ModCheckEvent: requiredMods=${e.requiredMods}, localMods=${allMods.map { it.name }}, missingMods=$missingMods, isRWPPRoom=${room.isRWPPRoom}, canTransferMod=${room.option.canTransferMod}")

                if (missingMods.isEmpty()) {
                    logger.info("[MODSYNC] no missing mods, fast path: reload + reloadFinish")
                    net.sendPacketToServer(ModPacket.RequestPacket())
                    allMods.forEach { mod ->
                        if (mod.name in e.requiredMods) {
                            mod.isEnabled = true
                        }
                    }
                    manager.modReload(forceImmediate = true)
                    net.sendPacketToServer(ModPacket.ModReloadFinishPacket())
                } else {
                    synchronized(Logic) {
                        transferCancelled = false
                        // 新一轮传输：作废旧代次，使上一轮残留的 handleChunk 协程在进入锁后立即自废
                        transferGeneration++
                        modQueue = LinkedList(missingMods)
                        requiredMods = e.requiredMods
                        receivingBuffers.clear()
                        receivedChunkCounts.clear()
                        receivedBytes.clear()
                    }
                    net.sendPacketToServer(ModPacket.RequestPacket().apply {
                        mods = missingMods.joinToString(",")
                    })
                    logger.info("[MODSYNC] request sent to host: missingMods=$missingMods, intercepting ModCheckEvent")
                    setDownloadingTitle(0)
                    e.intercept()
                    logger.info("[MODSYNC] ModCheckEvent intercepted, waiting for chunks")
                }
            }
        }

        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways(priority = EventPriority.MONITOR) {
            cleanupTransfer()
        }

        // 房主侧：远端客户端断开时，取消其正在进行的分块发送会话，
        // 避免对着死连接空发、以及该客户端重连后出现并发发送循环。
        GlobalEventChannel.filter(PlayerLeaveEvent::class).subscribeAlways(priority = EventPriority.MONITOR) { e ->
            if (!appKoin.get<Game>().gameRoom.isHost) return@subscribeAlways
            val c = e.player.client ?: return@subscribeAlways
            hostModTransferScheduler.cancel(c)
        }

        GlobalEventChannel.filter(GameLoadedEvent::class).subscribeAlways {
            val game = appKoin.get<Game>()
            val settings = appKoin.get<Settings>()

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

        // ---- 房主侧：收到客户端下载请求后，分块发送每个缺失的 mod ----
        net.registerPacketListener<ModPacket.RequestPacket>(
            ModPacket.MOD_DOWNLOAD_REQUEST
        ) { client, packet ->
            val room = game.gameRoom
            if (!room.isHost) return@registerPacketListener true
            logger.info("[MODSYNC-HOST] received download request from client: requested='${packet.mods}'")
            runCatching {
                val conn = client!!
                val player = room.getPlayerByClient(conn)
                    ?: throw IllegalStateException("Could not find player for mod download client")
                synchronized(Logic) {
                    player.data.ready = false
                    transferCancelled = false
                }
                val requested = packet.mods.split(",").filter { it.isNotBlank() }
                val mods = appKoin.get<ModManager>()
                    .getAllMods()
                    .filter { it.isEnabled && it.name in requested }
                logger.info("[MODSYNC-HOST] queued chunked send for ${mods.size} mod(s) to ${player.name}")
                hostModTransferScheduler.submit(
                    conn,
                    player.name,
                    mods.map { mod ->
                        HostModTransferSource(mod.name) { mod.getBytes() }
                    },
                )
            }.onFailure {
                logger.error(it.stackTraceToString())
            }

            true
        }

        // ---- 客户端侧：接收分块，按 mod 名重组，收齐后校验哈希并原子写 ----
        net.registerPacketListener<ModPacket.ModChunkPacket>(
            ModPacket.DOWNLOAD_MOD_CHUNK
        ) { client, packet ->
            val room = game.gameRoom
            if (room.isHost) return@registerPacketListener true
            logger.info("[MODSYNC] chunk received: name='${packet.name}', idx=${packet.chunkIndex}/${packet.totalChunks}, bytes=${packet.chunkBytes.size}, totalSize=${packet.totalSize}")
            // 捕获当前传输代次：若这是上一轮残留的协程（代次已变），handleChunk 会在锁内立即丢弃
            val gen = synchronized(Logic) { transferGeneration }
            scope.launch(Dispatchers.IO) {
                runCatching {
                    handleChunk(packet, gen, room, net)
                }.onFailure {
                    logger.error("[MODSYNC] handleChunk FAILED: ${it.stackTraceToString()}")
                    cleanupTransfer()
                    room.disconnect("Mod download failed.")
                    withContext(Dispatchers.Main.immediate) {
                        UI.showWarning("Mod download failed: ${it.stackTraceToString()}", true)
                    }
                }
            }

            true
        }

        // 兼容旧版单包协议（v4 不再触发，保留以防万一）
        net.registerPacketListener<ModPacket.ModPackPacket>(
            ModPacket.DOWNLOAD_MOD_PACK
        ) { _, _ ->
            // 旧版房主才会发此包；新版客户端忽略，避免误处理
            true
        }

        net.registerPacketListener<ModPacket.ModReloadFinishPacket>(
            ModPacket.MOD_RELOAD_FINISH
        ) { client, packet ->
            val room = game.gameRoom
            runCatching {
                val player = room.getPlayerByClient(client!!)!!
                player.data.ready = true
                logger.info("[MODSYNC-HOST] ModReloadFinishPacket received from ${player.name}, set ready=true")
            }.onFailure {
                logger.error("[MODSYNC-HOST] ModReloadFinishPacket handling FAILED: ${it.stackTraceToString()}")
            }

            true
        }

        // ---- 房主侧：收到客户端的分块 ACK，释放该客户端的发送窗口（流量控制） ----
        net.registerPacketListener<ModPacket.ModChunkAckPacket>(
            ModPacket.MOD_CHUNK_ACK
        ) { client, packet ->
            val room = game.gameRoom
            if (!room.isHost) return@registerPacketListener true
            val conn = client ?: return@registerPacketListener true
            hostModTransferScheduler.onAck(conn, packet.name, packet.ackChunkIndex)
            true
        }
    }

    /**
     * 处理单个分块：按序写入缓冲，收齐时校验哈希并原子落盘。
     * 所有对共享状态的读写都在 synchronized(Logic) 内。
     */
    private suspend fun handleChunk(packet: ModPacket.ModChunkPacket, generation: Long, room: GameRoom, net: Net) {
        // 本块是否被成功接收并缓冲（用于决定是否回 ACK 释放房主流控窗口）。
        // 代次过期 / 未请求 / 乱序抛异常的情况下都不会置 true。
        var accepted = false
        val done = synchronized(Logic) {
            // 代次校验：若这是上一轮传输残留的协程（generation 已过期），直接丢弃，不触发任何状态变更或顺序异常。
            // 这是「取消→重新加入」后进度卡 0/0 的根因修复点。
            if (generation != transferGeneration) return@synchronized false
            val queue = modQueue ?: return@synchronized false
            if (packet.name !in queue) return@synchronized false   // 未请求的 mod，忽略

            val receiving = receivingBuffers.getOrPut(packet.name) {
                ModReceiving(ByteArrayOutputStream(), packet.totalSize, packet.sha256, packet.totalChunks)
            }
            // 首块携带元数据：若缓冲是新建的，用它初始化；否则以首块为准（防御性覆盖）
            if (packet.chunkIndex == 0) {
                receiving.totalSize = packet.totalSize
                receiving.sha256 = packet.sha256
                receiving.totalChunks = packet.totalChunks
            }

            val expected = receivedChunkCounts[packet.name] ?: 0
            if (packet.chunkIndex != expected) {
                // 乱序或丢块：当前实现要求严格按序，出错即失败
                throw IllegalStateException(
                    "Mod chunk out of order for ${packet.name}: expected $expected, got ${packet.chunkIndex}"
                )
            }

            accepted = true
            receiving.buffer.write(packet.chunkBytes)
            receivedChunkCounts[packet.name] = expected + 1
            receivedBytes[packet.name] = (receivedBytes[packet.name] ?: 0L) + packet.chunkBytes.size

            // 是否收齐（进度 UI 在锁外刷新，避免 suspend 持锁）
            expected + 1 >= receiving.totalChunks && receiving.totalChunks > 0
        }

        // 锁外刷新下载进度（suspend 持锁会触发 critical section 警告）
        updateDownloadingTitle(packet.name)

        // 流量控制：每成功接收并缓冲一块，立即回 ACK，让房主释放该客户端的发送窗口、继续发后续块。
        // 这是「房主不再全速灌包 → 不拖垮游戏线程 → 其它加入者能正常握手」的关键。
        if (accepted) {
            runCatching {
                net.sendPacketToServer(
                    ModPacket.ModChunkAckPacket().apply {
                        this.name = packet.name
                        this.ackChunkIndex = packet.chunkIndex
                    }
                )
            }.onFailure {
                logger.warn("[MODSYNC] failed to send chunk ACK for '${packet.name}' idx=${packet.chunkIndex}: ${it.message}")
            }
        }

        if (!done) return

        logger.info("[MODSYNC] all chunks received for '${packet.name}', start integrity check")
        // 收齐：在锁外做校验与落盘（避免长时间持锁），但文件状态变更重新加锁
        val (name, fullBytes, expectedHash) = synchronized(Logic) {
            val rec = receivingBuffers[packet.name]!!
            Triple(packet.name, rec.buffer.toByteArray(), rec.sha256)
        }

        val actualHash = HashUtils.sha256(fullBytes)
        logger.info("[MODSYNC] hash check '${packet.name}': expected=$expectedHash, actual=$actualHash, match=${actualHash.equals(expectedHash, ignoreCase = true)}")
        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
            logger.warn("[MODSYNC] hash mismatch, aborting transfer")
            cleanupTransfer()
            room.disconnect("Mod integrity check failed.")
            withContext(Dispatchers.Main.immediate) {
                UI.showWarning("Mod integrity check failed: $name", true)
            }
            return
        }

        // 原子写：写临时文件再 rename，避免中途崩溃损坏。
        // 文件名用 {name}.network.rwmod 约定：保留 .rwmod 后缀让引擎能扫描到，
        // .network 后缀用于 UI 区分「网络传输下载的 mod」。
        val modFile = File(modDir, "$name.network.rwmod")
        logger.info("[MODSYNC] writing mod to disk: ${modFile.absolutePath}, size=${fullBytes.size}")
        modFile.writeBytesAtomic(fullBytes)
        logger.info("[MODSYNC] mod written to disk OK: ${modFile.name}")

        // 清理该 mod 的缓冲，并从队列移除
        val queueEmpty = synchronized(Logic) {
            receivingBuffers.remove(packet.name)
            receivedChunkCounts.remove(packet.name)
            receivedBytes.remove(packet.name)
            modQueue?.remove(packet.name)
            modQueue?.isEmpty() ?: true
        }

        if (queueEmpty) {
            // 全部下载完成：启用 mod、reload、校验名字齐全、通知房主
            logger.info("[MODSYNC] queue empty, all mods downloaded, starting finalization")
            withContext(Dispatchers.Main.immediate) {
                UI.showNetworkDialog = false
                resetReceivingModState()
            }
            val manager = appKoin.get<ModManager>()
            val mods = manager.getAllMods()
            val req = synchronized(Logic) { requiredMods ?: emptyList() }
            logger.info("[MODSYNC] enabling required mods: req=$req, currentMods=${mods.map { "${it.name}(enabled=${it.isEnabled})" }}")
            mods.forEach { mod ->
                mod.isEnabled = mod.name in req
            }
            logger.info("[MODSYNC] calling modReload(forceImmediate=true) ...")
            manager.modReload(forceImmediate = true)
            logger.info("[MODSYNC] modReload() returned, re-checking mod presence")
            val mods2 = manager.getAllMods()
            logger.info("[MODSYNC] mods after reload: ${mods2.map { it.name }}")
            if (req.any { m -> m !in mods2.map { it.name } }) {
                logger.warn("[MODSYNC] FAIL: required mods missing after reload, disconnecting")
                cleanupTransfer()
                room.disconnect("Mod download failed.")
                withContext(Dispatchers.Main.immediate) {
                    UI.showWarning("Mod download failed: required mods were not found.", true)
                }
                return
            }

            logger.info("[MODSYNC] SUCCESS: sending ModReloadFinishPacket to host")
            net.sendPacketToServer(ModPacket.ModReloadFinishPacket())
        } else {
            logger.info("[MODSYNC] queue not empty yet, continuing download (remaining=${synchronized(Logic) { modQueue?.size ?: -1 }})")
            withContext(Dispatchers.Main.immediate) {
                UI.showNetworkDialog = true
            }
        }
    }

    /**
     * 清理传输状态：断连、校验失败或下载异常时调用。
     * 重置队列、关闭缓冲、置取消标志（让房主发送循环提前退出）。
     * 注意：已原子写入完成的 mod 文件不会被删除（它们是完整的）；
     * 半成品数据存在于内存缓冲，随 clear 释放，不会落盘。
     */
    private fun cleanupTransfer() {
        synchronized(Logic) {
            transferCancelled = true
            // 作废旧代次：使上一轮已派发但尚未执行的 handleChunk 协程在进入锁后立即自废
            transferGeneration++
            modQueue = null
            requiredMods = null
            receivingBuffers.values.forEach { runCatching { it.buffer.close() } }
            receivingBuffers.clear()
            receivedChunkCounts.clear()
            receivedBytes.clear()
        }
        hostModTransferScheduler.cancelAll()
    }

    /**
     * 供 UI 主动取消传输（如点击「取消下载」按钮）。
     * 同步执行清理，确保状态立即作废、不依赖异步的 [DisconnectEvent]。
     * 房主侧的发送会话会在随后的 [disconnect] 触发 [PlayerLeaveEvent] 时被取消。
     */
    fun cancelTransfer() {
        cleanupTransfer()
    }

    /** 重置下载弹窗的结构化进度状态（须在主线程调用）。 */
    private fun resetReceivingModState() {
        UI.receivingModName = ""
        UI.receivingModProgress = 0f
        UI.receivingModReceivedBytes = 0L
        UI.receivingModTotalBytes = 0L
        UI.receivingModTotalCount = 0
        UI.receivingModDoneCount = 0
        UI.receivingNetworkDialogTitle = ""
    }

    /** 收到首个分块前初始化下载对话框状态。 */
    private suspend fun setDownloadingTitle(index: Int) {
        val totalSize = appKoin.get<Game>().gameRoom.option.allModsSize
        val (name, totalCount) = synchronized(Logic) {
            (modQueue?.peek() ?: "") to (requiredMods?.size ?: 1)
        }
        withContext(Dispatchers.Main.immediate) {
            UI.receivingNetworkDialogTitle =
                "Downloading $name. total: ${SizeUtils.byteToMB(totalSize.toLong())}MB. (0/?)"
            UI.receivingModName = name
            UI.receivingModProgress = 0f
            UI.receivingModReceivedBytes = 0L
            UI.receivingModTotalBytes = 0L
            UI.receivingModTotalCount = totalCount
            UI.receivingModDoneCount = 0
            UI.showNetworkDialog = true
        }
    }

    /** 按已收字节 / 总大小刷新下载进度。锁内取数据，锁外刷新 UI。 */
    private suspend fun updateDownloadingTitle(name: String) {
        val data = synchronized(Logic) {
            val totalSize = receivingBuffers[name]?.totalSize ?: 0L
            val received = receivedBytes[name] ?: 0L
            val totalCount = requiredMods?.size ?: 1
            // 已完成 = 总数 - 队列中剩余（含当前正在下载的，故 done 从 1 起算）
            val remaining = modQueue?.size ?: 0
            val done = (totalCount - remaining).coerceIn(0, totalCount)
            DownloadProgressData(received, totalSize, totalCount, done)
        }
        val (received, totalSize, totalCount, done) = data
        val progress = if (totalSize > 0) (received.toFloat() / totalSize).coerceIn(0f, 1f) else 0f
        withContext(Dispatchers.Main.immediate) {
            UI.receivingNetworkDialogTitle =
                "Downloading $name. ${SizeUtils.byteToMB(received)}/${SizeUtils.byteToMB(totalSize)}MB. (/$totalCount)"
            UI.receivingModName = name
            UI.receivingModProgress = progress
            UI.receivingModReceivedBytes = received
            UI.receivingModTotalBytes = totalSize
            UI.receivingModTotalCount = totalCount
            UI.receivingModDoneCount = done.coerceAtLeast(1)
            UI.showNetworkDialog = true
        }
    }

    //经过测试，分组效果不佳，暂时不使用
    /*
    fun onPathfindingOptimization(targetX: Float, targetY: Float, selectedUnits: List<GameUnit>): List<TargetPositionWithUnits> {
//        val leftX = selectedUnits.minOf { it.x }
//        val rightX = selectedUnits.maxOf { it.x }
//        val topY = selectedUnits.minOf { it.y }
//        val bottomY = selectedUnits.maxOf { it.y }
        // 简单距离迭代，计算每个单位到其他单位的距离，并将距离较短的单位放入同一组
        val groups = mutableListOf<TargetPositionWithUnits>()
        val unassignedUnits = selectedUnits.toMutableList()
        val maxDistance = 10
        while (unassignedUnits.isNotEmpty()) {
            val group = mutableListOf<GameUnit>()
            groups.add(Triple(targetX.toDouble(), targetY.toDouble(), group))
            group.add(unassignedUnits.removeAt(0))
            for (i in unassignedUnits.indices) {
                val unit = unassignedUnits[i]
                val distance = sqrt((unit.x - group.last().x) * (unit.x - group.last().x) + (unit.y - group.last().y) * (unit.y - group.last().y))
                if (distance <= maxDistance) {
                    group.add(unassignedUnits.removeAt(i))
                }
            }
        }

        return groups
    }
    */
}

/**
 * 客户端侧单个 mod 的接收缓冲与元数据。
 */
private class ModReceiving(
    val buffer: ByteArrayOutputStream,
    var totalSize: Long,
    var sha256: String,
    var totalChunks: Int,
)

/** 下载进度快照：用于锁内取数据、锁外刷新 UI。 */
private data class DownloadProgressData(
    val receivedBytes: Long,
    val totalBytes: Long,
    val totalCount: Int,
    val doneCount: Int,
)
