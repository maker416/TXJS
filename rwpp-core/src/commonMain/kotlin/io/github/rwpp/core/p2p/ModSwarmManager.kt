/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core.p2p

import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.net.Net
import io.github.rwpp.net.p2p.ModPeerWire
import io.github.rwpp.net.packets.ModPacket
import io.github.rwpp.net.packets.ModPeerPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.coroutines.coroutineContext

/**
 * 模组 P2P 网状互传管理器（Phase 2）。
 *
 * 角色双工：
 * - **seed（服务方）**：本机已下完的 mod 通过 [offerSeed] 登记，[startListening] 开启 TCP 监听，
 *   响应其他客户端的拉块握手（令牌鉴权 + 按缺失位图逐块发送）。
 * - **leech（请求方）**：[pullMod] 逐个候选 peer 尝试直连拉块，地址按 `lanAddresses + observedAddress`
 *   顺序尝试；每个分块经 [offerChunk]（内部即 `ModChunkAssembler.offer`，块级 SHA-256 拦坏块）。
 *
 * **退化底线**：relay 房、未拿到房间令牌、peer 表为空或对端 `listenPort=0` 时，本类完全不参与传输，
 * 调用方必须退回 Phase 1 的房主星型分发路径。
 *
 * 依赖全部构造注入（不在类内直接 `appKoin.get`），便于测试替身。
 */
