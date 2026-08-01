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

/**
 * 模组 P2P 网状互传的**信令包**（与 [ModPacket] 的房主星型分发并行）。
 *
 * 拓扑：进房后客户端向房主 [AnnouncePacket] 自报 P2P 监听端口与 LAN 地址；
 * 房主在下发 manifest 的同一条连接上紧接着回 [PeerListPacket]（房间级会话令牌 + 已知 peer 表）；
 * 客户端收齐某 mod 后成为 seed，发 [HavePacket] 告知房主，房主再转发给其他已 announce 的 peer。
 *
 * 真正的分块数据不走这里：P2P TCP 直连上的线协议见 `io.github.rwpp.net.p2p.ModPeerWire`。
 *
 * 退化约定（底线）：relay 房、对端 `listenPort=0`、peer 表为空时，整条链路必须完全退化为
 * Phase 1 的房主星型分发行为。
 */
@Suppress("MemberVisibilityCanBePrivate")
sealed class ModPeerPacket : Packet() {

    /**
     * client→房主：自报 P2P 能力。`listenPort=0` 表示不支持 P2P（如 relay 房），仅登记在场。
     */
    class AnnouncePacket : ModPeerPacket() {
        var requestId: Long = 0L
        /** P2P 监听端口；0 = 不支持 P2P。 */
        var listenPort: Int = 0
        /** 自报的 LAN IPv4 地址（站点本地地址），供同网段 peer 优先直连。 */
        var lanAddresses: List<String> = emptyList()

        override val type: Int = MOD_PEER_ANNOUNCE

        override fun readPacket(input: GameInputStream) {
            requestId = input.readLong()
            listenPort = input.readInt().also { require(it in 0..65535) { "invalid listen port" } }
            lanAddresses = readAddressList(input)
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeLong(requestId)
            output.writeInt(listenPort)
            writeAddressList(output, lanAddresses)
        }
    }

    /**
     * 房主→client：房间级 P2P 会话令牌 + 当前已知 peer 表（不含接收者自己）。
     * 与 [ModPacket.ManifestResponsePacket] 在同一条连接上紧接着下发。
     */
    class PeerListPacket : ModPeerPacket() {
        var requestId: Long = 0L
        /** 房间级会话令牌（房主在开房时生成），P2P 握手鉴权用。 */
        var token: String = ""
        var peers: List<PeerInfo> = emptyList()

        override val type: Int = MOD_PEER_LIST

        override fun readPacket(input: GameInputStream) {
            requestId = input.readLong()
            token = input.readUTF().also { require(it.length <= MAX_TOKEN_LENGTH) { "peer token too long" } }
            val count = input.readInt().also { require(it in 0..MAX_PEER_COUNT) { "invalid peer count" } }
            peers = List(count) { readPeerInfo(input) }
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeLong(requestId)
            output.writeUTF(token.take(MAX_TOKEN_LENGTH))
            require(peers.size <= MAX_PEER_COUNT) { "too many peers" }
            output.writeInt(peers.size)
            peers.forEach { writePeerInfo(output, it) }
        }
    }

    /**
     * client→房主：本机已完成 [modNames] 的下载，成为这些 mod 的 seed。
     * 房主更新登记后**转发**给其他已 announce 的 peer；转发时把 [ownerHexId] 填为
     * 原始发送者的 connectHexId，接收方据此更新自己 peer 表中的 completedMods。
     * （client→房主方向 [ownerHexId] 固定为空串。）
     */
    class HavePacket : ModPeerPacket() {
        var requestId: Long = 0L
        var modNames: List<String> = emptyList()
        /** 房主转发时填写的 seed 持有者 connectHexId；client→房主时为空串。 */
        var ownerHexId: String = ""
        /** true = 这些模组是通过 P2P 从其他 peer 拉取的（非房主星型分发）。房主据此统计 P2P 加速效果。 */
        var viaP2P: Boolean = false

        override val type: Int = MOD_PEER_HAVE

        override fun readPacket(input: GameInputStream) {
            requestId = input.readLong()
            val count = input.readInt().also { require(it in 0..MAX_MOD_NAME_COUNT) { "invalid mod name count" } }
            modNames = List(count) { input.readUTF().also { name -> validateModName(name) } }
            ownerHexId = input.readUTF().also { require(it.length <= MAX_HEX_ID_LENGTH) { "owner hex id too long" } }
            viaP2P = input.readBoolean()
        }

        override fun writePacket(output: GameOutputStream) {
            output.writeLong(requestId)
            require(modNames.size <= MAX_MOD_NAME_COUNT) { "too many mod names" }
            output.writeInt(modNames.size)
            modNames.forEach { name ->
                validateModName(name)
                output.writeUTF(name)
            }
            output.writeUTF(ownerHexId.take(MAX_HEX_ID_LENGTH))
            output.writeBoolean(viaP2P)
        }
    }

