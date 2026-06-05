/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import io.github.rwpp.config.DEFAULT_ROOM_LIST_API_URLS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RwListParserTest {

    private val sampleListJson = """
        {
          "code": 0,
          "data": {
            "list": [
              {
                "available": "1",
                "current_players": 0,
                "ip": "127.0.0.1:5123",
                "mapname": "[z;p10]Crossing Large (10p).tmx",
                "max_players": -1,
                "name": "本地测试服务器",
                "needpass": false,
                "required_mod": "[]",
                "roomtype": "公益",
                "server_id": "c75e1f5a-22aa-4205-a0bb-f889550581cc"
              }
            ],
            "page": 1,
            "page_size": 20,
            "total": 1
          },
          "message": "success"
        }
    """.trimIndent()

    @Test
    fun parseSampleServersPage() {
        val page = parseRwListServersPage(sampleListJson)
        assertEquals(1, page.total)
        assertEquals(1, page.list.size)
        assertEquals("127.0.0.1:5123", page.list.first().ip)
        assertEquals("c75e1f5a-22aa-4205-a0bb-f889550581cc", page.list.first().server_id)
        assertEquals("公益", page.list.first().roomtype)
    }

    @Test
    fun mapSampleEntryToRoomDescription() {
        val entry = parseRwListServersPage(sampleListJson).list.first()
        val desc = mapRwListEntryToRoomDescription(entry)
        assertEquals("c75e1f5a-22aa-4205-a0bb-f889550581cc", desc.uuid)
        assertEquals("127.0.0.1:5123", desc.customIp)
        assertEquals("本地测试服务器", desc.creator)
        assertEquals("公益", desc.label)
        assertNull(desc.playerMaxCount)
        assertEquals(0, desc.playerCurrentCount)
        assertEquals("vanilla", desc.version)
    }

    @Test
    fun keepsUnavailableEntriesWithListAvailableFlag() {
        val joinable = RwListServerEntry(
            name = "A", ip = "1.1.1.1:1", needpass = false, mapname = "m",
            roomtype = "public", max_players = 10, current_players = 1,
            required_mod = "[]", available = "1", server_id = "id-a",
        )
        val unavailable = joinable.copy(ip = "2.2.2.2:2", available = "0", server_id = "id-b")
        val unknown = joinable.copy(ip = "3.3.3.3:3", available = "", server_id = "id-c")
        val result = mapRwListEntriesToRoomDescriptions(listOf(joinable, unavailable, unknown))
        assertEquals(3, result.size)
        assertEquals(listOf(true, false, false), result.map { it.listAvailable })
        assertEquals(listOf("id-a", "id-b", "id-c"), result.map { it.uuid })
    }

    @Test
    fun parseRequiredModNamesFromJsonArray() {
        val mods = """[{"modName":"a-mod","unitCount":1},{"modName":"b-mod","unitCount":2}]"""
        assertEquals(listOf("a-mod", "b-mod"), parseRequiredModNames(mods))
        assertTrue(parseRequiredModNames("[]").isEmpty())
    }

    @Test
    fun moddedEntryMapsVersion() {
        val entry = RwListServerEntry(
            name = "Mod room", ip = "127.0.0.1:5123", needpass = false, mapname = "m",
            roomtype = "public", max_players = 8, current_players = 2,
            required_mod = """[{"modName":"x","unitCount":1}]""", available = "1",
        )
        val desc = mapRwListEntryToRoomDescription(entry)
        assertEquals("modded", desc.version)
    }

    @Test
    fun parseRoomTypesResponse() {
        val body = """
            {
              "code": 0,
              "message": "success",
              "data": { "room_types": ["public", "公益", "pvp"] }
            }
        """.trimIndent()
        assertEquals(listOf("public", "pvp", "公益"), parseRwListRoomTypes(body))
    }

    @Test
    fun keepsAllJoinableEntriesWithSameIp() {
        val entry = RwListServerEntry(
            name = "A", ip = "127.0.0.1:5123", needpass = false, mapname = "m",
            roomtype = "public", max_players = 10, current_players = 1,
            required_mod = "[]", available = "1", server_id = "id-1",
        )
        val result = mapRwListEntriesToRoomDescriptions(
            listOf(
                entry,
                entry.copy(name = "B", server_id = "id-2"),
                entry.copy(name = "C", available = "0", server_id = "id-3"),
            ),
        )
        assertEquals(3, result.size)
        assertEquals(listOf("A", "B", "C"), result.map { it.creator })
        assertEquals(listOf("id-1", "id-2", "id-3"), result.map { it.uuid })
        assertEquals(listOf(true, true, false), result.map { it.listAvailable })
    }

    @Test
    fun migrateLegacyMasterserverUrls() {
        val legacy =
            "http://gs1.corrodinggames.com/masterserver/1.4/interface?action=list&game_version=176&game_version_beta=false;" +
                "http://gs4.corrodinggames.net/masterserver/1.4/interface?action=list&game_version=176&game_version_beta=false"
        assertTrue(isLegacyMasterserverRoomListUrl(legacy.substringBefore(';')))
        assertEquals(DEFAULT_ROOM_LIST_API_URLS, migrateRoomListApiUrls(legacy))
        assertEquals(listOf(DEFAULT_ROOM_LIST_API_URLS), parseRwListBaseUrls(legacy))
        assertEquals(
            listOf("http://example.com"),
            parseRwListBaseUrls("$legacy;http://example.com"),
        )
    }

    @Test
    fun parseBaseUrls() {
        assertEquals(
            listOf(DEFAULT_ROOM_LIST_API_URLS, "http://example.com"),
            parseRwListBaseUrls("${DEFAULT_ROOM_LIST_API_URLS}/; http://example.com"),
        )
        assertEquals(DEFAULT_ROOM_LIST_API_URLS, normalizeRwListBaseUrl("$DEFAULT_ROOM_LIST_API_URLS/"))
    }

    @Test
    fun roomListApiBasesWithDefaultFallbackAppendsBuiltinMirror() {
        assertEquals(
            listOf("http://example.com", DEFAULT_ROOM_LIST_API_URLS),
            roomListApiBasesWithDefaultFallback("http://example.com"),
        )
        assertEquals(
            listOf(DEFAULT_ROOM_LIST_API_URLS),
            roomListApiBasesWithDefaultFallback(DEFAULT_ROOM_LIST_API_URLS),
        )
    }

    @Test
    fun listAvailableRoomsSortBeforeUnavailable() {
        val available = mapRwListEntryToRoomDescription(
            RwListServerEntry(
                name = "Open", ip = "1.1.1.1:1", needpass = false, mapname = "m",
                roomtype = "public", max_players = 10, current_players = 1,
                required_mod = "[]", available = "1", server_id = "id-open",
            ),
        )
        val unavailable = mapRwListEntryToRoomDescription(
            RwListServerEntry(
                name = "Closed", ip = "2.2.2.2:2", needpass = false, mapname = "m",
                roomtype = "public", max_players = 10, current_players = 1,
                required_mod = "[]", available = "0", server_id = "id-closed",
            ),
        )
        val sorted = listOf(unavailable, available).sorted
        assertEquals(listOf("id-open", "id-closed"), sorted.map { it.uuid })
        assertEquals(RoomListDegradeReason.Unavailable, unavailable.listDegradeReason())
        assertEquals(RoomListDegradeReason.None, available.listDegradeReason())
    }

    @Test
    fun passwordProtectedRoomRemainsJoinableFromList() {
        val passwordRoom = RoomDescription(
            uuid = "pw-1",
            requiredPassword = true,
            listAvailable = true,
        )
        assertEquals(RoomListDegradeReason.PasswordRequired, passwordRoom.listDegradeReason())
        assertTrue(passwordRoom.isJoinableFromList)
    }

    @Test
    fun joinableFlag() {
        assertTrue(
            isRwListEntryJoinable(RwListServerEntry(
                "", "", false, "", "", -1, 0, "[]", "1",
            ))
        )
        assertFalse(
            isRwListEntryJoinable(RwListServerEntry(
                "", "", false, "", "", -1, 0, "[]", "0",
            ))
        )
    }

    @Test
    fun parseErrorResponseThrows() {
        val body = """{"code":1001,"message":"参数错误"}"""
        try {
            parseRwListServersPage(body)
            error("Expected exception")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("1001"))
        }
    }
}
