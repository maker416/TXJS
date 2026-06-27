/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.Client
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
            // 模拟真实客户端：每收一块立即回 ACK，释放房主流控窗口
            scheduler.onAck(secondClient, packet.name, packet.chunkIndex)
        }
        lateinit var firstClient: RecordingClient
        firstClient = RecordingClient("first", events) { packet ->
            // 首块到达时把第二位加入调度（验证轮询公平），并同样回 ACK
            if (packet.chunkIndex == 0) {
                scheduler.submit(
                    secondClient,
                    "second",
                    listOf(HostModTransferSource("same-mod") { fourChunks }),
                )
            }
            scheduler.onAck(firstClient, packet.name, packet.chunkIndex)
        }

        scheduler = HostModTransferScheduler(this, windowSize = 16, chunkDelayMillis = 0)
        scheduler.submit(
            firstClient,
            "first",
            listOf(HostModTransferSource("same-mod") { fourChunks }),
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
        // 该客户端收块后不回 ACK → 房主窗口不应被释放
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
            listOf(HostModTransferSource("big") { ByteArray(ModPacket.CHUNK_SIZE * 50) }),
        )

        // 等到窗口被填满（发满 windowSize 块）
        withTimeout(1000) { while (events.size < windowSize) yield() }
        // 再留足时间，确认不会冒出第 windowSize+1 块（被窗口挡住、且无 ACK 释放）
        delay(80)
        assertEquals(windowSize, events.size, "host must not exceed the in-flight window without ACKs")

        // 释放一个槽：应能继续多发一块
        scheduler.onAck(silent, "big", 0)
        withTimeout(1000) { while (events.size < windowSize + 1) yield() }
        assertTrue(events.size >= windowSize + 1, "a released window slot must allow one more chunk")

        scheduler.cancelAll()
    }

    private class RecordingClient(
        private val id: String,
        private val events: MutableList<String>,
        private val afterSend: (ModPacket.ModChunkPacket) -> Unit = {},
    ) : Client {
        override fun sendPacketToClient(packet: Packet) {
            val chunk = packet as ModPacket.ModChunkPacket
            events.add("$id:${chunk.chunkIndex}")
            afterSend(chunk)
        }
    }
}
