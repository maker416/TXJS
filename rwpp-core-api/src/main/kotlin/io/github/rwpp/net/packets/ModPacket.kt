/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.packets

import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.io.GameInputStream
import io.github.rwpp.io.GameOutputStream
import io.github.rwpp.net.Packet

@Suppress("MemberVisibilityCanBePrivate")
sealed class ModPacket : Packet() {

    class ManifestRequestPacket : ModPacket() {
        var requestId: Long = 0L
        var requiredNames: List<String> = emptyList()

        override val type: Int = MOD_MANIFEST_REQUEST

        override fun readPacket(input: GameInputStream) {
            requestId = input.readLong()
            requiredNames = readStringList(input)
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeLong(requestId)
            writeStringList(output, requiredNames)
        }
    }

    class ManifestResponsePacket : ModPacket() {
        var requestId: Long = 0L
        var success: Boolean = false
        var errorMessage: String = ""
        var descriptors: List<NetworkModDescriptor> = emptyList()
        /** mod 名 → 按 [CHUNK_SIZE] 切分的逐块 SHA-256（小写 hex）。接收端据此做块级校验与多源互传验证。 */
        var chunkHashes: Map<String, List<String>> = emptyMap()

        override val type: Int = MOD_MANIFEST_RESPONSE

        override fun readPacket(input: GameInputStream) {
            requestId = input.readLong()
            success = input.readBoolean()
            errorMessage = input.readUTF().also { require(it.length <= MAX_ERROR_LENGTH) { "manifest error too long" } }
            descriptors = readDescriptorList(input)
            chunkHashes = readChunkHashMap(input, descriptors.map { it.name }.toSet())
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeLong(requestId)
            output.writeBoolean(success)
            output.writeUTF(errorMessage.take(MAX_ERROR_LENGTH))
            writeDescriptorList(output, descriptors)
            writeChunkHashMap(output, chunkHashes)
        }
    }

    class RequestPacket : ModPacket() {
        var requestId: Long = 0L
        var requestedDescriptors: List<NetworkModDescriptor> = emptyList()
        /**
         * mod 名 → 已有分块位图（bit i = 本地已持有第 i 块，来自断点续传缓存）。
         * 房主据此跳过已有块；空 map / 空数组 = 全缺，与旧语义一致。
         */
        var haveBitmaps: Map<String, ByteArray> = emptyMap()

        override val type: Int = MOD_DOWNLOAD_REQUEST

        override fun readPacket(input: GameInputStream) {
            requestId = input.readLong()
            requestedDescriptors = readDescriptorList(input)
            haveBitmaps = readBitmapMap(input, requestedDescriptors.map { it.name }.toSet())
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeLong(requestId)
            writeDescriptorList(output, requestedDescriptors)
            writeBitmapMap(output, haveBitmaps)
        }
    }

    class ModPackPacket : ModPacket() {
        var index: Int = 0
        var name: String = ""
        var modBytes: ByteArray = byteArrayOf()

        override val type: Int = DOWNLOAD_MOD_PACK

        override fun readPacket(input: GameInputStream) {
            index = input.readInt()
            name = input.readUTF()
            modBytes = input.readNextBytes()
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeInt(index)
            output.writeUTF(name)
            output.writeBytesWithSize(modBytes)
        }
    }

    /**
     * mod 分块传输包：房主把单个 mod 切成固定大小（[CHUNK_SIZE]）的多个块依次发送，
     * 客户端按 manifest 中的 descriptor 重组后再做完整性校验。
     */
    class ModChunkPacket : ModPacket() {
        var requestId: Long = 0L
        /** mod 名称，同一次传输内所有块相同 */
        var name: String = ""
        /** 当前块序号，从 0 开始 */
        var chunkIndex: Int = 0
        /** 该 mod 的总块数（首块带） */
        var totalChunks: Int = 0
        /** 该 mod 完整数据的字节数（首块带） */
        var totalSize: Long = 0L
        /** 该 mod 完整数据的 SHA-256 十六进制串（首块带） */
        var sha256: String = ""
        /** 本块数据 */
        var chunkBytes: ByteArray = byteArrayOf()

        override val type: Int = DOWNLOAD_MOD_CHUNK

