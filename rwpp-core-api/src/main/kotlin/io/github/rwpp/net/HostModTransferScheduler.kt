/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import io.github.rwpp.game.mod.NetworkModDescriptor
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
import java.util.BitSet
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 房主侧 MOD 分块传输调度器。
 *
 * **核心设计（四件事）：**
 * 1. **轮询公平**：所有加入者共享一个轮询队列，新加入者会在老加入者传完之前就开始收块。
 * 2. **流量控制窗口**：每个 client 同时「在途（已发送但尚未被 ACK）」的分块数有上限 [windowSize]。
 * 3. **断点跳块**：[submit] 携带客户端已有分块位图（断点续传），已持有的块不再重发。
 * 4. **NAK 重传**：客户端块级校验失败会回 NAK（[onNak]），坏块进入重传队列**优先于**正常序列重发；
 *    已发完的 payload 字节会保留到会话结束，以支持跨 mod 的迟到的重传请求。
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

    /**
     * @param haveBitmaps mod 名 → 客户端已持有分块位图（断点续传）。对应位为 1 的块不再发送。
     */
    fun submit(
        client: Client,
        playerName: String,
        requestId: Long,
        mods: List<HostModTransferSource>,
        haveBitmaps: Map<String, BitSet> = emptyMap(),
    ) {
        synchronized(lock) {
            sessions.remove(client)?.releaseRemaining()
            sessions[client] = HostModTransferSession(client, playerName, requestId, mods, haveBitmaps, logInfo)
            ensureSchedulerLocked()
        }
    }

    fun cancel(client: Client) {
        synchronized(lock) {
            sessions.remove(client)?.releaseRemaining()
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            sessions.values.forEach { it.releaseRemaining() }
            sessions.clear()
            schedulerJob?.cancel()
            schedulerJob = null
        }
    }

    fun activeClientCount(): Int = synchronized(lock) { sessions.size }

    /**
     * 各下载客户端的进度快照（供房主 UI 展示 per-client 下载进度与当前模组）。
     */
    fun snapshot(): List<HostTransferSnapshot> = synchronized(lock) {
        sessions.values.map { it.toSnapshot() }
    }

    /**
     * 客户端确认收到一块后调用：释放该 client 一个在途槽，使其窗口内可继续发送。
     */
    fun onAck(client: Client, requestId: Long, name: String?, chunkIndex: Int) {
        synchronized(lock) {
            val session = sessions[client] ?: return
            if (session.requestId != requestId) return
            if (session.inFlight > 0) {
                session.inFlight--
            }
            ensureSchedulerLocked()
        }
    }

    /**
     * 客户端报告某块内容校验失败（坏块）：把该块压入重传队列，优先于正常序列重发。
     * 会话不存在 / requestId 不符 / 块越界时静默忽略。
     */
    fun onNak(client: Client, requestId: Long, name: String, chunkIndex: Int) {
        synchronized(lock) {
            val session = sessions[client] ?: return
            if (session.requestId != requestId) return
            session.enqueueResend(name, chunkIndex)
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

                    val roomInWindow = synchronized(lock) { session.inFlight < windowSize }
                    if (!roomInWindow) continue

                    val packet = try {
                        session.nextPacket()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        removeIfCurrent(session)
                        session.releaseRemaining()
                        logError("[MODSYNC-HOST] failed to send mods to ${session.playerName}", e)
                        continue
                    }

                    if (packet == null) {
                        removeIfCurrent(session)
                        session.releaseRemaining()
                        logInfo("[MODSYNC-HOST] all mods sent to ${session.playerName}, waiting for client ModReloadFinishPacket")
                        continue
                    }

                    if (!isActive(session)) continue
                    synchronized(lock) { session.inFlight++ }
                    session.client.sendPacketToClient(packet)
                    sentAny = true
                    yield()
                    if (chunkDelayMillis > 0) delay(chunkDelayMillis)
                }

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
        /** 默认在途窗口：32 块 ≈ 2MB。 */
        const val DEFAULT_WINDOW_SIZE: Int = 32
        const val DEFAULT_POLL_WHEN_BLOCKED_MILLIS: Long = 3L
    }
}

