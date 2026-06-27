/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.packets

import io.github.rwpp.io.GameInputStream
import io.github.rwpp.io.GameOutputStream
import io.github.rwpp.net.Packet

@Suppress("MemberVisibilityCanBePrivate")
sealed class ModPacket : Packet() {

    class RequestPacket : ModPacket() {

        var mods: String = ""

        override val type: Int = MOD_DOWNLOAD_REQUEST

        override fun readPacket(input: GameInputStream) {
            mods = input.readUTF()
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeUTF(mods)
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
     * 客户端按 [name] 重组后再做完整性校验。避免单个 48MB 大包直接交给游戏引擎。
     *
     * 约定：仅首块（[chunkIndex] == 0）携带 [totalSize]、[sha256]；后续块这两字段为 0/空，
     * 重组与校验以首块的元数据为准。
     */
    class ModChunkPacket : ModPacket() {
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
            name = input.readUTF()
            chunkIndex = input.readInt()
            totalChunks = input.readInt()
            totalSize = input.readLong()
            sha256 = input.readUTF()
            chunkBytes = input.readNextBytes()
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeUTF(name)
            output.writeInt(chunkIndex)
            output.writeInt(totalChunks)
            output.writeLong(totalSize)
            output.writeUTF(sha256)
            output.writeBytesWithSize(chunkBytes)
        }
    }

    class ModReloadFinishPacket : ModPacket() {
       override val type: Int = MOD_RELOAD_FINISH

        override fun readPacket(input: GameInputStream) {
            input.readInt()
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeInt(1)
        }
    }

    /**
     * 分块接收确认包：客户端每成功接收并缓冲一个 [ModChunkPacket] 后回发给房主，用于**流量控制**。
     * 房主据此释放该客户端的发送窗口（见 [io.github.rwpp.net.HostModTransferScheduler]），
     * 使房主发送速率自动适配客户端真实排水速度，避免向游戏连接的无界发送队列灌入海量在途分块、
     * 拖垮房主游戏线程（进而饿死其它加入者的握手）。
     *
     * 字段仅用于诊断；窗口释放按「每收到一个 ACK 释放一个槽」的 1:1 语义，依赖底层 TCP 可靠有序交付。
     */
    class ModChunkAckPacket : ModPacket() {
        /** 被确认的 mod 名 */
        var name: String = ""
        /** 被确认的块序号 */
        var ackChunkIndex: Int = 0

        override val type: Int = MOD_CHUNK_ACK

        override fun readPacket(input: GameInputStream) {
            name = input.readUTF()
            ackChunkIndex = input.readInt()
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeUTF(name)
            output.writeInt(ackChunkIndex)
        }
    }

    companion object {
        const val MOD_DOWNLOAD_REQUEST = 500
        const val DOWNLOAD_MOD_PACK = 510
        const val DOWNLOAD_MOD_CHUNK = 511
        const val MOD_RELOAD_FINISH = 502
        /** 客户端→房主：分块接收确认（流量控制用）。 */
        const val MOD_CHUNK_ACK = 503

        /** 单个分块的最大字节数：64KB。足够小以避免大包风险，又不至于包数过多拖慢。 */
        const val CHUNK_SIZE = 64 * 1024
    }
}