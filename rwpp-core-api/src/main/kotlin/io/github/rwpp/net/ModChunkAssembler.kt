/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.io.HashUtils
import io.github.rwpp.net.packets.ModPacket
import java.util.BitSet

/**
 * MOD 分块稀疏重组器。
 *
 * 取代旧的「严格按序追加 + 乱序即断连」接收逻辑：
 * - 允许分块**乱序到达**，按 [io.github.rwpp.net.packets.ModPacket.CHUNK_SIZE] 的序号稀疏缓冲；
 * - 每个分块先用 manifest 下发的**块级 SHA-256** 独立校验，坏块拒绝并计数（供调用方发 NAK 重传），
 *   不再因为单个坏块/乱序块让整轮传输作废；
 * - 校验过的块可通过 [offer] 的返回结果由调用方落盘（断点续传），下次进房用 [offer] 重新播种即可。
 *
 * 线程安全：所有公共方法内部持锁。
 */
class ModChunkAssembler(
    val descriptor: NetworkModDescriptor,
    /** manifest 下发的逐块 SHA-256（小写 hex）。空列表 = 不做块级校验（仅整包校验兜底）。 */
    private val chunkHashes: List<String> = emptyList(),
    /** 单块校验失败允许的最大重传次数，超过后调用方应中止传输。 */
    private val maxRetriesPerChunk: Int = DEFAULT_MAX_RETRIES_PER_CHUNK,
) {
    val totalChunks: Int = maxOf(
        1,
        ((descriptor.payloadSize + ModPacket.CHUNK_SIZE - 1) / ModPacket.CHUNK_SIZE).toInt()
    )

    init {
        require(chunkHashes.isEmpty() || chunkHashes.size == totalChunks) {
            "chunk hash count (${chunkHashes.size}) does not match total chunks ($totalChunks) for ${descriptor.name}"
        }
    }

    private val lock = Any()
    private val chunks = arrayOfNulls<ByteArray>(totalChunks)
    private val retries = IntArray(totalChunks)
    private var received = 0
    private var receivedBytesTotal = 0L

    /** 已通过校验的块数。 */
    val receivedCount: Int get() = synchronized(lock) { received }

    /** 已通过校验的累计字节数（进度展示用）。 */
    val receivedBytes: Long get() = synchronized(lock) { receivedBytesTotal }

    /** 是否已收齐全部分块。 */
    val isComplete: Boolean get() = receivedCount >= totalChunks

    fun hasChunk(chunkIndex: Int): Boolean = synchronized(lock) {
        chunkIndex in 0 until totalChunks && chunks[chunkIndex] != null
    }

    /** 已持有分块的位图副本（bit i = 已有第 i 块），用于断点续传请求。 */
    fun bitmap(): BitSet = synchronized(lock) {
        val bits = BitSet(totalChunks)
        for (i in 0 until totalChunks) {
            if (chunks[i] != null) bits.set(i)
        }
        bits
    }

    sealed interface OfferResult {
        /** 新块通过校验并已缓冲。 */
        data object Accepted : OfferResult

        /** 该块此前已持有（重发），直接忽略。 */
        data object Duplicate : OfferResult

        /** 块级哈希不匹配，块被丢弃。[retriesLeft] 为 0 表示重传次数已耗尽。 */
        data class Corrupted(val chunkIndex: Int, val retriesLeft: Int) : OfferResult

        /** 结构性错误（序号越界/块大小不符/manifest 缺块哈希），调用方应视为致命错误中止传输。 */
        data class Invalid(val reason: String) : OfferResult
    }

    /**
     * 投递一个分块。序号允许乱序；内容会先过块级 SHA-256（[chunkHashes] 非空时）。
     */
    fun offer(chunkIndex: Int, bytes: ByteArray): OfferResult = synchronized(lock) {
        if (chunkIndex !in 0 until totalChunks) {
            return OfferResult.Invalid("chunk index out of range: $chunkIndex (total $totalChunks)")
        }
        val expectedSize = expectedChunkSize(chunkIndex)
        if (bytes.size != expectedSize) {
            return OfferResult.Invalid("chunk $chunkIndex size mismatch: expected $expectedSize, got ${bytes.size}")
        }
        if (chunks[chunkIndex] != null) return OfferResult.Duplicate
        if (chunkHashes.isNotEmpty()) {
            val expected = chunkHashes[chunkIndex]
            if (!HashUtils.sha256(bytes).equals(expected, ignoreCase = true)) {
                retries[chunkIndex]++
                return OfferResult.Corrupted(chunkIndex, (maxRetriesPerChunk - retries[chunkIndex]).coerceAtLeast(0))
            }
        }
        chunks[chunkIndex] = bytes
        received++
        receivedBytesTotal += bytes.size
        OfferResult.Accepted
    }

    /** 第 [chunkIndex] 块应有的字节数（最后一块可能不足 [ModPacket.CHUNK_SIZE]）。 */
    fun expectedChunkSize(chunkIndex: Int): Int {
        val start = chunkIndex.toLong() * ModPacket.CHUNK_SIZE
        return minOf(ModPacket.CHUNK_SIZE.toLong(), descriptor.payloadSize - start).toInt()
    }

    /**
     * 收齐后按序拼接完整 payload。调用方应再做一次整包 SHA-256 复核（双保险）。
     */
    fun assemble(): ByteArray = synchronized(lock) {
        check(isComplete) { "cannot assemble incomplete mod: ${descriptor.name} ($received/$totalChunks)" }
        require(descriptor.payloadSize <= Int.MAX_VALUE) { "payload too large to assemble: ${descriptor.payloadSize}" }
        val out = ByteArray(descriptor.payloadSize.toInt())
        var pos = 0
        for (i in 0 until totalChunks) {
            val chunk = chunks[i] ?: throw IllegalStateException("missing chunk $i for ${descriptor.name}")
            chunk.copyInto(out, pos)
            pos += chunk.size
        }
        out
    }

    companion object {
        const val DEFAULT_MAX_RETRIES_PER_CHUNK = 3
    }
}
