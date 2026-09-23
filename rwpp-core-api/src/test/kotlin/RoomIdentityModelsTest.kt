/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.roomid

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 房间身份公示协议 DTO 的 JSON 序列化测试：除 round-trip 外，锚定 snake_case 字段名，
 * 防止与 Go 服务端字段名漂移。
 */
class RoomIdentityModelsTest {

    private val json = Json

    @Test
    fun publishRequestRoundTrip() {
        val req = RoomIdPublishRequest(
            roomKeys = listOf("code:Q77182", "addr:1.2.3.4:5123"),
            playerName = "萌新",
        )
        val encoded = json.encodeToString(req)
        assertTrue(encoded.contains("\"room_keys\""), "JSON 字段必须为 snake_case 的 room_keys：$encoded")
        assertTrue(encoded.contains("\"player_name\""), "JSON 字段必须为 snake_case 的 player_name：$encoded")
        assertEquals(req, json.decodeFromString<RoomIdPublishRequest>(encoded))
    }

    @Test
    fun lookupResponseDecodesWireFormat() {
        val wire = """
            {
              "users": [
                {"user_id": 123, "username": "abc", "nickname": "萌新"}
              ]
            }
        """.trimIndent()
        val resp = json.decodeFromString<RoomIdLookupResponse>(wire)
        assertEquals(1, resp.users.size)
        assertEquals(123L, resp.users[0].userId)
        assertEquals("abc", resp.users[0].username)
        assertEquals("萌新", resp.users[0].nickname)
    }

    @Test
    fun lookupResponseEmptyUsers() {
        // 无匹配 / 无权限：一律 200 + 空数组
        val resp = json.decodeFromString<RoomIdLookupResponse>("""{"users":[]}""")
        assertTrue(resp.users.isEmpty())
    }

    @Test
    fun roomIdEntryUsesSnakeCaseUserId() {
        val entry = RoomIdEntry(userId = 7L, username = "abc", nickname = "萌新")
        val encoded = json.encodeToString(entry)
        assertTrue(encoded.contains("\"user_id\""), "JSON 字段必须为 snake_case 的 user_id：$encoded")
        assertFalse(encoded.contains("\"userId\""))
        assertEquals(entry, json.decodeFromString<RoomIdEntry>(encoded))
    }

    @Test
    fun okResponseRoundTrip() {
        val ok = json.decodeFromString<RoomIdOkResponse>("""{"ok":true}""")
        assertTrue(ok.ok)
        assertTrue(json.encodeToString(RoomIdOkResponse(true)).contains("\"ok\""))
    }

    @Test
    fun lookupResponseToleratesUnknownFields() {
        // 服务端后续新增字段不得导致老客户端解析失败
        val tolerant = Json { ignoreUnknownKeys = true }
        val resp = tolerant.decodeFromString<RoomIdLookupResponse>(
            """{"users":[{"user_id":1,"username":"a","nickname":"b","extra":1}],"next_cursor":"x"}"""
        )
        assertEquals(1, resp.users.size)
    }
}
