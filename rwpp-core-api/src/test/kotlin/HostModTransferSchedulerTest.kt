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
