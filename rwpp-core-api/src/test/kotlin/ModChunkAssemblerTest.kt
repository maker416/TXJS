/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.io.HashUtils
import io.github.rwpp.net.ModChunkAssembler
import io.github.rwpp.net.packets.ModPacket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModChunkAssemblerTest {
    private val payload = ByteArray(ModPacket.CHUNK_SIZE * 3 + 123) { (it % 251).toByte() }

    private fun chunkOf(bytes: ByteArray, index: Int): ByteArray {
        val start = index * ModPacket.CHUNK_SIZE
        return bytes.copyOfRange(start, minOf(start + ModPacket.CHUNK_SIZE, bytes.size))
    }

    private fun assemblerFor(
        bytes: ByteArray,
        maxRetries: Int = ModChunkAssembler.DEFAULT_MAX_RETRIES_PER_CHUNK,
    ): ModChunkAssembler {
        val descriptor = NetworkModDescriptor.fromBytes("mod", bytes)
        return ModChunkAssembler(descriptor, HashUtils.sha256Chunks(bytes, ModPacket.CHUNK_SIZE), maxRetries)
    }

    @Test
    fun inOrderChunksAssembleToOriginalPayload() {
        val assembler = assemblerFor(payload)
        assertEquals(4, assembler.totalChunks)
        repeat(4) { index ->
            assertIs<ModChunkAssembler.OfferResult.Accepted>(assembler.offer(index, chunkOf(payload, index)))
        }
        assertTrue(assembler.isComplete)
        assertEquals(payload.size.toLong(), assembler.receivedBytes)
        assertContentEquals(payload, assembler.assemble())
    }

    @Test
    fun outOfOrderChunksAssembleToOriginalPayload() {
        val assembler = assemblerFor(payload)
        listOf(2, 0, 3, 1).forEach { index ->
            assertIs<ModChunkAssembler.OfferResult.Accepted>(assembler.offer(index, chunkOf(payload, index)))
        }
        assertTrue(assembler.isComplete)
        assertContentEquals(payload, assembler.assemble())
    }

    @Test
    fun duplicateChunkIsIgnored() {
        val assembler = assemblerFor(payload)
        assertIs<ModChunkAssembler.OfferResult.Accepted>(assembler.offer(0, chunkOf(payload, 0)))
        assertIs<ModChunkAssembler.OfferResult.Duplicate>(assembler.offer(0, chunkOf(payload, 0)))
        assertEquals(1, assembler.receivedCount)
    }

    @Test
    fun corruptedChunkIsRejectedAndRetriesExhaust() {
        val assembler = assemblerFor(payload, maxRetries = 3)
        val bad = chunkOf(payload, 0).also { it[0] = (it[0] + 1).toByte() }
        val r1 = assembler.offer(0, bad)
        val r2 = assembler.offer(0, bad)
        val r3 = assembler.offer(0, bad)
        assertEquals(2, (r1 as ModChunkAssembler.OfferResult.Corrupted).retriesLeft)
        assertEquals(1, (r2 as ModChunkAssembler.OfferResult.Corrupted).retriesLeft)
        assertEquals(0, (r3 as ModChunkAssembler.OfferResult.Corrupted).retriesLeft)
        assertEquals(0, assembler.receivedCount)
        assertFalse(assembler.isComplete)
        // 坏块不占位：之后收到正确块仍可接受
        assertIs<ModChunkAssembler.OfferResult.Accepted>(assembler.offer(0, chunkOf(payload, 0)))
    }

    @Test
    fun outOfRangeIndexIsInvalid() {
        val assembler = assemblerFor(payload)
        assertIs<ModChunkAssembler.OfferResult.Invalid>(assembler.offer(-1, chunkOf(payload, 0)))
        assertIs<ModChunkAssembler.OfferResult.Invalid>(assembler.offer(4, chunkOf(payload, 0)))
    }

    @Test
    fun wrongSizedChunkIsInvalid() {
        val assembler = assemblerFor(payload)
        assertIs<ModChunkAssembler.OfferResult.Invalid>(assembler.offer(0, ByteArray(10)))
    }

    @Test
    fun bitmapAndReceivedBytesTrackProgress() {
        val assembler = assemblerFor(payload)
        assembler.offer(0, chunkOf(payload, 0))
        assembler.offer(2, chunkOf(payload, 2))
        val bitmap = assembler.bitmap()
        assertTrue(bitmap.get(0))
        assertFalse(bitmap.get(1))
        assertTrue(bitmap.get(2))
        assertEquals(2L * ModPacket.CHUNK_SIZE, assembler.receivedBytes)
    }

    @Test
    fun assembleRequiresComplete() {
        val assembler = assemblerFor(payload)
        assembler.offer(0, chunkOf(payload, 0))
        assertFailsWith<IllegalStateException> { assembler.assemble() }
    }

    @Test
    fun emptyPayloadAssemblesToEmpty() {
        val assembler = assemblerFor(ByteArray(0))
        assertEquals(1, assembler.totalChunks)
        assertIs<ModChunkAssembler.OfferResult.Accepted>(assembler.offer(0, ByteArray(0)))
        assertTrue(assembler.isComplete)
        assertEquals(0, assembler.assemble().size)
    }

    @Test
    fun chunkHashListMustMatchChunkCount() {
        val descriptor = NetworkModDescriptor.fromBytes("mod", payload)
        assertFailsWith<IllegalArgumentException> {
            ModChunkAssembler(descriptor, listOf(HashUtils.sha256(payload)))
        }
    }
}