class HostModTransferSource(
    val descriptor: NetworkModDescriptor,
    val bytes: ByteArray,
    private val onRelease: () -> Unit = {},
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}

/**
 * 房主侧单个客户端的 MOD 同步进度快照（只读、不可变），供 UI 展示。
 *
 * 进度语义：UI 应使用 [currentModProgressBytes] / [totalBytes] 计算**当前模组**的进度。
 * [sentBytes] 是整个会话的累计已发字节（含已传完的模组），与 [totalBytes]（仅当前模组大小）
 * 不属同一口径，直接相除在第 2 个模组起会失真。
 */
data class HostTransferSnapshot(
    val client: Client,
    val playerName: String,
    val currentModName: String,
    val sentBytes: Long,
    val totalBytes: Long,
    val modIndex: Int,
    val modCount: Int,
    /** 当前模组已交付给客户端的字节数 = 断点续传免发字节 + 本次按序已发字节（不含 NAK 重发），上限为 [totalBytes]。 */
    val currentModProgressBytes: Long,
)

private class HostModTransferSession(
    val client: Client,
    val playerName: String,
    val requestId: Long,
    sources: List<HostModTransferSource>,
    haveBitmaps: Map<String, BitSet>,
    private val logInfo: (String) -> Unit,
) {
    private val sources = sources.toMutableList()
    private val haveBitmaps = haveBitmaps.toMap()
    private var sourceIndex = 0
    private var currentPayload: HostModPayload? = null
    private var nextChunkIndex = 0
    /** (mod 名, 块序号) 重传队列：NAK 驱动的坏块重发，优先于正常发送序列。 */
    private val resendQueue = ArrayDeque<Pair<String, Int>>()
    /** 已发完的 payload（按 mod 名），保留到会话结束以支持迟到的 NAK 重传。 */
    private val sentPayloads = LinkedHashMap<String, HostModPayload>()
    /** 已发送但尚未被客户端 ACK 的分块数（流量控制窗口占用）。 */
    var inFlight: Int = 0
    /** 累计已发字节（进度快照用）。 */
    private var sentBytesTotal = 0L
    /** 当前模组已交付字节：断点续传免发字节 + 按序已发字节（不含 NAK 重发），随 payload 切换重置。 */
    private var currentModProgressBytes = 0L

    fun toSnapshot(): HostTransferSnapshot {
        val payload = currentPayload
        val source = if (sourceIndex < sources.size) sources[sourceIndex] else null
        val modName = payload?.descriptor?.name ?: source?.descriptor?.name ?: ""
        val total = payload?.bytes?.size ?: source?.bytes?.size ?: 0
        return HostTransferSnapshot(
            client = client,
            playerName = playerName,
            currentModName = modName,
            sentBytes = sentBytesTotal,
            totalBytes = total.toLong(),
            modIndex = sourceIndex,
            modCount = sources.size,
            currentModProgressBytes = currentModProgressBytes.coerceAtMost(total.toLong()),
        )
    }

    /** NAK 请求重传。仅当对应 payload 仍在内存（当前或已发完）时入队，否则静默丢弃。 */
    fun enqueueResend(name: String, chunkIndex: Int) {
        val payload = payloadFor(name) ?: return
        if (chunkIndex !in 0 until payload.totalChunks) return
        if (resendQueue.any { it.first == name && it.second == chunkIndex }) return
        resendQueue.addLast(name to chunkIndex)
        logInfo("[MODSYNC-HOST] queued chunk resend for $playerName: $name#$chunkIndex")
    }

    fun nextPacket(): ModPacket.ModChunkPacket? {
        // 重传优先：NAK 的块先于正常序列发出
        while (resendQueue.isNotEmpty()) {
            val (name, index) = resendQueue.removeFirst()
            val payload = payloadFor(name) ?: continue
            if (index !in 0 until payload.totalChunks) continue
            return buildChunk(payload, index, countProgress = false)
        }

        while (true) {
            val payload = currentPayload ?: loadNextPayload() ?: return null
            var index = nextChunkIndex
            while (index < payload.totalChunks && payload.skip.get(index)) index++
            if (index >= payload.totalChunks) {
                finishCurrentPayload()
                continue
            }
            nextChunkIndex = index + 1
            return buildChunk(payload, index, countProgress = true)
        }
    }

    private fun buildChunk(
        payload: HostModPayload,
        index: Int,
        countProgress: Boolean,
    ): ModPacket.ModChunkPacket {
        val start = index * ModPacket.CHUNK_SIZE
        val end = if (payload.bytes.isEmpty()) 0 else minOf(start + ModPacket.CHUNK_SIZE, payload.bytes.size)
        val length = (end - start).toLong()
        sentBytesTotal += length
        // 只有当前 payload 的按序首发才计入当前模组进度；NAK 重发与跨 mod 的迟到重传不计
        if (countProgress && payload === currentPayload) {
            currentModProgressBytes += length
        }
        return ModPacket.ModChunkPacket().apply {
            requestId = this@HostModTransferSession.requestId
            name = payload.descriptor.name
            chunkIndex = index
            totalChunks = payload.totalChunks
            if (index == 0) {
                totalSize = payload.descriptor.payloadSize
                sha256 = payload.descriptor.normalizedSha256
            }
            chunkBytes = payload.bytes.copyOfRange(start, end)
        }
    }

    private fun payloadFor(name: String): HostModPayload? =
        currentPayload?.takeIf { it.descriptor.name == name } ?: sentPayloads[name]

    private fun loadNextPayload(): HostModPayload? {
        if (sourceIndex >= sources.size) return null
        val source = sources[sourceIndex]
        require(source.descriptor.matches(source.bytes)) {
            "Prepared bytes do not match descriptor for ${source.descriptor.name}"
        }
        val totalChunks = maxOf(1, (source.bytes.size + ModPacket.CHUNK_SIZE - 1) / ModPacket.CHUNK_SIZE)
        val skip = haveBitmaps[source.descriptor.name] ?: BitSet(0)
        val payload = HostModPayload(source.descriptor, source.bytes, totalChunks, source, skip)
        currentPayload = payload
        nextChunkIndex = 0
        // 断点续传：客户端已持有的块不再发送，但这部分字节应计入当前模组进度
        currentModProgressBytes = skippedBytes(source.bytes.size, totalChunks, skip)
        val skipped = (0 until totalChunks).count { skip.get(it) }
        logInfo("[MODSYNC-HOST] sending mod to $playerName (chunked): ${source.descriptor.name}, size=${source.bytes.size}, chunks=$totalChunks, skippedByResume=$skipped")
        return payload
    }

    /** 位图中已持有分块对应的字节总量（末块可能不足 CHUNK_SIZE，空 payload 为 0）。 */
    private fun skippedBytes(size: Int, totalChunks: Int, skip: BitSet): Long {
        var sum = 0L
        var i = skip.nextSetBit(0)
        while (i >= 0) {
            if (i < totalChunks) {
                val start = i * ModPacket.CHUNK_SIZE
                sum += (minOf(start + ModPacket.CHUNK_SIZE, size) - start).coerceAtLeast(0)
            }
            i = skip.nextSetBit(i + 1)
        }
        return sum
    }

    private fun finishCurrentPayload() {
        val payload = currentPayload
        if (payload != null) {
            logInfo("[MODSYNC-HOST] finished sending mod to $playerName: ${payload.descriptor.name}")
            // 字节保留在 sentPayloads 中直到会话结束，以支持迟到的 NAK 重传；
            // source 统一由 releaseRemaining 释放。
            sentPayloads[payload.descriptor.name] = payload
        }
        sourceIndex++
        currentPayload = null
        nextChunkIndex = 0
    }

    fun releaseRemaining() {
        currentPayload = null
        resendQueue.clear()
        sentPayloads.values.forEach { it.source.release() }
        sentPayloads.clear()
        for (i in sourceIndex until sources.size) {
            sources[i].release()
        }
    }
}

private class HostModPayload(
    val descriptor: NetworkModDescriptor,
    val bytes: ByteArray,
    val totalChunks: Int,
    val source: HostModTransferSource,
    /** 客户端已持有的分块位图（断点续传），对应位为 1 的块跳过不发。 */
    val skip: BitSet,
)
