/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import io.github.rwpp.io.HashUtils
import io.github.rwpp.net.packets.ModPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.LinkedHashMap

/**
 * 房主侧 MOD 分块传输调度器。
 *
 * **核心设计（两件事）：**
 * 1. **轮询公平**：所有加入者共享一个轮询队列，新加入者会在老加入者传完之前就开始收块。
 * 2. **流量控制窗口**：每个 client 同时「在途（已发送但尚未被 ACK）」的分块数有上限 [windowSize]。
 *    客户端每收到一块就回 [ModPacket.ModChunkAckPacket]，房主据此释放窗口继续发送。
 *
 * **为什么需要流量控制**：游戏的发送路径是 per-client 异步的——`sendPacketToClient` 只是把分块
 * 入队到该 client 的**无界** `ConcurrentLinkedQueue`，由该 client 自己的发送线程排水写 socket。
 * 若房主以全速（~64MB/s）向某 client 灌包，而该 client 排水较慢，则该 client 的队列会无限膨胀，
 * 同时产生海量临时分配，拖垮房主游戏线程（GC 抖动 / 资源饥饿）——表现为：一个加入者下载期间，
 * 后续加入者连原生握手（玩家列表）都收不到，直到前者传完。窗口限流把在途数据限定在小量
 * （[windowSize] × [ModPacket.CHUNK_SIZE]），发送速率自动适配客户端真实排水速度，从根本消除灌包。
 */
class HostModTransferScheduler(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 每个客户端允许的最大在途（已发未 ACK）分块数。默认 16 ≈ 1MB 在途。 */
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    /** 突发内每块之间的间隔（0 = 靠窗口限流）。 */
    private val chunkDelayMillis: Long = 0L,
    /** 所有会话都被窗口挡住（等待 ACK）时，轮询间隔，避免 CPU 空转。 */
    private val pollWhenBlockedMillis: Long = DEFAULT_POLL_WHEN_BLOCKED_MILLIS,
    private val logInfo: (String) -> Unit = {},
    private val logError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val sessions = LinkedHashMap<Client, HostModTransferSession>()
    private var schedulerJob: Job? = null

    fun submit(client: Client, playerName: String, mods: List<HostModTransferSource>) {
        synchronized(lock) {
            sessions.remove(client)
            sessions[client] = HostModTransferSession(client, playerName, mods, logInfo)
            ensureSchedulerLocked()
        }
    }

    fun cancel(client: Client) {
        synchronized(lock) {
            sessions.remove(client)
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            sessions.clear()
            schedulerJob?.cancel()
            schedulerJob = null
        }
    }

    fun activeClientCount(): Int = synchronized(lock) { sessions.size }

    /**
     * 各下载客户端的进度快照（供房主 UI 展示 per-client 下载进度与当前模组）。
     * 在 [lock] 下遍历当前会话；单个会话字段由 [HostModTransferSession.nextPacket] 在锁外推进，
     * 故单条快照可能差一帧（UI 展示可容忍），但字段读取为原子引用/Int，不会崩。
     */
    fun snapshot(): List<HostTransferSnapshot> = synchronized(lock) {
        sessions.values.map { it.toSnapshot() }
    }

    /**
     * 客户端确认收到一块后调用：释放该 client 一个在途槽，使其窗口内可继续发送。
     * 1:1 语义（每块一个 ACK），依赖底层 TCP 可靠有序交付；重复/陈旧 ACK 安全忽略。
     */
    fun onAck(client: Client, name: String?, chunkIndex: Int) {
        synchronized(lock) {
            val session = sessions[client] ?: return
            if (session.inFlight > 0) {
                session.inFlight--
            }
            ensureSchedulerLocked()
        }
    }

    private fun ensureSchedulerLocked() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch(dispatcher) {
            runScheduler()
        }
    }

    private suspend fun runScheduler() {
        val runningJob = currentCoroutineContext()[Job]
        try {
            while (true) {
                val snapshot = synchronized(lock) {
                    if (sessions.isEmpty()) {
                        schedulerJob = null
                        emptyList()
                    } else {
                        sessions.values.toList()
                    }
                }
                if (snapshot.isEmpty()) return

                var sentAny = false
                for (session in snapshot) {
                    currentCoroutineContext().ensureActive()
                    if (!isActive(session)) continue

                    // 流量控制：窗口满则跳过该会话，本轮不发送（等 ACK 释放）
                    val roomInWindow = synchronized(lock) { session.inFlight < windowSize }
                    if (!roomInWindow) continue

                    val packet = try {
                        session.nextPacket()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        removeIfCurrent(session)
                        logError("[MODSYNC-HOST] failed to send mods to ${session.playerName}", e)
                        continue
                    }

                    if (packet == null) {
                        removeIfCurrent(session)
                        logInfo("[MODSYNC-HOST] all mods sent to ${session.playerName}, waiting for client ModReloadFinishPacket")
                        continue
                    }

                    if (!isActive(session)) continue
                    // 占用一个在途槽；客户端回 ACK 后于 [onAck] 释放
                    synchronized(lock) { session.inFlight++ }
                    session.client.sendPacketToClient(packet)
                    sentAny = true
                    yield()
                    if (chunkDelayMillis > 0) delay(chunkDelayMillis)
                }

                // 所有会话都被窗口挡住（等 ACK）或本轮无进展时，短暂等待再轮询，避免空转烧 CPU
                if (!sentAny) delay(pollWhenBlockedMillis) else yield()
            }
        } finally {
            synchronized(lock) {
                if (schedulerJob === runningJob) schedulerJob = null
            }
        }
    }

    private fun isActive(session: HostModTransferSession): Boolean =
        synchronized(lock) { sessions[session.client] === session }

    private fun removeIfCurrent(session: HostModTransferSession) {
        synchronized(lock) {
            if (sessions[session.client] === session) {
                sessions.remove(session.client)
            }
        }
    }

    companion object {
        /** 默认在途窗口：32 块 ≈ 2MB。远小于「无界灌包」，又能在 ~100ms RTT 链路上维持 ~20MB/s 吞吐。 */
        const val DEFAULT_WINDOW_SIZE: Int = 32
        const val DEFAULT_POLL_WHEN_BLOCKED_MILLIS: Long = 3L
    }
}

