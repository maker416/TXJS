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
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 房主侧 MOD 分块传输调度器。
 *
 * **核心设计（两件事）：**
 * 1. **轮询公平**：所有加入者共享一个轮询队列，新加入者会在老加入者传完之前就开始收块。
 * 2. **流量控制窗口**：每个 client 同时「在途（已发送但尚未被 ACK）」的分块数有上限 [windowSize]。
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

    fun submit(client: Client, playerName: String, requestId: Long, mods: List<HostModTransferSource>) {
        synchronized(lock) {
            sessions.remove(client)?.releaseRemaining()
            sessions[client] = HostModTransferSession(client, playerName, requestId, mods, logInfo)
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
    val requestId: Long,
    sources: List<HostModTransferSource>,
    private val logInfo: (String) -> Unit,
) {
    private val sources = sources.toMutableList()
    private var sourceIndex = 0
    private var currentPayload: HostModPayload? = null
    private var offset = 0
    private var chunkIndex = 0
    /** 已发送但尚未被客户端 ACK 的分块数（流量控制窗口占用）。 */
    var inFlight: Int = 0

    fun toSnapshot(): HostTransferSnapshot {
        val payload = currentPayload
        val source = if (sourceIndex < sources.size) sources[sourceIndex] else null
        val modName = payload?.descriptor?.name ?: source?.descriptor?.name ?: ""
        val total = payload?.bytes?.size ?: source?.bytes?.size ?: 0
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
            requestId = this@HostModTransferSession.requestId
            name = payload.descriptor.name
            chunkIndex = this@HostModTransferSession.chunkIndex
            totalChunks = payload.totalChunks
            if (chunkIndex == 0) {
                totalSize = payload.descriptor.payloadSize
                sha256 = payload.descriptor.normalizedSha256
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
        require(source.descriptor.matches(source.bytes)) {
            "Prepared bytes do not match descriptor for ${source.descriptor.name}"
        }
        val totalChunks = maxOf(1, (source.bytes.size + ModPacket.CHUNK_SIZE - 1) / ModPacket.CHUNK_SIZE)
        val payload = HostModPayload(source.descriptor, source.bytes, totalChunks, source)
        currentPayload = payload
        offset = 0
        chunkIndex = 0
        logInfo("[MODSYNC-HOST] sending mod to $playerName (chunked): ${source.descriptor.name}, size=${source.bytes.size}, chunks=$totalChunks")
        return payload
    }

    private fun finishCurrentPayload(sentChunks: Int) {
        val payload = currentPayload
        if (payload != null) {
            logInfo("[MODSYNC-HOST] finished sending mod to $playerName: ${payload.descriptor.name}, sentChunks=$sentChunks")
            payload.source.release()
        }
        sourceIndex++
        currentPayload = null
        offset = 0
        chunkIndex = 0
    }

    fun releaseRemaining() {
        currentPayload?.source?.release()
        currentPayload = null
        for (i in sourceIndex until sources.size) {
            sources[i].release()
        }
    }
}

private data class HostModPayload(
    val descriptor: NetworkModDescriptor,
    val bytes: ByteArray,
    val totalChunks: Int,
    val source: HostModTransferSource,
)
