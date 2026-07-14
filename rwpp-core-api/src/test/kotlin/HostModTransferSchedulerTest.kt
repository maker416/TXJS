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