data class HostModTransferSource(
    val name: String,
    val readBytes: () -> ByteArray,
)

/**
 * 房主侧单个客户端的 MOD 同步进度快照（只读、不可变），供 UI 展示。
 *
 * @property client 对应的客户端连接（与 [io.github.rwpp.game.Player.client] 用同一对象，便于匹配玩家行）
 * @property playerName 玩家名（诊断/兜底展示用）
 * @property currentModName 正在发送的模组名
 * @property sentBytes 当前模组已发送字节数
 * @property totalBytes 当前模组总字节数
 * @property modIndex 当前/下一个模组的 0 基序号
 * @property modCount 本次需传输的模组总数
 */
data class HostTransferSnapshot(
    val client: Client,
    val playerName: String,
    val currentModName: String,
    val sentBytes: Long,
    val totalBytes: Long,
    val modIndex: Int,
    val modCount: Int,
)

private class HostModTransferSession(
    val client: Client,
    val playerName: String,
    private val sources: List<HostModTransferSource>,
    private val logInfo: (String) -> Unit,
) {
    private var sourceIndex = 0
    private var currentPayload: HostModPayload? = null
    private var offset = 0
    private var chunkIndex = 0
    /** 已发送但尚未被客户端 ACK 的分块数（流量控制窗口占用）。 */
    var inFlight: Int = 0

    /**
     * 当前进度快照（当前模组名、已发/总字节、第几个模组）。字段为原子读取，UI 容忍瞬时不一致。
     * - [HostTransferSnapshot.sentBytes] = [offset]（当前模组已切片发出的字节）。
     * - [HostTransferSnapshot.totalBytes] = 当前模组完整字节数。
     * - [HostTransferSnapshot.modIndex] = [sourceIndex]（0 基）。
     */
    fun toSnapshot(): HostTransferSnapshot {
        val payload = currentPayload
        val modName = payload?.name
            ?: if (sourceIndex < sources.size) sources[sourceIndex].name else ""
        val total = payload?.bytes?.size ?: 0
        return HostTransferSnapshot(
            client = client,
            playerName = playerName,
            currentModName = modName,
            sentBytes = if (payload != null) offset.toLong() else 0L,
            totalBytes = total.toLong(),
            modIndex = sourceIndex,
            modCount = sources.size,
        )
    }

    fun nextPacket(): ModPacket.ModChunkPacket? {
        val payload = currentPayload ?: loadNextPayload() ?: return null
        val end = if (payload.bytes.isEmpty()) 0 else minOf(offset + ModPacket.CHUNK_SIZE, payload.bytes.size)
        val packet = ModPacket.ModChunkPacket().apply {
            name = payload.name
            chunkIndex = this@HostModTransferSession.chunkIndex
            totalChunks = payload.totalChunks
            if (chunkIndex == 0) {
                totalSize = payload.bytes.size.toLong()
                sha256 = payload.sha256
            }
            chunkBytes = payload.bytes.copyOfRange(offset, end)
        }

        if (payload.bytes.isEmpty()) {
            finishCurrentPayload(1)
        } else {
            offset = end
            chunkIndex++
            if (offset >= payload.bytes.size) {
                finishCurrentPayload(chunkIndex)
            }
        }

        return packet
    }

    private fun loadNextPayload(): HostModPayload? {
        if (sourceIndex >= sources.size) return null
        val source = sources[sourceIndex]
        val bytes = source.readBytes()
        val totalChunks = maxOf(1, (bytes.size + ModPacket.CHUNK_SIZE - 1) / ModPacket.CHUNK_SIZE)
        val payload = HostModPayload(source.name, bytes, HashUtils.sha256(bytes), totalChunks)
        currentPayload = payload
        offset = 0
        chunkIndex = 0
        logInfo("[MODSYNC-HOST] sending mod to $playerName (chunked): ${source.name}, size=${bytes.size}, chunks=$totalChunks")
        return payload
    }

    private fun finishCurrentPayload(sentChunks: Int) {
        val payload = currentPayload
        if (payload != null) {
            logInfo("[MODSYNC-HOST] finished sending mod to $playerName: ${payload.name}, sentChunks=$sentChunks")
        }
        sourceIndex++
        currentPayload = null
        offset = 0
        chunkIndex = 0
    }
}

private data class HostModPayload(
    val name: String,
    val bytes: ByteArray,
    val sha256: String,
    val totalChunks: Int,
)
