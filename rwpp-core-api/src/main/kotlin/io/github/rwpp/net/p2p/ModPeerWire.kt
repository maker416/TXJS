/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.p2p

import io.github.rwpp.net.packets.ModPacket
import io.github.rwpp.net.packets.ModPeerPacket
import java.io.DataInput
import java.io.DataOutput

/**
 * 模组 P2P 直连（TCP）上的线协议编解码。**纯函数 / 纯数据类**，不持有任何 socket 状态，可独立单测。
 *
 * 一个连接只拉一个 mod：
 * 1. 请求方（B）握手：[MAGIC] + token + modCacheKey + 缺失块位图（bit=1 表示需要该块）。
 * 2. 服务方（P）先回一个 int 状态码（[STATUS_OK] / [STATUS_UNKNOWN_MOD] / [STATUS_BAD_TOKEN]）；
 *    仅 [STATUS_OK] 后按请求位图逐块发送 chunk frame（`int 块序号 + int 长度 + 字节`），
 *    全部发完写 [END_OF_CHUNKS]（-1）收尾。
 *
 * 内容安全不依赖本层：接收侧每块仍过 manifest 下发的块级 SHA-256（`ModChunkAssembler`），
 * 坏块/恶意 peer 最多浪费带宽，无法注入错误内容。
 */
object ModPeerWire {
    /** 握手魔数，标识 RWPP 模组 P2P 协议版本 1。 */
    const val MAGIC = "RWPM1"

    /** 服务方接受请求，随后跟 chunk frame 序列。 */
    const val STATUS_OK = 0

    /** 服务方没有该 cacheKey 对应的完整 mod。 */
    const val STATUS_UNKNOWN_MOD = 1

    /** 握手令牌与房间会话令牌不符。 */
    const val STATUS_BAD_TOKEN = 2

    /** chunk frame 序列结束标记（块序号位置的 -1）。 */
    const val END_OF_CHUNKS = -1

    /** 单块最大字节数，与房主分块大小一致。 */
    const val MAX_CHUNK_BYTES = ModPacket.CHUNK_SIZE

    /** 缺失块位图的最大字节数（与 manifest 位图上限一致）。 */
    const val MAX_BITMAP_BYTES = ModPacket.MAX_BITMAP_BYTES

    private val CACHE_KEY_REGEX = Regex("^[0-9a-f]{64}$")

    /**
     * 请求方握手。token 为房主下发的房间级会话令牌；cacheKey 为要拉取的 mod 内容标识；
     * wantBitmap 为缺失块位图（`java.util.BitSet.toByteArray` 格式，bit=1 表示需要该块）。
     */
    data class Handshake(
        val token: String,
        val cacheKey: String,
        val wantBitmap: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Handshake && token == other.token && cacheKey == other.cacheKey &&
                    wantBitmap.contentEquals(other.wantBitmap)

        override fun hashCode(): Int = 31 * (31 * token.hashCode() + cacheKey.hashCode()) + wantBitmap.contentHashCode()
    }

    /** 服务方→请求方的一个 chunk frame，或结束标记。 */
    sealed interface ChunkFrame {
        data class Chunk(val index: Int, val bytes: ByteArray) : ChunkFrame
        data object End : ChunkFrame
    }

    fun encodeHandshake(output: DataOutput, handshake: Handshake) {
        require(handshake.token.length <= ModPeerPacket.MAX_TOKEN_LENGTH) { "peer token too long" }
        require(handshake.cacheKey.matches(CACHE_KEY_REGEX)) { "invalid mod cache key" }
        require(handshake.wantBitmap.size <= MAX_BITMAP_BYTES) { "want bitmap too large" }
        output.writeUTF(MAGIC)
        output.writeUTF(handshake.token)
        output.writeUTF(handshake.cacheKey)
        writeBytesWithSize(output, handshake.wantBitmap)
    }

    fun decodeHandshake(input: DataInput): Handshake {
        val magic = input.readUTF()
        require(magic == MAGIC) { "bad peer protocol magic: $magic" }
        val token = input.readUTF().also { require(it.length <= ModPeerPacket.MAX_TOKEN_LENGTH) { "peer token too long" } }
        val cacheKey = input.readUTF().lowercase().also { require(it.matches(CACHE_KEY_REGEX)) { "invalid mod cache key" } }
        val wantBitmap = readBytesWithSize(input, MAX_BITMAP_BYTES)
        return Handshake(token, cacheKey, wantBitmap)
    }

    fun encodeStatus(output: DataOutput, status: Int) {
        require(status in STATUS_OK..STATUS_BAD_TOKEN) { "invalid peer status" }
        output.writeInt(status)
    }

    fun decodeStatus(input: DataInput): Int =
        input.readInt().also { require(it in STATUS_OK..STATUS_BAD_TOKEN) { "invalid peer status: $it" } }

    fun encodeChunkFrame(output: DataOutput, index: Int, bytes: ByteArray) {
        require(index >= 0) { "negative chunk index" }
        require(bytes.size <= MAX_CHUNK_BYTES) { "chunk too large" }
        output.writeInt(index)
        writeBytesWithSize(output, bytes)
    }

    fun encodeEndOfChunks(output: DataOutput) {
        output.writeInt(END_OF_CHUNKS)
    }

    fun decodeChunkFrame(input: DataInput): ChunkFrame {
        val index = input.readInt()
        if (index == END_OF_CHUNKS) return ChunkFrame.End
        require(index >= 0) { "negative chunk index: $index" }
        return ChunkFrame.Chunk(index, readBytesWithSize(input, MAX_CHUNK_BYTES))
    }

    /** 与 `GameOutputStream.writeBytesWithSize` 相同的线格式（int 长度 + 原始字节）。 */
    fun writeBytesWithSize(output: DataOutput, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    /** 先读长度再做上限校验，然后才分配数组，避免恶意长度前缀撑爆内存。 */
    fun readBytesWithSize(input: DataInput, maxSize: Int): ByteArray {
        val size = input.readInt()
        require(size in 0..maxSize) { "invalid byte block size: $size (max $maxSize)" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return bytes
    }
}
