/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.net.HostModTransferScheduler
import io.github.rwpp.net.HostModTransferSource
import io.github.rwpp.net.Packet
import io.github.rwpp.net.packets.ModPacket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostModTransferSchedulerTest {
    @Test
    fun newlySubmittedClientStartsBeforeExistingClientCompletes() = runBlocking {
        lateinit var scheduler: HostModTransferScheduler
        lateinit var secondClient: RecordingClient
        val events = mutableListOf<String>()
        val fourChunks = ByteArray(ModPacket.CHUNK_SIZE * 3 + 1) { it.toByte() }

        secondClient = RecordingClient("second", events) { packet ->
            scheduler.onAck(secondClient, packet.requestId, packet.name, packet.chunkIndex)
        }
        lateinit var firstClient: RecordingClient
        firstClient = RecordingClient("first", events) { packet ->
            if (packet.chunkIndex == 0) {
                scheduler.submit(
                    secondClient,
                    "second",
                    2L,
                    listOf(source("same-mod", fourChunks)),
                )
            }
            scheduler.onAck(firstClient, packet.requestId, packet.name, packet.chunkIndex)
        }

        scheduler = HostModTransferScheduler(this, windowSize = 16, chunkDelayMillis = 0)
        scheduler.submit(
            firstClient,
            "first",
            1L,
            listOf(source("same-mod", fourChunks)),
        )

        withTimeout(1000) {
            while (scheduler.activeClientCount() > 0) {
                yield()
            }
        }

        assertTrue(events.indexOf("second:0") in 1 until events.indexOf("first:3"))
    }

    @Test
    fun unackedClientIsBlockedAtWindowUntilAckReleasesIt() = runBlocking {
        val events = mutableListOf<String>()
        val windowSize = 3
        val silent = RecordingClient("silent", events)
        val scheduler = HostModTransferScheduler(
            this,
            windowSize = windowSize,
            chunkDelayMillis = 0,
            pollWhenBlockedMillis = 1,
        )
        scheduler.submit(
            silent,
            "silent",
            1L,
            listOf(source("big", ByteArray(ModPacket.CHUNK_SIZE * 50))),
        )

        withTimeout(1000) { while (events.size < windowSize) yield() }
        delay(80)
        assertEquals(windowSize, events.size, "host must not exceed the in-flight window without ACKs")

        scheduler.onAck(silent, 1L, "big", 0)
        withTimeout(1000) { while (events.size < windowSize + 1) yield() }
        assertTrue(events.size >= windowSize + 1, "a released window slot must allow one more chunk")

        scheduler.cancelAll()
    }

    @Test
    fun skipsChunksClientAlreadyHas() = runBlocking {
        val events = mutableListOf<String>()
        lateinit var scheduler: HostModTransferScheduler
        lateinit var client: RecordingClient
        client = RecordingClient("c", events) { packet ->
            scheduler.onAck(client, packet.requestId, packet.name, packet.chunkIndex)
        }
        scheduler = HostModTransferScheduler(this, windowSize = 16, chunkDelayMillis = 0)
        val fourChunks = ByteArray(ModPacket.CHUNK_SIZE * 4)
        // 客户端断点续传：已持有第 0、1 块，房主只应补发 2、3
        scheduler.submit(
            client,
            "c",
            1L,
            listOf(source("mod", fourChunks)),
            mapOf("mod" to java.util.BitSet().apply { set(0); set(1) }),
        )

        withTimeout(1000) {
            while (scheduler.activeClientCount() > 0) {
                yield()
            }
        }

        assertEquals(listOf("c:2", "c:3"), events)
    }

    @Test
    fun nakResendIsSentBeforeRemainingSequence() = runBlocking {
        val events = mutableListOf<String>()
        val client = RecordingClient("c", events)
        val scheduler = HostModTransferScheduler(
            this,
            windowSize = 2,
            chunkDelayMillis = 0,
            pollWhenBlockedMillis = 1,
        )
        scheduler.submit(
            client,
            "c",
            1L,
            listOf(source("mod", ByteArray(ModPacket.CHUNK_SIZE * 3))),
        )

        withTimeout(1000) { while (events.size < 2) yield() }
        assertEquals(listOf("c:0", "c:1"), events)

        // 客户端报告 0 号块损坏；ACK 释放窗口后，重传的 0 号块应优先于 2 号块发出
        scheduler.onNak(client, 1L, "mod", 0)
        scheduler.onAck(client, 1L, "mod", 0)
        withTimeout(1000) { while (events.size < 3) yield() }
        assertEquals(listOf("c:0", "c:1", "c:0"), events)

        scheduler.onAck(client, 1L, "mod", 0)
        withTimeout(1000) { while (events.size < 4) yield() }
        assertEquals(listOf("c:0", "c:1", "c:0", "c:2"), events)

        scheduler.cancelAll()
    }

    @Test
    fun snapshotReportsCurrentModProgress() = runBlocking {
        val events = mutableListOf<String>()
        val silent = RecordingClient("silent", events)
        val scheduler = HostModTransferScheduler(
            this,
            windowSize = 2,
            chunkDelayMillis = 0,
            pollWhenBlockedMillis = 1,
        )
        scheduler.submit(
            silent,
            "silent",
            1L,
            listOf(source("mod", ByteArray(ModPacket.CHUNK_SIZE * 4))),
        )

        // 窗口打满后阻塞：4 块中已按序发出 2 块
        withTimeout(1000) { while (events.size < 2) yield() }
        val snap = scheduler.snapshot().single()
        assertEquals("mod", snap.currentModName)
        assertEquals(0, snap.modIndex)
        assertEquals(1, snap.modCount)
        assertEquals(4L * ModPacket.CHUNK_SIZE, snap.totalBytes)
        assertEquals(2L * ModPacket.CHUNK_SIZE, snap.currentModProgressBytes)
        assertEquals(2L * ModPacket.CHUNK_SIZE, snap.sentBytes)

        scheduler.cancelAll()
    }

    @Test
    fun snapshotCountsResumedChunksAsProgress() = runBlocking {
        val events = mutableListOf<String>()
        val silent = RecordingClient("silent", events)
        val scheduler = HostModTransferScheduler(
            this,
            windowSize = 1,
            chunkDelayMillis = 0,
            pollWhenBlockedMillis = 1,
        )
        // 客户端已持有第 0、1 块：免发字节计入进度；窗口 1 阻塞在第 2 块发出后
        scheduler.submit(
            silent,
            "silent",
            1L,
            listOf(source("mod", ByteArray(ModPacket.CHUNK_SIZE * 4))),
            mapOf("mod" to java.util.BitSet().apply { set(0); set(1) }),
        )

        withTimeout(1000) { while (events.size < 1) yield() }
        val snap = scheduler.snapshot().single()
        assertEquals(4L * ModPacket.CHUNK_SIZE, snap.totalBytes)
        assertEquals(3L * ModPacket.CHUNK_SIZE, snap.currentModProgressBytes)

        scheduler.cancelAll()
    }

    @Test
    fun snapshotProgressResetsPerModWhileSentBytesStaysCumulative() = runBlocking {
        val events = mutableListOf<String>()
        lateinit var scheduler: HostModTransferScheduler
        lateinit var client: RecordingClient
        // 只 ACK 第一个 mod 的块；第二个 mod 的块全部不 ACK，让传输停在第二个 mod 上
        client = RecordingClient("c", events) { packet ->
            if (packet.name == "a") {
                scheduler.onAck(client, packet.requestId, packet.name, packet.chunkIndex)
            }
        }
        scheduler = HostModTransferScheduler(
            this,
            windowSize = 32,
            chunkDelayMillis = 0,
            pollWhenBlockedMillis = 1,
        )
        scheduler.submit(
            client,
            "c",
            1L,
            listOf(
                source("a", ByteArray(ModPacket.CHUNK_SIZE * 4)),
                source("b", ByteArray(ModPacket.CHUNK_SIZE * 40)),
            ),
        )

        // a 的 4 块发完 + b 的 32 块打满窗口：传输停留在 b 的中段
        withTimeout(1000) { while (events.size < 36) yield() }
        val snap = scheduler.snapshot().single()
        assertEquals("b", snap.currentModName)
        assertEquals(1, snap.modIndex)
        assertEquals(2, snap.modCount)
        assertEquals(40L * ModPacket.CHUNK_SIZE, snap.totalBytes)
        assertEquals(32L * ModPacket.CHUNK_SIZE, snap.currentModProgressBytes)
        // sentBytes 是整个会话的累计口径（36 块），与当前模组进度口径区分开
        assertEquals(36L * ModPacket.CHUNK_SIZE, snap.sentBytes)

        scheduler.cancelAll()
    }

    private fun source(name: String, bytes: ByteArray): HostModTransferSource =
        HostModTransferSource(NetworkModDescriptor.fromBytes(name, bytes), bytes)

    private class RecordingClient(
        private val id: String,
        private val events: MutableList<String>,
        private val afterSend: (ModPacket.ModChunkPacket) -> Unit = {},
    ) : io.github.rwpp.net.Client {
        override fun sendPacketToClient(packet: Packet) {
            val chunk = packet as ModPacket.ModChunkPacket
            events.add("$id:${chunk.chunkIndex}")
            afterSend(chunk)
        }
    }
}