        override fun readPacket(input: GameInputStream) {
            requestId = input.readLong()
            name = input.readUTF().also { validateName(it) }
            chunkIndex = input.readInt().also { require(it >= 0) { "negative chunk index" } }
            totalChunks = input.readInt().also { require(it >= 0) { "negative total chunks" } }
            totalSize = input.readLong().also { require(it >= 0L) { "negative total size" } }
            sha256 = input.readUTF().lowercase().also { if (it.isNotEmpty()) NetworkModDescriptor(name, totalSize, it) }
            chunkBytes = input.readNextBytes().also { require(it.size <= CHUNK_SIZE) { "chunk too large" } }
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeLong(requestId)
            output.writeUTF(name)
            output.writeInt(chunkIndex)
            output.writeInt(totalChunks)
            output.writeLong(totalSize)
            output.writeUTF(sha256)
            output.writeBytesWithSize(chunkBytes)
        }
    }

    class ModReloadFinishPacket : ModPacket() {
        var requestId: Long = 0L

        override val type: Int = MOD_RELOAD_FINISH

        override fun readPacket(input: GameInputStream) {
            requestId = input.readLong()
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeLong(requestId)
        }
    }

    /**
     * 分块接收确认包：客户端每成功接收并缓冲一个 [ModChunkPacket] 后回发给房主，用于**流量控制**。
     *
     * 语义为「包已到达」（无论内容是否通过块级校验），内容被拒绝走 [ModChunkNakPacket]。
     */
    class ModChunkAckPacket : ModPacket() {
        var requestId: Long = 0L
        /** 被确认的 mod 名 */
        var name: String = ""
        /** 被确认的块序号 */
        var ackChunkIndex: Int = 0

        override val type: Int = MOD_CHUNK_ACK

        override fun readPacket(input: GameInputStream) {
            requestId = input.readLong()
            name = input.readUTF().also { validateName(it) }
            ackChunkIndex = input.readInt().also { require(it >= 0) { "negative ack index" } }
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeLong(requestId)
            output.writeUTF(name)
            output.writeInt(ackChunkIndex)
        }
    }

    /**
     * 分块否定确认包：客户端收到的分块未通过块级 SHA-256 校验时回发给房主，请求重传该块。
     * 与 [ModChunkAckPacket] 成对出现（ACK 释放流量窗口，NAK 请求内容重发）。
     */
    class ModChunkNakPacket : ModPacket() {
        var requestId: Long = 0L
        /** 被拒绝的 mod 名 */
        var name: String = ""
        /** 被拒绝的块序号 */
        var chunkIndex: Int = 0

        override val type: Int = MOD_CHUNK_NAK

        override fun readPacket(input: GameInputStream) {
            requestId = input.readLong()
            name = input.readUTF().also { validateName(it) }
            chunkIndex = input.readInt().also { require(it >= 0) { "negative nak index" } }
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeLong(requestId)
            output.writeUTF(name)
            output.writeInt(chunkIndex)
        }
    }