    /**
     * 一个可作为分块来源的 peer 的描述（房主观察 + 自报信息的合并视图）。
     */
    data class PeerInfo(
        /** 引擎连接级 ID（房主侧玩家标识），peer 表的主键。 */
        val connectHexId: String,
        /** 房主观察到的对端 IP（`Client.remoteAddress`），跨网段时的兜底地址候选。 */
        val observedAddress: String,
        /** P2P 监听端口；0 = 该 peer 不支持 P2P，选路时必须跳过。 */
        val listenPort: Int,
        /** peer 自报的 LAN IPv4 地址，同网段优先尝试。 */
        val lanAddresses: List<String>,
        /** 该 peer 已完成、可作为 seed 的 mod 名列表（随 HavePacket 增量更新）。 */
        var completedMods: List<String>,
    )

    companion object {
        const val MOD_PEER_ANNOUNCE = 512
        const val MOD_PEER_LIST = 513
        const val MOD_PEER_HAVE = 514

        /** 单个 peer 自报 LAN 地址的数量上限。 */
        const val MAX_LAN_ADDRESSES = 8
        /** 单个地址串的字符数上限（IPv6 字面量也够）。 */
        const val MAX_ADDRESS_LENGTH = 64
        /** PeerList 中 peer 的数量上限。 */
        const val MAX_PEER_COUNT = 64
        /** 房间会话令牌的字符数上限（UUID 为 36）。 */
        const val MAX_TOKEN_LENGTH = 128
        /** connectHexId 的字符数上限。 */
        const val MAX_HEX_ID_LENGTH = 64
        /** HavePacket 携带的 mod 名数量上限（与 manifest descriptor 上限一致）。 */
        const val MAX_MOD_NAME_COUNT = ModPacket.MAX_DESCRIPTOR_COUNT

        fun writePeerInfo(output: GameOutputStream, peer: PeerInfo) {
            require(peer.connectHexId.isNotBlank() && peer.connectHexId.length <= MAX_HEX_ID_LENGTH) { "invalid connect hex id" }
            require(peer.observedAddress.length <= MAX_ADDRESS_LENGTH) { "observed address too long" }
            require(peer.listenPort in 0..65535) { "invalid listen port" }
            require(peer.completedMods.size <= MAX_MOD_NAME_COUNT) { "too many completed mods" }
            output.writeUTF(peer.connectHexId)
            output.writeUTF(peer.observedAddress)
            output.writeInt(peer.listenPort)
            writeAddressList(output, peer.lanAddresses)
            output.writeInt(peer.completedMods.size)
            peer.completedMods.forEach { name ->
                validateModName(name)
                output.writeUTF(name)
            }
        }

        fun readPeerInfo(input: GameInputStream): PeerInfo {
            val connectHexId = input.readUTF().also {
                require(it.isNotBlank() && it.length <= MAX_HEX_ID_LENGTH) { "invalid connect hex id" }
            }
            val observedAddress = input.readUTF().also { require(it.length <= MAX_ADDRESS_LENGTH) { "observed address too long" } }
            val listenPort = input.readInt().also { require(it in 0..65535) { "invalid listen port" } }
            val lanAddresses = readAddressList(input)
            val modCount = input.readInt().also { require(it in 0..MAX_MOD_NAME_COUNT) { "invalid completed mod count" } }
            val completedMods = List(modCount) { input.readUTF().also { name -> validateModName(name) } }
            return PeerInfo(connectHexId, observedAddress, listenPort, lanAddresses, completedMods)
        }

        fun writeAddressList(output: GameOutputStream, addresses: List<String>) {
            require(addresses.size <= MAX_LAN_ADDRESSES) { "too many lan addresses" }
            output.writeInt(addresses.size)
            addresses.forEach { address ->
                require(address.isNotBlank() && address.length <= MAX_ADDRESS_LENGTH) { "invalid lan address" }
                output.writeUTF(address)
            }
        }

        fun readAddressList(input: GameInputStream): List<String> {
            val count = input.readInt().also { require(it in 0..MAX_LAN_ADDRESSES) { "invalid lan address count" } }
            return List(count) {
                input.readUTF().also { require(it.isNotBlank() && it.length <= MAX_ADDRESS_LENGTH) { "invalid lan address" } }
            }
        }

        private fun validateModName(name: String) {
            require(name.isNotBlank()) { "blank mod name" }
            require(name.length <= NetworkModDescriptor.MAX_NAME_LENGTH) { "mod name too long" }
        }
    }
}
