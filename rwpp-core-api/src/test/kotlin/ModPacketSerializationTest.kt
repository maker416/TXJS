/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.io.GameInputStream
import io.github.rwpp.io.GameOutputStream
import io.github.rwpp.net.packets.ModPacket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModPacketSerializationTest {
    private val hash64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun roundTrip(packet: ModPacket): ModPacket {
        val bytes = packet.toBytes()
        val decoded = packet.javaClass.getDeclaredConstructor().newInstance()
        ByteArrayInputStream(bytes).use { input ->
            decoded.readPacket(GameInputStream(DataInputStream(input)))
        }
        return decoded
    }

    private fun gameOutput(block: GameOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            GameOutputStream(DataOutputStream(bytes)).use(block)
            bytes.toByteArray()
        }

    @Test
    fun manifestRequestRoundTrips() {
        val packet = ModPacket.ManifestRequestPacket().apply {
            requestId = 123L
            requiredNames = listOf("alpha", "模组-β")
        }
        val out = roundTrip(packet) as ModPacket.ManifestRequestPacket
        assertEquals(123L, out.requestId)
        assertEquals(listOf("alpha", "模组-β"), out.requiredNames)
    }

    @Test
    fun manifestResponseRoundTripsWithDescriptors() {
        val descriptors = listOf(
            NetworkModDescriptor("mod", 100L, hash64),
            NetworkModDescriptor("other", 0L, hash64),
        )
        val packet = ModPacket.ManifestResponsePacket().apply {
            requestId = 7L
            success = true
            errorMessage = ""
            this.descriptors = descriptors
        }
        val out = roundTrip(packet) as ModPacket.ManifestResponsePacket
        assertEquals(7L, out.requestId)
        assertTrue(out.success)
        assertEquals(descriptors, out.descriptors)
    }

    @Test
    fun requestPacketRoundTripsWithDescriptors() {
        val descriptors = listOf(NetworkModDescriptor("mod", 100L, hash64))
        val packet = ModPacket.RequestPacket().apply {
            requestId = 9L
            requestedDescriptors = descriptors
        }
        val out = roundTrip(packet) as ModPacket.RequestPacket
        assertEquals(9L, out.requestId)
        assertEquals(descriptors, out.requestedDescriptors)
    }

    @Test
    fun requestPacketRoundTripsWithEmptyDescriptors() {
        // 缓存全命中时客户端会发空下载请求，通知房主保持 ready=false 直到 ModReloadFinish。
        val packet = ModPacket.RequestPacket().apply {
            requestId = 11L
            requestedDescriptors = emptyList()
        }
        val out = roundTrip(packet) as ModPacket.RequestPacket
        assertEquals(11L, out.requestId)
        assertEquals(emptyList(), out.requestedDescriptors)
    }

    @Test
    fun chunkAckRoundTripsWithRequestId() {
        val packet = ModPacket.ModChunkAckPacket().apply {
            requestId = 42L
            name = "mod"
            ackChunkIndex = 3
        }
        val out = roundTrip(packet) as ModPacket.ModChunkAckPacket
        assertEquals(42L, out.requestId)
        assertEquals("mod", out.name)
        assertEquals(3, out.ackChunkIndex)
    }

    @Test
    fun chunkPacketRoundTripsAndCarriesDescriptorInFirstChunk() {
        val payload = ByteArray(ModPacket.CHUNK_SIZE) { it.toByte() }
        val descriptor = NetworkModDescriptor.fromBytes("mod", payload)
        val packet = ModPacket.ModChunkPacket().apply {
            requestId = 5L
            name = "mod"
            chunkIndex = 0
            totalChunks = 1
            totalSize = descriptor.payloadSize
            sha256 = descriptor.normalizedSha256
            chunkBytes = payload
        }
        val out = roundTrip(packet) as ModPacket.ModChunkPacket
        assertEquals(5L, out.requestId)
        assertEquals(descriptor.payloadSize, out.totalSize)
        assertEquals(descriptor.normalizedSha256, out.sha256)
        assertTrue(payload.contentEquals(out.chunkBytes))
    }

    @Test
    fun rejectTooManyDescriptors() {
        val tooMany = (0..ModPacket.MAX_DESCRIPTOR_COUNT).joinToString(",") { "x" }
        val bytes = gameOutput {
            writeLong(1L)
            writeInt(ModPacket.MAX_DESCRIPTOR_COUNT + 1)
        }
        val decoded = ModPacket.RequestPacket()
        assertNull(runCatching {
            ByteArrayInputStream(bytes).use { input ->
                decoded.readPacket(GameInputStream(DataInputStream(input)))
            }
        }.getOrNull())
        assertTrue(tooMany.length > 0)
    }

    @Test
    fun rejectInvalidSha256InDescriptor() {
        val bytes = gameOutput {
            writeInt(1)
            writeUTF("mod")
            writeLong(1L)
            writeUTF("nothex")
        }
        val decoded = ModPacket.RequestPacket()
        assertNull(runCatching {
            ByteArrayInputStream(bytes).use { input ->
                decoded.readPacket(GameInputStream(DataInputStream(input)))
            }
        }.getOrNull())
    }
}
