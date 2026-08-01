/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.p2p.ModPeerWire
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModPeerWireTest {
    private val hash64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun encode(block: (DataOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use(block) }.toByteArray()

    private fun inputOf(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))

    @Test
    fun handshakeRoundTrips() {
        val handshake = ModPeerWire.Handshake(
            token = "550e8400-e29b-41d4-a716-446655440000",
            cacheKey = hash64,
            wantBitmap = byteArrayOf(0b101, 0b11),
        )
        val bytes = encode { ModPeerWire.encodeHandshake(it, handshake) }
        val decoded = ModPeerWire.decodeHandshake(inputOf(bytes))
        assertEquals(handshake, decoded)
        assertTrue(handshake.wantBitmap.contentEquals(decoded.wantBitmap))
    }

    @Test
    fun handshakeRejectsBadMagic() {
        val bytes = encode { out ->
            out.writeUTF("XXXXX")
            out.writeUTF("token")
            out.writeUTF(hash64)
            ModPeerWire.writeBytesWithSize(out, byteArrayOf(1))
        }
        assertFails { ModPeerWire.decodeHandshake(inputOf(bytes)) }
    }

    @Test
    fun handshakeRejectsBadCacheKey() {
        val bytes = encode { out ->
            out.writeUTF(ModPeerWire.MAGIC)
            out.writeUTF("token")
            out.writeUTF("not-a-64-hex-key")
            ModPeerWire.writeBytesWithSize(out, byteArrayOf(1))
        }
        assertFails { ModPeerWire.decodeHandshake(inputOf(bytes)) }
    }

    @Test
    fun handshakeRejectsOversizeToken() {
        val bytes = encode { out ->
            out.writeUTF(ModPeerWire.MAGIC)
            out.writeUTF("t".repeat(129))
            out.writeUTF(hash64)
            ModPeerWire.writeBytesWithSize(out, byteArrayOf(1))
        }
        assertFails { ModPeerWire.decodeHandshake(inputOf(bytes)) }
    }

    @Test
    fun handshakeRejectsOversizeBitmap() {
        val bytes = encode { out ->
            out.writeUTF(ModPeerWire.MAGIC)
            out.writeUTF("token")
            out.writeUTF(hash64)
            out.writeInt(ModPeerWire.MAX_BITMAP_BYTES + 1) // 先写超大长度，后面不跟字节也必须拒绝
        }
        assertFails { ModPeerWire.decodeHandshake(inputOf(bytes)) }
    }

    @Test
    fun statusRoundTrips() {
        listOf(ModPeerWire.STATUS_OK, ModPeerWire.STATUS_UNKNOWN_MOD, ModPeerWire.STATUS_BAD_TOKEN)
            .forEach { status ->
                val bytes = encode { ModPeerWire.encodeStatus(it, status) }
                assertEquals(status, ModPeerWire.decodeStatus(inputOf(bytes)))
            }
    }

    @Test
    fun statusRejectsInvalidValue() {
        val bytes = encode { it.writeInt(99) }
        assertFails { ModPeerWire.decodeStatus(inputOf(bytes)) }
    }

    @Test
    fun chunkFrameRoundTrips() {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val bytes = encode { ModPeerWire.encodeChunkFrame(it, 7, payload) }
        val frame = ModPeerWire.decodeChunkFrame(inputOf(bytes))
        assertIs<ModPeerWire.ChunkFrame.Chunk>(frame)
        assertEquals(7, frame.index)
        assertTrue(payload.contentEquals(frame.bytes))
    }

    @Test
    fun endOfChunksRoundTrips() {
        val bytes = encode { ModPeerWire.encodeEndOfChunks(it) }
        assertEquals(ModPeerWire.ChunkFrame.End, ModPeerWire.decodeChunkFrame(inputOf(bytes)))
    }

    @Test
    fun chunkFrameRejectsOversizeChunk() {
        val bytes = encode { out ->
            out.writeInt(3)
            out.writeInt(ModPeerWire.MAX_CHUNK_BYTES + 1) // 恶意长度前缀：先拒绝再分配
        }
        assertFails { ModPeerWire.decodeChunkFrame(inputOf(bytes)) }
    }

    @Test
    fun chunkFrameRejectsNegativeIndex() {
        val bytes = encode { out ->
            out.writeInt(-2)
            ModPeerWire.writeBytesWithSize(out, byteArrayOf(1))
        }
        assertFails { ModPeerWire.decodeChunkFrame(inputOf(bytes)) }
    }

    @Test
    fun fullSessionRoundTrips() {
        // 模拟一次完整会话：握手 → ok → 两个块 → 结束
        val chunk0 = byteArrayOf(1, 2, 3)
        val chunk1 = byteArrayOf(4, 5)
        val bytes = encode { out ->
            ModPeerWire.encodeHandshake(out, ModPeerWire.Handshake("tok", hash64, byteArrayOf(0b11)))
            ModPeerWire.encodeStatus(out, ModPeerWire.STATUS_OK)
            ModPeerWire.encodeChunkFrame(out, 0, chunk0)
            ModPeerWire.encodeChunkFrame(out, 1, chunk1)
            ModPeerWire.encodeEndOfChunks(out)
        }
        val input = inputOf(bytes)
        val handshake = ModPeerWire.decodeHandshake(input)
        assertEquals("tok", handshake.token)
        assertEquals(ModPeerWire.STATUS_OK, ModPeerWire.decodeStatus(input))
        val f0 = ModPeerWire.decodeChunkFrame(input) as ModPeerWire.ChunkFrame.Chunk
        assertEquals(0, f0.index)
        assertTrue(chunk0.contentEquals(f0.bytes))
        val f1 = ModPeerWire.decodeChunkFrame(input) as ModPeerWire.ChunkFrame.Chunk
        assertEquals(1, f1.index)
        assertTrue(chunk1.contentEquals(f1.bytes))
        assertEquals(ModPeerWire.ChunkFrame.End, ModPeerWire.decodeChunkFrame(input))
        // 流应恰好读完，无残留字节
        assertNull(runCatching { input.readByte() }.getOrNull())
    }
}