class ModSwarmManager(
    private val scope: CoroutineScope,
    /** 预留：当前实现不直接经 [Net] 收发（信令走 Logic 的包监听，数据走自有 TCP）。 */
    @Suppress("UNUSED_PARAMETER") net: Net,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val activeConnections = mutableSetOf<Socket>()
    /** 服务方并发连接上限：超出直接拒绝（对端会换下一个 peer 或回退房主）。 */
    private val connectionPermits = Semaphore(MAX_CONCURRENT_CONNECTIONS)

    /** 房间级会话令牌（房主 PeerList 下发）。null/空 = 未入房或已离房，服务方拒绝一切握手。 */
    @Volatile
    var roomToken: String? = null
        private set

    /** cacheKey → 完整已校验 payload（本机可作为 seed 提供的 mod）。 */
    val seedBytes: MutableMap<String, ByteArray> = ConcurrentHashMap()

    /** connectHexId → 已知 peer（房主 PeerList 下发 + HavePacket 增量更新）。 */
    val peers: MutableMap<String, ModPeerPacket.PeerInfo> = ConcurrentHashMap()

    /** 本机 P2P 监听端口；0 = 未监听（对外宣称不支持 P2P）。 */
    val listenPort: Int get() = synchronized(lock) { serverSocket?.localPort ?: 0 }

    /**
     * 启动 P2P 监听（幂等）：已在监听时仅刷新房间令牌并返回现有端口。
     *
     * @param token 房间级会话令牌；null 表示暂未知晓（PeerList 到达后用真实令牌再调一次刷新）。
     * @return 监听端口；绑定失败返回 0（调用方按「不支持 P2P」处理，退化为房主拉取）。
     */
    fun startListening(token: String?): Int = synchronized(lock) {
        token?.takeIf { it.isNotEmpty() }?.let { roomToken = it }
        serverSocket?.let { return@synchronized it.localPort }
        val socket = runCatching { ServerSocket(0) }.getOrElse {
            log("[MODSWARM] failed to bind p2p listen socket: ${it.message}")
            return@synchronized 0
        }
        serverSocket = socket
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(socket) }
        log("[MODSWARM] p2p listening on port ${socket.localPort}")
        socket.localPort
    }

    /**
     * 清空房间会话状态（令牌 + peer 表），保留监听 socket 与 seed 数据。
     * 新一轮模组检查（ModCheckEvent）开始时调用；旧令牌即刻失效，在途/新建握手都会被拒。
     */
    fun resetSession() {
        roomToken = null
        peers.clear()
    }

    /** 关闭监听、断开在途连接并清空全部状态（离房/断连时调用）。 */
    fun stopAll() {
        val socket: ServerSocket?
        val connections: List<Socket>
        synchronized(lock) {
            acceptJob?.cancel()
            acceptJob = null
            socket = serverSocket
            serverSocket = null
            connections = activeConnections.toList()
            activeConnections.clear()
        }
        roomToken = null
        runCatching { socket?.close() }
        connections.forEach { runCatching { it.close() } }
        seedBytes.clear()
        peers.clear()
        log("[MODSWARM] stopped all p2p activity")
    }

    /** 登记一个已完成校验的 mod 为本机 seed（下载完成路径调用）。 */
    fun offerSeed(cacheKey: String, bytes: ByteArray) {
        seedBytes[cacheKey] = bytes
    }

    /**
     * 从候选 peer 拉取一个 mod 的缺失分块。
     *
     * @param peerCandidates 已完成该 mod 的 peer 列表（`listenPort=0` 的会被跳过）。
     * @param wantBitmap 缺失块位图（bit=1 表示需要该块）。
     * @param offerChunk 分块投递回调（内部即 `ModChunkAssembler.offer`，块级哈希拦坏块）；
     *        返回 false 表示坏块/结构错误，立即放弃当前 peer 换下一个。
     * @param onChunkPulled 每成功接受一块后回调（落 partial + 更新进度用）。
     * @return true = 某个 peer 完整交付了请求的分块序列（是否真正装齐由调用方用重组器判定）；
     *         false = 全部候选失败，调用方应回退房主拉取。
     */
    suspend fun pullMod(
        descriptor: NetworkModDescriptor,
        peerCandidates: List<ModPeerPacket.PeerInfo>,
        wantBitmap: BitSet,
        offerChunk: (Int, ByteArray) -> Boolean,
        onChunkPulled: suspend (Int, ByteArray) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val token = roomToken
        if (token.isNullOrEmpty()) {
            log("[MODSWARM] pull skipped: no room token")
            return@withContext false
        }
        for (peer in peerCandidates) {
            if (peer.listenPort <= 0) continue
            if (pullFromPeer(peer, token, descriptor, wantBitmap, offerChunk, onChunkPulled)) {
                return@withContext true
            }
        }
        false
    }

    /** 同网段优先：地址候选按 `lanAddresses + observedAddress` 顺序去重尝试。 */
    private suspend fun pullFromPeer(
        peer: ModPeerPacket.PeerInfo,
        token: String,
        descriptor: NetworkModDescriptor,
        wantBitmap: BitSet,
        offerChunk: (Int, ByteArray) -> Boolean,
        onChunkPulled: suspend (Int, ByteArray) -> Unit,
    ): Boolean {
        val addresses = (peer.lanAddresses + peer.observedAddress).filter { it.isNotBlank() }.distinct()
        for (address in addresses) {
            when (
                runCatching {
                    pullFromAddress(address, peer.listenPort, token, descriptor, wantBitmap, offerChunk, onChunkPulled)
                }.getOrElse {
                    log("[MODSWARM] pull from $address:${peer.listenPort} failed: ${it.message}")
                    PullOutcome.RETRYABLE_FAILURE
                }
            ) {
                PullOutcome.SUCCESS -> {
                    log("[MODSWARM] pulled '${descriptor.name}' from $address:${peer.listenPort}")
                    return true
                }
                // 对端明确拒绝（令牌不符/没有该 mod）：换地址无意义，直接换 peer
                PullOutcome.REFUSED -> return false
                PullOutcome.RETRYABLE_FAILURE -> Unit
            }
        }
        return false
    }

    private enum class PullOutcome { SUCCESS, RETRYABLE_FAILURE, REFUSED }

    private suspend fun pullFromAddress(
        address: String,
        port: Int,
        token: String,
        descriptor: NetworkModDescriptor,
        wantBitmap: BitSet,
        offerChunk: (Int, ByteArray) -> Boolean,
        onChunkPulled: suspend (Int, ByteArray) -> Unit,
    ): PullOutcome {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = FRAME_READ_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            ModPeerWire.encodeHandshake(
                output,
                ModPeerWire.Handshake(token, descriptor.cacheKey(), wantBitmap.toByteArray())
            )
            output.flush()
            when (val status = ModPeerWire.decodeStatus(input)) {
                ModPeerWire.STATUS_OK -> Unit
                // 服务方已应答但拒绝：对端不是可用来源
                else -> {
                    log("[MODSWARM] peer $address:$port refused pull for '${descriptor.name}' (status=$status)")
                    return PullOutcome.REFUSED
                }
            }
            val deadline = System.currentTimeMillis() + PULL_TOTAL_TIMEOUT_MS
            while (true) {
                if (System.currentTimeMillis() > deadline) {
                    log("[MODSWARM] pull from $address:$port hit total time cap")
                    return PullOutcome.RETRYABLE_FAILURE
                }
                when (val frame = ModPeerWire.decodeChunkFrame(input)) {
                    is ModPeerWire.ChunkFrame.End -> return PullOutcome.SUCCESS
                    is ModPeerWire.ChunkFrame.Chunk -> {
                        if (!offerChunk(frame.index, frame.bytes)) {
                            // 坏块/结构错误：内容不可信，放弃该 peer（块级哈希已保证不会落坏数据）
                            log("[MODSWARM] peer $address:$port sent rejected chunk #${frame.index} for '${descriptor.name}'")
                            return PullOutcome.RETRYABLE_FAILURE
                        }
                        onChunkPulled(frame.index, frame.bytes)
                    }
                }
            }
        }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (coroutineContext.isActive) {
            val connection = try {
                socket.accept()
            } catch (e: Exception) {
                if (socket.isClosed) break
                log("[MODSWARM] accept failed: ${e.message}")
                continue
            }
            if (!connectionPermits.tryAcquire()) {
                log("[MODSWARM] connection rejected: concurrency limit reached")
                runCatching { connection.close() }
                continue
            }
            synchronized(lock) { activeConnections.add(connection) }
            scope.launch(Dispatchers.IO) {
                try {
                    serveConnection(connection)
                } catch (e: Exception) {
                    log("[MODSWARM] serve connection failed: ${e.message}")
                } finally {
                    connectionPermits.release()
                    synchronized(lock) { activeConnections.remove(connection) }
                    runCatching { connection.close() }
                }
            }
        }
    }

    /**
     * 服务方单连接处理：验令牌 → 查 seed → 按缺失位图逐块发送。
     * 双重时限：单次读 [SERVE_READ_TIMEOUT_MS]，整连接 [SERVE_TOTAL_TIMEOUT_MS]。
     */
    private fun serveConnection(socket: Socket) {
        socket.soTimeout = SERVE_READ_TIMEOUT_MS
        val deadline = System.currentTimeMillis() + SERVE_TOTAL_TIMEOUT_MS
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        // 握手解码失败（魔数不对/字段非法）直接抛给外层关闭连接
        val handshake = ModPeerWire.decodeHandshake(input)
        val token = roomToken
        if (token.isNullOrEmpty() || handshake.token != token) {
            ModPeerWire.encodeStatus(output, ModPeerWire.STATUS_BAD_TOKEN)
            output.flush()
            return
        }
        val seed = seedBytes[handshake.cacheKey]
        if (seed == null) {
            ModPeerWire.encodeStatus(output, ModPeerWire.STATUS_UNKNOWN_MOD)
            output.flush()
            return
        }
        ModPeerWire.encodeStatus(output, ModPeerWire.STATUS_OK)
        val want = BitSet.valueOf(handshake.wantBitmap)
        val totalChunks = maxOf(1, (seed.size + ModPacket.CHUNK_SIZE - 1) / ModPacket.CHUNK_SIZE)
        var sent = 0
        var index = want.nextSetBit(0)
        while (index in 0 until totalChunks) {
            if (System.currentTimeMillis() > deadline) {
                log("[MODSWARM] serve hit total time cap, closing connection")
                return
            }
            val start = index * ModPacket.CHUNK_SIZE
            val end = minOf(start + ModPacket.CHUNK_SIZE, seed.size)
            ModPeerWire.encodeChunkFrame(output, index, seed.copyOfRange(start, end))
            sent++
            index = want.nextSetBit(index + 1)
        }
        ModPeerWire.encodeEndOfChunks(output)
        output.flush()
        log("[MODSWARM] served $sent chunks (${handshake.cacheKey.take(8)}…) to ${socket.inetAddress?.hostAddress}")
    }

    companion object {
        const val MAX_CONCURRENT_CONNECTIONS = 4
        const val CONNECT_TIMEOUT_MS = 2_000
        const val FRAME_READ_TIMEOUT_MS = 10_000
        const val PULL_TOTAL_TIMEOUT_MS = 60_000L
        const val SERVE_READ_TIMEOUT_MS = 10_000
        const val SERVE_TOTAL_TIMEOUT_MS = 60_000L

        /** 枚举本机站点本地 IPv4 地址（Announce 自报用）；失败返回空表（不影响房主路径）。 */
        fun localLanAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
                .take(ModPeerPacket.MAX_LAN_ADDRESSES)
        }.getOrDefault(emptyList())
    }
}
