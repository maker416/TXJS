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
import io.github.rwpp.game.Game
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.io.HashUtils
import io.github.rwpp.io.SizeUtils
import io.github.rwpp.io.AtomicFileUtils.writeBytesAtomic
import io.github.rwpp.logger
import io.github.rwpp.modDir
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
    /** 传输取消标志：断连或校验失败后置 true，房主发送循环据此提前退出。 */
    private var transferCancelled: Boolean = false

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

                if (missingMods.isEmpty()) {
                    net.sendPacketToServer(ModPacket.RequestPacket())
                    allMods.forEach { mod ->
                        if (mod.name in e.requiredMods) {
                            mod.isEnabled = true
                        }
                    }
                    manager.modReload()
                    net.sendPacketToServer(ModPacket.ModReloadFinishPacket())
                } else {
                    synchronized(Logic) {
                        transferCancelled = false
                        modQueue = LinkedList(missingMods)
                        requiredMods = e.requiredMods
                        receivingBuffers.clear()
                        receivedChunkCounts.clear()
                        receivedBytes.clear()
                    }
                    net.sendPacketToServer(ModPacket.RequestPacket().apply {
                        mods = missingMods.joinToString(",")
                    })
                    setDownloadingTitle(0)
                    e.intercept()
                }
            }
        }

        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways(priority = EventPriority.MONITOR) {
            cleanupTransfer()
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
            runCatching {
                val player = room.getPlayerByClient(client!!)!!
                synchronized(Logic) {
                    player.data.ready = false
                    transferCancelled = false
                }
                val requested = packet.mods.split(",").filter { it.isNotBlank() }
                val mods = appKoin.get<ModManager>()
                    .getAllMods()
                    .filter { it.isEnabled && it.name in requested }
                // 异步分块发送，避免大数据在原版网络线程里卡顿
                scope.launch(Dispatchers.IO) {
                    mods.forEach { mod ->
                        synchronized(Logic) {
                            if (transferCancelled) return@forEach
                        }
                        // getBytes() 只调一次：对同一份 ByteArray 既分块又算哈希，避免内存翻倍
                        val bytes = mod.getBytes()
                        val hash = HashUtils.sha256(bytes)
                        val total = bytes.size
                        val chunkSize = ModPacket.CHUNK_SIZE
                        val totalChunks = (total + chunkSize - 1) / chunkSize
                        logger.info("send mod (chunked): ${mod.name}, size=${SizeUtils.byteToMB(total.toLong())}MB, chunks=$totalChunks")
                        var offset = 0
                        var index = 0
                        while (offset < total) {
                            synchronized(Logic) {
                                if (transferCancelled) return@forEach
                            }
                            val end = minOf(offset + chunkSize, total)
                            val chunk = bytes.copyOfRange(offset, end)
                            client.sendPacketToClient(
                                ModPacket.ModChunkPacket().apply {
                                    name = mod.name
                                    chunkIndex = index
                                    this.totalChunks = totalChunks
                                    // 仅首块携带元数据，供客户端校验
                                    if (index == 0) {
                                        totalSize = total.toLong()
                                        sha256 = hash
                                    }
                                    chunkBytes = chunk
                                }
                            )
                            offset = end
                            index++
                        }
                    }
                }
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
            scope.launch(Dispatchers.IO) {
                runCatching {
                    handleChunk(packet, room, net)
                }.onFailure {
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
            }

            true
        }
    }

    /**
     * 处理单个分块：按序写入缓冲，收齐时校验哈希并原子落盘。
     * 所有对共享状态的读写都在 synchronized(Logic) 内。
     */
    private suspend fun handleChunk(packet: ModPacket.ModChunkPacket, room: GameRoom, net: Net) {
        val done = synchronized(Logic) {
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

            receiving.buffer.write(packet.chunkBytes)
            receivedChunkCounts[packet.name] = expected + 1
            receivedBytes[packet.name] = (receivedBytes[packet.name] ?: 0L) + packet.chunkBytes.size

            // 是否收齐（进度 UI 在锁外刷新，避免 suspend 持锁）
            expected + 1 >= receiving.totalChunks && receiving.totalChunks > 0
        }

        // 锁外刷新下载进度（suspend 持锁会触发 critical section 警告）
        updateDownloadingTitle(packet.name)

        if (!done) return

        // 收齐：在锁外做校验与落盘（避免长时间持锁），但文件状态变更重新加锁
        val (name, fullBytes, expectedHash) = synchronized(Logic) {
            val rec = receivingBuffers[packet.name]!!
            Triple(packet.name, rec.buffer.toByteArray(), rec.sha256)
        }

        val actualHash = HashUtils.sha256(fullBytes)
        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
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
        modFile.writeBytesAtomic(fullBytes)

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
            withContext(Dispatchers.Main.immediate) {
                UI.showNetworkDialog = false
            }
            val manager = appKoin.get<ModManager>()
            val mods = manager.getAllMods()
            val req = synchronized(Logic) { requiredMods ?: emptyList() }
            mods.forEach { mod ->
                mod.isEnabled = mod.name in req
            }
            manager.modReload()
            val mods2 = manager.getAllMods()
            if (req.any { m -> m !in mods2.map { it.name } }) {
                cleanupTransfer()
                room.disconnect("Mod download failed.")
                withContext(Dispatchers.Main.immediate) {
                    UI.showWarning("Mod download failed: required mods were not found.", true)
                }
                return
            }

            net.sendPacketToServer(ModPacket.ModReloadFinishPacket())
        } else {
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
            modQueue = null
            requiredMods = null
            receivingBuffers.values.forEach { runCatching { it.buffer.close() } }
            receivingBuffers.clear()
            receivedChunkCounts.clear()
            receivedBytes.clear()
        }
    }

    /** 收到首个分块前初始化下载对话框标题。 */
    private suspend fun setDownloadingTitle(index: Int) {
        val totalSize = appKoin.get<Game>().gameRoom.option.allModsSize
        val name = synchronized(Logic) { modQueue?.peek() ?: "" }
        withContext(Dispatchers.Main.immediate) {
            UI.receivingNetworkDialogTitle =
                "Downloading $name. total: ${SizeUtils.byteToMB(totalSize.toLong())}MB. (0/?)"
            UI.showNetworkDialog = true
        }
    }

    /** 按已收字节 / 总大小刷新下载进度。锁内取数据，锁外刷新 UI。 */
    private suspend fun updateDownloadingTitle(name: String) {
        val data = synchronized(Logic) {
            val totalSize = receivingBuffers[name]?.totalSize ?: 0L
            Triple(
                receivedBytes[name] ?: 0L,
                totalSize,
                requiredMods?.size ?: 1,
            )
        }
        val (received, totalSize, modCount) = data
        withContext(Dispatchers.Main.immediate) {
            UI.receivingNetworkDialogTitle =
                "Downloading $name. ${SizeUtils.byteToMB(received)}/${SizeUtils.byteToMB(totalSize)}MB. (/$modCount)"
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
