/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.io.GameInputStream
import io.github.rwpp.io.GameOutputStream
import io.github.rwpp.net.packets.ModPeerPacket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModPeerPacketSerializationTest {
    private fun roundTrip(packet: ModPeerPacket): ModPeerPacket {
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
    fun announceRoundTrips() {
        val packet = ModPeerPacket.AnnouncePacket().apply {
            requestId = 21L
            listenPort = 51234
            lanAddresses = listOf("192.168.1.10", "10.0.0.2")
        }
        val out = roundTrip(packet) as ModPeerPacket.AnnouncePacket
        assertEquals(21L, out.requestId)
        assertEquals(51234, out.listenPort)
        assertEquals(listOf("192.168.1.10", "10.0.0.2"), out.lanAddresses)
    }

    @Test
    fun announceRoundTripsWithZeroPort() {
        // relay 房：port=0 表示不支持 P2P
        val packet = ModPeerPacket.AnnouncePacket().apply {
            requestId = 22L
            listenPort = 0
            lanAddresses = emptyList()
        }
        val out = roundTrip(packet) as ModPeerPacket.AnnouncePacket
        assertEquals(0, out.listenPort)
        assertEquals(emptyList(), out.lanAddresses)
    }

    @Test
    fun peerListRoundTrips() {
        val peers = listOf(
            ModPeerPacket.PeerInfo(
                connectHexId = "a1b2",
                observedAddress = "203.0.113.7",
                listenPort = 50001,
                lanAddresses = listOf("192.168.1.11"),
                completedMods = listOf("modA", "模组-β"),
            ),
            ModPeerPacket.PeerInfo(
                connectHexId = "c3d4",
                observedAddress = "",
                listenPort = 0,
                lanAddresses = emptyList(),
                completedMods = emptyList(),
            ),
        )
        val packet = ModPeerPacket.PeerListPacket().apply {
            requestId = 23L
            token = "550e8400-e29b-41d4-a716-446655440000"
            this.peers = peers
        }
        val out = roundTrip(packet) as ModPeerPacket.PeerListPacket
        assertEquals(23L, out.requestId)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", out.token)
        assertEquals(peers, out.peers)
    }

    @Test
    fun haveRoundTrips() {
        val packet = ModPeerPacket.HavePacket().apply {
            requestId = 24L
            modNames = listOf("modA")
            ownerHexId = ""
        }
        val out = roundTrip(packet) as ModPeerPacket.HavePacket
        assertEquals(24L, out.requestId)
        assertEquals(listOf("modA"), out.modNames)
        assertEquals("", out.ownerHexId)
    }

    @Test
    fun haveRoundTripsWithOwnerHexId() {
        // 房主转发时填写 seed 持有者的 connectHexId
        val packet = ModPeerPacket.HavePacket().apply {
            requestId = 25L
            modNames = listOf("modA", "modB")
            ownerHexId = "a1b2"
        }
        val out = roundTrip(packet) as ModPeerPacket.HavePacket
        assertEquals("a1b2", out.ownerHexId)
        assertEquals(listOf("modA", "modB"), out.modNames)
    }

    @Test
    fun rejectInvalidListenPort() {
        val bytes = gameOutput {
            writeLong(1L)
            writeInt(70000) // 超出 0..65535
            writeInt(0)
        }
        val decoded = ModPeerPacket.AnnouncePacket()
        assertNull(runCatching {
            ByteArrayInputStream(bytes).use { input ->
                decoded.readPacket(GameInputStream(DataInputStream(input)))
            }
        }.getOrNull())
    }

    @Test
    fun rejectTooManyLanAddresses() {
        val bytes = gameOutput {
            writeLong(1L)
            writeInt(5000)
            writeInt(ModPeerPacket.MAX_LAN_ADDRESSES + 1)
        }
        val decoded = ModPeerPacket.AnnouncePacket()
        assertNull(runCatching {
            ByteArrayInputStream(bytes).use { input ->
                decoded.readPacket(GameInputStream(DataInputStream(input)))
            }
        }.getOrNull())
    }

    @Test
    fun rejectTooManyPeers() {
        val bytes = gameOutput {
            writeLong(1L)
            writeUTF("token")
            writeInt(ModPeerPacket.MAX_PEER_COUNT + 1)
        }
        val decoded = ModPeerPacket.PeerListPacket()
        assertNull(runCatching {
            ByteArrayInputStream(bytes).use { input ->
                decoded.readPacket(GameInputStream(DataInputStream(input)))
            }
        }.getOrNull())
    }

    @Test
    fun rejectTooLongToken() {
        val bytes = gameOutput {
            writeLong(1L)
            writeUTF("t".repeat(ModPeerPacket.MAX_TOKEN_LENGTH + 1))
            writeInt(0)
        }
        val decoded = ModPeerPacket.PeerListPacket()
        assertNull(runCatching {
            ByteArrayInputStream(bytes).use { input ->
                decoded.readPacket(GameInputStream(DataInputStream(input)))
            }
        }.getOrNull())
    }

    @Test
    fun rejectTooManyHaveModNames() {
        val bytes = gameOutput {
            writeLong(1L)
            writeInt(ModPeerPacket.MAX_MOD_NAME_COUNT + 1)
        }
        val decoded = ModPeerPacket.HavePacket()
        assertNull(runCatching {
            ByteArrayInputStream(bytes).use { input ->
                decoded.readPacket(GameInputStream(DataInputStream(input)))
            }
        }.getOrNull())
    }

    @Test
    fun peerInfoRoundTripsViaHelpers() {
        val peer = ModPeerPacket.PeerInfo(
            connectHexId = "ff00",
            observedAddress = "198.51.100.3",
            listenPort = 49152,
            lanAddresses = listOf("172.16.0.5"),
            completedMods = listOf("x"),
        )
        val bytes = gameOutput { ModPeerPacket.writePeerInfo(this, peer) }
        val decoded = ByteArrayInputStream(bytes).use { input ->
            ModPeerPacket.readPeerInfo(GameInputStream(DataInputStream(input)))
        }
        assertEquals(peer, decoded)
        assertTrue(decoded.completedMods.contains("x"))
    }
}
