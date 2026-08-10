/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync

import io.github.rwpp.game.mod.NetworkModDescriptor
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 同步协议 DTO 的 JSON 序列化测试：除 round-trip 外，锚定 snake_case 字段名，
 * 防止与 Go 服务端字段名漂移。
 */
class ModSyncModelsTest {

    private val json = Json

    private val descriptor = SyncModDescriptor(
        name = "test mod",
        size = 42L,
        sha256 = "a".repeat(64),
    )

    @Test
    fun syncModDescriptorRoundTrip() {
        val encoded = json.encodeToString(descriptor)
        assertTrue(encoded.contains("\"sha256\""), "JSON 字段必须为 snake_case 的 sha256：$encoded")
        assertTrue(encoded.contains("\"size\""))
        assertEquals(descriptor, json.decodeFromString<SyncModDescriptor>(encoded))
    }

    @Test
    fun roomRegisterRequestRoundTrip() {
        val req = RoomRegisterRequest(
            key = "code:Q77182",
            secret = "s3cret",
            gameVersion = "1.15",
            mods = listOf(descriptor),
        )
        val encoded = json.encodeToString(req)
        assertTrue(encoded.contains("\"game_version\""), "JSON 字段必须为 snake_case 的 game_version：$encoded")
        assertTrue(encoded.contains("\"sha256\""))
        assertEquals(req, json.decodeFromString<RoomRegisterRequest>(encoded))
    }

    @Test
    fun roomManifestResponseDecodesWireFormat() {
        val wire = """
            {
              "status": "ready",
              "game_version": "1.15",
              "mods": [{"name": "test mod", "size": 42, "sha256": "${"a".repeat(64)}"}]
            }
        """.trimIndent()
        val resp = json.decodeFromString<RoomManifestResponse>(wire)
        assertEquals(SyncStatus.READY, resp.status)
        assertEquals("1.15", resp.gameVersion)
        assertEquals(listOf(descriptor), resp.mods)
        assertTrue(resp.isReady)
        assertFalse(resp.isPreparing)
    }

    @Test
    fun roomManifestResponseDefaultsAndPreparing() {
        val resp = json.decodeFromString<RoomManifestResponse>("""{"status": "preparing", "mods": []}""")
        assertEquals("", resp.gameVersion)
        assertTrue(resp.isPreparing)
        assertFalse(resp.isReady)

        val encoded = json.encodeToString(
            RoomManifestResponse(SyncStatus.READY, "1.15", listOf(descriptor))
        )
        assertTrue(encoded.contains("\"game_version\""), "编码必须输出 game_version：$encoded")
    }

    @Test
    fun filesCheckRoundTrip() {
        val req = FilesCheckRequest(hashes = listOf("a".repeat(64), "b".repeat(64)))
        assertEquals(req, json.decodeFromString<FilesCheckRequest>(json.encodeToString(req)))

        val resp = FilesCheckResponse(missing = listOf("b".repeat(64)))
        assertEquals(resp, json.decodeFromString<FilesCheckResponse>(json.encodeToString(resp)))
        assertEquals(resp, json.decodeFromString<FilesCheckResponse>("""{"missing": ["${"b".repeat(64)}"]}"""))
    }

    @Test
    fun aliasRequestRoundTrip() {
        val req = AliasRequest(alias = "sid:e72d379e-b489-480e-b356-cb3251c0c2b6")
        assertEquals(req, json.decodeFromString<AliasRequest>(json.encodeToString(req)))
    }

    @Test
    fun errorResponseDecoding() {
        assertEquals("boom", json.decodeFromString<ErrorResponse>("""{"error": "boom"}""").error)
        assertEquals("", json.decodeFromString<ErrorResponse>("""{}""").error)
    }

    @Test
    fun syncStatusWireValues() {
        assertEquals("preparing", SyncStatus.PREPARING)
        assertEquals("ready", SyncStatus.READY)
    }

    @Test
    fun networkDescriptorConversionRoundTrip() {
        val network = NetworkModDescriptor.fromBytes("test mod", byteArrayOf(1, 2, 3))
        val sync = network.toSync()
        assertEquals(network.name, sync.name)
        assertEquals(network.payloadSize, sync.size)
        assertEquals(network.normalizedSha256, sync.sha256)
        assertEquals(network, sync.toNetwork())
    }

    @Test
    fun syncPeerUpsertRequestSnakeCase() {
        val req = SyncPeerUpsertRequest(
            displayName = "萌新",
            phase = SyncPeerPhase.DOWNLOADING,
            currentModName = "modA",
            currentBytes = 123L,
            currentTotal = 1000L,
            modIndex = 1,
            modCount = 2,
        )
        val encoded = json.encodeToString(req)
        assertTrue(encoded.contains("\"display_name\""), encoded)
        assertTrue(encoded.contains("\"current_mod_name\""), encoded)
        assertTrue(encoded.contains("\"current_bytes\""), encoded)
        assertTrue(encoded.contains("\"current_total\""), encoded)
        assertTrue(encoded.contains("\"mod_index\""), encoded)
        assertTrue(encoded.contains("\"mod_count\""), encoded)
        assertEquals(req, json.decodeFromString<SyncPeerUpsertRequest>(encoded))
        // 默认 0 字段可被省略编码；服务端零值等价，解码缺省字段仍为 0
        val wire = """{"display_name":"x","phase":"waiting_host"}"""
        val decoded = json.decodeFromString<SyncPeerUpsertRequest>(wire)
        assertEquals(0, decoded.modIndex)
        assertEquals(SyncPeerPhase.WAITING_HOST, decoded.phase)
    }

    @Test
    fun syncPeerListResponseDecodesWireFormat() {
        val wire = """
            {
              "peers": [{
                "peer_id": "peer-abc",
                "display_name": "萌新",
                "phase": "applying",
                "current_mod_name": "modA",
                "current_bytes": 50,
                "current_total": 100,
                "mod_index": 1,
                "mod_count": 2,
                "updated_at": "2026-08-04T03:00:00Z"
              }]
            }
        """.trimIndent()
        val resp = json.decodeFromString<SyncPeerListResponse>(wire)
        assertEquals(1, resp.peers.size)
        val peer = resp.peers.first()
        assertEquals("peer-abc", peer.peerId)
        assertEquals("萌新", peer.displayName)
        assertEquals(SyncPeerPhase.APPLYING, peer.phase)
        assertEquals("", peer.peerSecret)
        val snap = peer.toSnapshot()
        assertEquals(peer.peerId, snap.peerId)
        assertEquals(50L, snap.currentBytes)
        assertEquals(100L, snap.currentTotal)

        val createWire = """
            {
              "peer_id": "peer-abc",
              "display_name": "萌新",
              "phase": "waiting_host",
              "peer_secret": "s3cr3t-once"
            }
        """.trimIndent()
        val created = json.decodeFromString<SyncPeerProgress>(createWire)
        assertEquals("s3cr3t-once", created.peerSecret)
        assertTrue(json.encodeToString(created).contains("\"peer_secret\""), "peer_secret snake_case")
    }

    @Test
    fun syncPeerPhaseWireValues() {
        assertEquals("waiting_host", SyncPeerPhase.WAITING_HOST)
        assertEquals("downloading", SyncPeerPhase.DOWNLOADING)
        assertEquals("applying", SyncPeerPhase.APPLYING)
        assertEquals("joining", SyncPeerPhase.JOINING)
    }
}
