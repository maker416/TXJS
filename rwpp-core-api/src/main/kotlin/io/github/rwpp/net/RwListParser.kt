/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonObject
import io.github.rwpp.config.DEFAULT_ROOM_LIST_API_URLS

data class RwListServerEntry(
    val name: String,
    val ip: String,
    val needpass: Boolean,
    val mapname: String,
    val roomtype: String,
    val max_players: Int,
    val current_players: Int,
    val required_mod: String,
    val available: String,
    val server_id: String = "",
)

data class RwListServersPage(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val list: List<RwListServerEntry>,
)

fun normalizeRwListBaseUrl(url: String): String =
    url.trim().trimEnd('/')

private val legacyMasterserverMarkers = listOf(
    "/masterserver/",
    "action=list",
    "corrodinggames.com/masterserver",
    "corrodinggames.net/masterserver",
)

/** Pre-RWList public room list URL (masterserver CSV protocol). */
fun isLegacyMasterserverRoomListUrl(url: String): Boolean {
    val lower = url.lowercase()
    return legacyMasterserverMarkers.any { lower.contains(it) }
}

/**
 * Drop legacy masterserver URLs; fall back to [DEFAULT_ROOM_LIST_API_URLS] when none remain.
 */
fun migrateRoomListApiUrls(roomListApiUrls: String): String {
    val parts = roomListApiUrls
        .split(";")
        .map { normalizeRwListBaseUrl(it) }
        .filter { it.isNotEmpty() }
    if (parts.isEmpty()) return DEFAULT_ROOM_LIST_API_URLS
    val rwListBases = parts.filterNot(::isLegacyMasterserverRoomListUrl)
    return if (rwListBases.isEmpty()) DEFAULT_ROOM_LIST_API_URLS else rwListBases.joinToString(";")
}

fun parseRwListBaseUrls(roomListApiUrls: String): List<String> =
    migrateRoomListApiUrls(roomListApiUrls)
        .split(";")
        .map { normalizeRwListBaseUrl(it) }
        .filter { it.isNotEmpty() }
        .distinct()

fun parseRequiredModNames(requiredMod: String): List<String> {
    if (requiredMod.isBlank() || requiredMod == "[]") return emptyList()
    return runCatching {
        val arr = Json.parse(requiredMod).asArray()
        arr.mapNotNull { element ->
            if (!element.isObject) return@mapNotNull null
            val obj = element.asObject()
            obj.getString("modName", null)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }.getOrElse { emptyList() }
}

fun parseRwListServerEntry(obj: JsonObject): RwListServerEntry =
    RwListServerEntry(
        name = obj.getString("name", ""),
        ip = obj.getString("ip", ""),
        needpass = obj.getBoolean("needpass", false),
        mapname = obj.getString("mapname", ""),
        roomtype = obj.getString("roomtype", ""),
        max_players = obj.getInt("max_players", -1),
        current_players = obj.getInt("current_players", 0),
        required_mod = obj.getString("required_mod", "[]"),
        available = obj.getString("available", ""),
        server_id = obj.getString("server_id", ""),
    )

fun parseRwListServersPage(body: String): RwListServersPage {
    val trimmed = body.trimStart()
    if (trimmed.startsWith("CORRODINGGAMES", ignoreCase = true)) {
        throw RuntimeException(
            "Response is legacy masterserver CSV, not RWList JSON. " +
                "Update room list API to an RWList base URL (e.g. $DEFAULT_ROOM_LIST_API_URLS)."
        )
    }
    val root = Json.parse(body).asObject()
    val code = root.getInt("code", -1)
    if (code != 0) {
        val message = root.getString("message", "unknown error")
        throw RuntimeException("RWList error $code: $message")
    }
    val data = root.get("data")?.asObject()
        ?: throw RuntimeException("RWList response missing data")
    val listArr = data.get("list")?.asArray() ?: Json.array()
    val entries = listArr.mapNotNull { value ->
        if (value.isObject) parseRwListServerEntry(value.asObject()) else null
    }
    return RwListServersPage(
        total = data.getInt("total", entries.size),
        page = data.getInt("page", 1),
        pageSize = data.getInt("page_size", entries.size.coerceAtLeast(1)),
        list = entries,
    )
}

fun parseRwListRoomTypes(body: String): List<String> {
    val root = Json.parse(body).asObject()
    if (root.getInt("code", -1) != 0) return emptyList()
    val data = root.get("data")?.asObject() ?: return emptyList()
    val arr = data.get("room_types")?.asArray() ?: return emptyList()
    return arr.mapNotNull { value ->
        if (value.isString) value.asString().trim().takeIf { it.isNotEmpty() } else null
    }.distinct().sorted()
}

fun isRwListEntryJoinable(entry: RwListServerEntry): Boolean =
    entry.available == "1"

fun rwListEntryUuid(entry: RwListServerEntry): String =
    entry.server_id.takeIf { it.isNotBlank() } ?: entry.ip

fun mapRwListEntryToRoomDescription(entry: RwListServerEntry): RoomDescription {
    val modNames = parseRequiredModNames(entry.required_mod)
    val (host, port) = splitHostPort(entry.ip)
    return RoomDescription(
        uuid = rwListEntryUuid(entry),
        roomOwner = entry.name,
        netWorkAddress = host,
        port = port,
        isOpen = !entry.needpass,
        creator = entry.name,
        requiredPassword = entry.needpass,
        mapName = entry.mapname,
        status = "battleroom",
        version = if (modNames.isEmpty()) "vanilla" else "modded",
        displayMapName = entry.mapname,
        playerCurrentCount = entry.current_players,
        playerMaxCount = entry.max_players.takeIf { it >= 0 },
        mods = entry.required_mod,
        customIp = entry.ip,
        label = entry.roomtype,
    )
}

fun mapRwListEntriesToRoomDescriptions(entries: List<RwListServerEntry>): List<RoomDescription> =
    entries
        .filter(::isRwListEntryJoinable)
        .map(::mapRwListEntryToRoomDescription)

private fun splitHostPort(ip: String): Pair<String, Long> {
    val idx = ip.lastIndexOf(':')
    if (idx <= 0 || idx == ip.length - 1) return ip to 5123L
    val host = ip.substring(0, idx)
    val port = ip.substring(idx + 1).toLongOrNull() ?: 5123L
    return host to port
}
