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
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.io.HashUtils
import io.github.rwpp.io.SizeUtils
import io.github.rwpp.logger
import io.github.rwpp.net.Client
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.LinkedList

object Logic : Initialization {
    private var playerCount = 0

    private var modQueue: LinkedList<NetworkModDescriptor>? = null
    private var requiredMods: List<String>? = null
    private var requiredDescriptors: List<NetworkModDescriptor>? = null
    private var currentRequestId: Long = 0L
    private var transferGeneration: Long = 0L
    private var manifestTimeoutJob: Job? = null

    private val receivingBuffers: MutableMap<String, ModReceiving> = mutableMapOf()
    private val receivedChunkCounts: MutableMap<String, Int> = mutableMapOf()
    private val receivedBytes: MutableMap<String, Long> = mutableMapOf()

    private val hostPreparedManifests: MutableMap<Client, HostPreparedManifest> = mutableMapOf()

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
    private var hostProgressPollJob: Job? = null

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
                manifestTimeoutJob?.cancel()
                manifestTimeoutJob = scope.launch {
                    delay(MANIFEST_TIMEOUT_MS)
                    val stillWaiting = synchronized(Logic) { currentRequestId == requestId && requiredDescriptors == null }
                    if (stillWaiting) {
                        cleanupTransfer()
                        room.disconnect("Mod manifest request timed out.")
                        withContext(Dispatchers.Main.immediate) {
                            UI.showWarning("Mod manifest request timed out.", true)
                        }
                    }
                }
            }
        }

        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways(priority = EventPriority.MONITOR) {
            cleanupTransfer()
        }

        GlobalEventChannel.filter(PlayerLeaveEvent::class).subscribeAlways(priority = EventPriority.MONITOR) { e ->
            if (!appKoin.get<Game>().gameRoom.isHost) return@subscribeAlways
            val c = e.player.client ?: return@subscribeAlways
            hostModTransferScheduler.cancel(c)
            synchronized(Logic) { hostPreparedManifests.remove(c)?.release() }
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

        net.registerPacketListener<ModPacket.ManifestRequestPacket>(
            ModPacket.MOD_MANIFEST_REQUEST
        ) { client, packet ->
            val room = game.gameRoom
            if (!room.isHost) return@registerPacketListener true
            val conn = client ?: return@registerPacketListener true
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
                // Safety net: if the client never follows up with a download request or finish packet
                // (e.g. it cache-hits but drops before ModReloadFinish, or stalls), release the prepared
                // payload bytes after a grace period so they do not linger on the host.
                scope.launch {
                    delay(HOST_MANIFEST_TTL_MS)
                    synchronized(Logic) {
                        val pending = hostPreparedManifests[conn]
                        if (pending != null && pending.requestId == packet.requestId) {
                            hostPreparedManifests.remove(conn)
                            pending.release()
                            logger.info("[MODSYNC-HOST] released unconsumed prepared manifest for client after TTL (requestId=${packet.requestId})")
                        }
                    }
                }
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
                room.disconnect(packet.errorMessage.ifBlank { "Mod manifest failed." })
                return@registerPacketListener true
            }
            scope.launch(Dispatchers.IO) {
                runCatching { handleManifestResponse(packet, gen, room, net) }.onFailure {
                    logger.error("[MODSYNC] manifest handling failed: ${it.stackTraceToString()}")
                    cleanupTransfer()
                    room.disconnect("Mod manifest failed.")
                    withContext(Dispatchers.Main.immediate) { UI.showWarning("Mod manifest failed: ${it.message}", true) }
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
                val prepared = synchronized(Logic) { hostPreparedManifests.remove(conn) }
                    ?: throw IllegalStateException("Missing prepared manifest for download request")
                require(prepared.requestId == packet.requestId) { "Download request id does not match prepared manifest" }
                val requestedKeys = packet.requestedDescriptors.map { it.cacheKey() }.toSet()
                val sources = prepared.sources.filter { it.descriptor.cacheKey() in requestedKeys }
                require(sources.size == packet.requestedDescriptors.size) { "Requested descriptor was not in host manifest" }
                prepared.sources.filter { it.descriptor.cacheKey() !in requestedKeys }.forEach { it.release() }
                synchronized(Logic) { player.data.ready = false }
                if (sources.isEmpty()) {
                    player.data.ready = false
                    logger.info("[MODSYNC-HOST] no payload requested by ${player.name}")
                } else {
                    hostModTransferScheduler.submit(conn, player.name, packet.requestId, sources)
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
                    room.disconnect("Mod download failed.")
                    withContext(Dispatchers.Main.immediate) {
                        UI.showWarning("Mod download failed: ${it.stackTraceToString()}", true)
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
                synchronized(Logic) { hostPreparedManifests.remove(client)?.release() }
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
    }

    private fun prepareHostManifest(client: Client, requestId: Long, requiredNames: List<String>): HostPreparedManifest {
        val manager = appKoin.get<ModManager>()
        val enabled = manager.getAllMods().filter { it.isEnabled && it.name in requiredNames }
        val byName = enabled.groupBy { it.name }
        val sources = requiredNames.distinct().map { name ->
            val candidates = byName[name].orEmpty()
            require(candidates.size == 1) { "Host mod '$name' is missing or ambiguous" }
            val mod = candidates.single()
            val bytes = mod.getBytes()
            HostModTransferSource(NetworkModDescriptor.fromBytes(mod.name, bytes), bytes)
        }
        return HostPreparedManifest(client, requestId, sources)
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
            logger.info("[MODSYNC] all required mods matched locally/cache, finalizing without download")
            finalizeModSync(requestId, descriptors, net)
            return
        }
        synchronized(Logic) {
            if (requestId != currentRequestId) return
            modQueue = LinkedList(missing)
            requiredDescriptors = descriptors
            clearReceivingLocked()
        }
        net.sendPacketToServer(ModPacket.RequestPacket().apply {
            this.requestId = requestId
            requestedDescriptors = missing
        })
        logger.info("[MODSYNC] requested missing descriptor(s): ${missing.map { it.name }}")
        setDownloadingTitle(0)
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
        var accepted = false
        val done = synchronized(Logic) {
            if (requestId != currentRequestId || packet.requestId != currentRequestId) return@synchronized false
            val queue = modQueue ?: return@synchronized false
            val descriptor = queue.firstOrNull { it.name == packet.name } ?: return@synchronized false
            if (packet.chunkIndex == 0) {
                require(packet.totalSize == descriptor.payloadSize) { "Chunk size does not match manifest" }
                require(packet.sha256.equals(descriptor.normalizedSha256, ignoreCase = true)) { "Chunk hash does not match manifest" }
                require(packet.totalChunks == maxOf(1, ((descriptor.payloadSize + ModPacket.CHUNK_SIZE - 1) / ModPacket.CHUNK_SIZE).toInt())) {
                    "Chunk count does not match manifest"
                }
            }

            val receiving = receivingBuffers.getOrPut(descriptor.cacheKey()) {
                ModReceiving(ByteArrayOutputStream(), descriptor, packet.totalChunks)
            }

            val expected = receivedChunkCounts[descriptor.cacheKey()] ?: 0
            if (packet.chunkIndex != expected) {
                throw IllegalStateException("Mod chunk out of order for ${packet.name}: expected $expected, got ${packet.chunkIndex}")
            }

            accepted = true
            receiving.buffer.write(packet.chunkBytes)
            receivedChunkCounts[descriptor.cacheKey()] = expected + 1
            receivedBytes[descriptor.cacheKey()] = (receivedBytes[descriptor.cacheKey()] ?: 0L) + packet.chunkBytes.size

            expected + 1 >= receiving.totalChunks && receiving.totalChunks > 0
        }

        if (accepted) {
            updateDownloadingTitle(packet.name)
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
        }

        if (!done) return

        val (descriptor, fullBytes) = synchronized(Logic) {
            val queue = modQueue ?: return
            val descriptor = queue.firstOrNull { it.name == packet.name } ?: return
            val rec = receivingBuffers[descriptor.cacheKey()] ?: return
            descriptor to rec.buffer.toByteArray()
        }
        require(fullBytes.size.toLong() == descriptor.payloadSize) { "Downloaded size does not match manifest" }
        val actualHash = HashUtils.sha256(fullBytes)
        if (!actualHash.equals(descriptor.normalizedSha256, ignoreCase = true)) {
            cleanupTransfer()
            room.disconnect("Mod integrity check failed.")
            withContext(Dispatchers.Main.immediate) { UI.showWarning("Mod integrity check failed: ${descriptor.name}", true) }
            return
        }

        val cache = appKoin.get<NetworkModCache>()
        cache.storeVerified(descriptor, fullBytes)
        cache.activate(descriptor)
        logger.info("[MODSYNC] mod cached and activated OK: ${descriptor.name}")

        val queueEmpty = synchronized(Logic) {
            val key = descriptor.cacheKey()
            receivingBuffers.remove(key)
            receivedChunkCounts.remove(key)
            receivedBytes.remove(key)
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
        withContext(Dispatchers.Main.immediate) {
            UI.showNetworkDialog = false
            resetReceivingModState()
        }
        val cache = appKoin.get<NetworkModCache>()
        descriptors.forEach { descriptor -> cache.find(descriptor)?.let { cache.activate(descriptor) } }
        val manager = appKoin.get<ModManager>()
        var mods = manager.getAllMods()
        val exactNames = descriptors.map { it.name }.toSet()
        mods.forEach { mod -> mod.isEnabled = mod.name in exactNames }
        logger.info("[MODSYNC] calling modReload(forceImmediate=true) ...")
        manager.modReload(forceImmediate = true)
        mods = manager.getAllMods()
        val matched = findLocalMatchKeys(mods, descriptors, cache)
        if (!descriptors.all { it.cacheKey() in matched }) {
            cleanupTransfer()
            appKoin.get<Game>().gameRoom.disconnect("Mod download failed.")
            withContext(Dispatchers.Main.immediate) {
                UI.showWarning("Mod download failed: required mods were not found.", true)
            }
            return
        }
        logger.info("[MODSYNC] SUCCESS: sending ModReloadFinishPacket to host")
        net.sendPacketToServer(ModPacket.ModReloadFinishPacket().apply { this.requestId = requestId })
        cleanupTransfer()
    }

    private fun clearReceivingLocked() {
        receivingBuffers.values.forEach { runCatching { it.buffer.close() } }
        receivingBuffers.clear()
        receivedChunkCounts.clear()
        receivedBytes.clear()
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
        }
        hostProgressPollJob?.cancel()
        hostProgressPollJob = null
        scope.launch(Dispatchers.Main.immediate) { UI.hostTransferSnapshots = emptyList() }
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
        UI.receivingNetworkDialogTitle = ""
    }

    private suspend fun setDownloadingTitle(index: Int) {
        val queueSnapshot = synchronized(Logic) { modQueue?.toList().orEmpty() }
        val current = queueSnapshot.getOrNull(index) ?: queueSnapshot.firstOrNull()
        val totalSize = queueSnapshot.sumOf { it.payloadSize }
        val totalCount = synchronized(Logic) { requiredDescriptors?.size ?: queueSnapshot.size.coerceAtLeast(1) }
        withContext(Dispatchers.Main.immediate) {
            UI.receivingNetworkDialogTitle =
                "Downloading ${current?.name.orEmpty()}. total: ${SizeUtils.byteToMB(totalSize)}MB. (0/?)"
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
            val key = descriptor?.cacheKey().orEmpty()
            val totalSize = descriptor?.payloadSize ?: 0L
            val received = receivedBytes[key] ?: 0L
            val totalCount = requiredDescriptors?.size ?: 1
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
}

private data class HostPreparedManifest(
    val client: Client,
    val requestId: Long,
    val sources: List<HostModTransferSource>,
) {
    fun release() = sources.forEach { it.release() }
}

private class ModReceiving(
    val buffer: ByteArrayOutputStream,
    val descriptor: NetworkModDescriptor,
    val totalChunks: Int,
)

private data class DownloadProgressData(
    val receivedBytes: Long,
    val totalBytes: Long,
    val totalCount: Int,
    val doneCount: Int,
)