    companion object {
        const val MOD_DOWNLOAD_REQUEST = 500
        const val DOWNLOAD_MOD_PACK = 510
        const val DOWNLOAD_MOD_CHUNK = 511
        const val MOD_RELOAD_FINISH = 502
        /** 客户端→房主：分块接收确认（流量控制用）。 */
        const val MOD_CHUNK_ACK = 503
        const val MOD_MANIFEST_REQUEST = 504
        const val MOD_MANIFEST_RESPONSE = 505
        /** 客户端→房主：分块内容校验失败，请求重传（与 ACK 成对，ACK 释放窗口、NAK 请求重发）。 */
        const val MOD_CHUNK_NAK = 506

        /** 单个分块的最大字节数：64KB。足够小以避免大包风险，又不至于包数过多拖慢。 */
        const val CHUNK_SIZE = 64 * 1024
        const val MAX_DESCRIPTOR_COUNT = 128
        const val MAX_ERROR_LENGTH = 512
        /** 单个 mod 允许的最大分块数（64KB × 65536 ≈ 4GB），用于限制 manifest 中的块哈希列表长度。 */
        const val MAX_CHUNK_COUNT = 65536
        /** 分块位图的最大字节数（[MAX_CHUNK_COUNT] bit）。 */
        const val MAX_BITMAP_BYTES = MAX_CHUNK_COUNT / 8

        private val CHUNK_HASH_REGEX = Regex("^[0-9a-f]{64}$")

        fun writeDescriptor(output: GameOutputStream, descriptor: NetworkModDescriptor) {
            output.writeUTF(descriptor.name)
            output.writeLong(descriptor.payloadSize)
            output.writeUTF(descriptor.normalizedSha256)
        }

        fun readDescriptor(input: GameInputStream): NetworkModDescriptor {
            val name = input.readUTF().also { validateName(it) }
            val size = input.readLong().also { require(it >= 0L) { "negative payload size" } }
            val sha256 = input.readUTF().lowercase()
            return NetworkModDescriptor(name, size, sha256)
        }

        fun writeDescriptorList(output: GameOutputStream, descriptors: List<NetworkModDescriptor>) {
            require(descriptors.size <= MAX_DESCRIPTOR_COUNT) { "too many descriptors" }
            output.writeInt(descriptors.size)
            descriptors.forEach { writeDescriptor(output, it) }
        }

        fun readDescriptorList(input: GameInputStream): List<NetworkModDescriptor> {
            val count = input.readInt().also { require(it in 0..MAX_DESCRIPTOR_COUNT) { "invalid descriptor count" } }
            return List(count) { readDescriptor(input) }
        }

        fun writeStringList(output: GameOutputStream, values: List<String>) {
            require(values.size <= MAX_DESCRIPTOR_COUNT) { "too many strings" }
            output.writeInt(values.size)
            values.forEach { value ->
                validateName(value)
                output.writeUTF(value)
            }
        }

        fun readStringList(input: GameInputStream): List<String> {
            val count = input.readInt().also { require(it in 0..MAX_DESCRIPTOR_COUNT) { "invalid string count" } }
            return List(count) { input.readUTF().also { value -> validateName(value) } }
        }

        /**
         * 写入 mod 名 → 逐块 SHA-256 列表的映射。
         * 读侧用 descriptor 名单校验条目归属，防止携带无关 mod 的数据。
         */
        fun writeChunkHashMap(output: GameOutputStream, map: Map<String, List<String>>) {
            require(map.size <= MAX_DESCRIPTOR_COUNT) { "too many chunk hash entries" }
            output.writeInt(map.size)
            map.forEach { (name, hashes) ->
                validateName(name)
                require(hashes.size <= MAX_CHUNK_COUNT) { "too many chunk hashes for '$name'" }
                output.writeUTF(name)
                output.writeInt(hashes.size)
                hashes.forEach { hash ->
                    require(hash.matches(CHUNK_HASH_REGEX)) { "invalid chunk sha256: $hash" }
                    output.writeUTF(hash)
                }
            }
        }

        fun readChunkHashMap(input: GameInputStream, allowedNames: Set<String>): Map<String, List<String>> {
            val count = input.readInt().also { require(it in 0..MAX_DESCRIPTOR_COUNT) { "invalid chunk hash entry count" } }
            val result = LinkedHashMap<String, List<String>>(count)
            repeat(count) {
                val name = input.readUTF().also { value -> validateName(value) }
                require(allowedNames.isEmpty() || name in allowedNames) { "chunk hashes for unknown mod: $name" }
                val hashCount = input.readInt().also { require(it in 0..MAX_CHUNK_COUNT) { "invalid chunk hash count" } }
                val hashes = List(hashCount) {
                    input.readUTF().lowercase().also { hash -> require(hash.matches(CHUNK_HASH_REGEX)) { "invalid chunk sha256" } }
                }
                result[name] = hashes
            }
            return result
        }

        /** 写入 mod 名 → 分块位图（[java.util.BitSet.toByteArray] 格式）的映射。 */
        fun writeBitmapMap(output: GameOutputStream, map: Map<String, ByteArray>) {
            require(map.size <= MAX_DESCRIPTOR_COUNT) { "too many bitmap entries" }
            output.writeInt(map.size)
            map.forEach { (name, bitmap) ->
                validateName(name)
                require(bitmap.size <= MAX_BITMAP_BYTES) { "bitmap too large for '$name'" }
                output.writeUTF(name)
                output.writeBytesWithSize(bitmap)
            }
        }

        fun readBitmapMap(input: GameInputStream, allowedNames: Set<String>): Map<String, ByteArray> {
            val count = input.readInt().also { require(it in 0..MAX_DESCRIPTOR_COUNT) { "invalid bitmap entry count" } }
            val result = LinkedHashMap<String, ByteArray>(count)
            repeat(count) {
                val name = input.readUTF().also { value -> validateName(value) }
                require(allowedNames.isEmpty() || name in allowedNames) { "bitmap for unknown mod: $name" }
                val bitmap = input.readNextBytes().also { require(it.size <= MAX_BITMAP_BYTES) { "bitmap too large" } }
                result[name] = bitmap
            }
            return result
        }

        private fun validateName(name: String) {
            require(name.isNotBlank()) { "blank mod name" }
            require(name.length <= NetworkModDescriptor.MAX_NAME_LENGTH) { "mod name too long" }
        }
    }
}
