/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import io.github.rwpp.gameVersion

data class RoomDescription(
    val uuid: String,
    val roomOwner: String = "Unnamed", // ? for official server and custom client is always 'Unnamed'
    val gameVersion: Int = io.github.rwpp.gameVersion,
    val netWorkAddress: String = "unknown",
    val localAddress: String = "127.0.0.1",
    val port: Long = 5123,
    val isOpen: Boolean = false,
    val creator: String = "Unnamed",
    val requiredPassword: Boolean = false,
    val mapName: String = "Unknown",
    val mapType: String = "Unknown",
    val status: String = "battleroom",
    val version: String = "Unknown",
    val isLocal: Boolean = false,
    val displayMapName: String = "Unknown", // not sure, source code doesn't use this
    val playerCurrentCount: Int? = null, // may be blank
    val playerMaxCount: Int? = null,
    val isUpperCase: Boolean = false, // ???
    val uuid2: String = "Unknown", // use to get real ip from list??
    val unknown: Boolean = false, // it is unused in source code
    val mods: String = "", // even though, this cannot be evidence that the mod has been enabled
    val roomId: Int = 0,
    val customIp: String? = null,
    /**
     * Raw RWList `roomtype` scalar. RWList v2.14.0 stores one or more labels joined by `|`.
     * Use [labels] for normalized semantic access rather than comparing this string directly.
     */
    val label: String = "",
    /**
     * Join transport hint from the third-party list protocol.
     *
     * - [RoomJoinType.IP]: treat [netWorkAddress] + [port] as a direct endpoint.
     * - [RoomJoinType.SHORT]: [netWorkAddress] is a short code, [port] is ignored.
     */
    val roomJoinType: String = RoomJoinType.IP,
    /** RWList `available == "1"`; false covers offline, in-game, and other non-joinable states. */
    val listAvailable: Boolean = true,
) {
    fun addressProvider(): String {
        if (this.roomId != 0) {
            return "get|" + uuid2.replace("|", ".") + "|" + roomId + "|" + requiredPassword + "|" + port
        }
        return when (roomJoinType) {
            RoomJoinType.SHORT -> netWorkAddress
            else -> customIp ?: "$netWorkAddress:$port"
        }
    }

    /** Relay/list join uuid for the game engine; null when unset or placeholder. */
    fun joinRelayUuid(): String? =
        uuid2.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
}

fun sanitizeJoinRelayUuid(uuid: String?): String? =
    uuid?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }

object RoomJoinType {
    const val IP = "IP"
    const val SHORT = "short"
}

enum class RoomListDegradeReason {
    None,
    VersionMismatch,
    PasswordRequired,
    Full,
    Unavailable,
}

fun RoomDescription.listDegradeReason(): RoomListDegradeReason {
    if (!listAvailable) return RoomListDegradeReason.Unavailable
    if (playerCurrentCount != null && playerMaxCount != null
        && playerCurrentCount >= playerMaxCount) return RoomListDegradeReason.Full
    if (requiredPassword) return RoomListDegradeReason.PasswordRequired
    if (gameVersion != io.github.rwpp.gameVersion) return RoomListDegradeReason.VersionMismatch
    return RoomListDegradeReason.None
}

/** 服务端「模组同步」房间类型字符串：房主开启传输模组（MOD 同步）后公开房间所用的标签。 */
const val MOD_SYNC_ROOM_TYPE = "模组同步"

/** 公开发布时未选择任何普通标签时的默认房间标签。 */
const val DEFAULT_PUBLISH_ROOM_TYPE = "默认"

/**
 * 解析 RWList `roomtype`（竖线分隔的多标签串）为规范标签列表：
 * 按 `|` 拆分、去除每段首尾空格、丢弃空段、按首次出现顺序去重（大小写不敏感，保留首次出现的原始写法）。
 */
fun parseRoomLabels(roomType: String): List<String> {
    if (roomType.isBlank()) return emptyList()
    val seen = LinkedHashSet<String>()
    roomType.split('|').forEach { segment ->
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return@forEach
        val key = trimmed.lowercase()
        if (seen.none { it.lowercase() == key }) seen.add(trimmed)
    }
    return seen.toList()
}

/** 将标签列表按 [parseRoomLabels] 的逆操作编码为 RWList wire 串（去空、按给定顺序去重）。 */
fun encodeRoomLabels(labels: Iterable<String>): String {
    val seen = LinkedHashSet<String>()
    labels.forEach { label ->
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return@forEach
        val key = trimmed.lowercase()
        if (seen.none { it.lowercase() == key }) seen.add(trimmed)
    }
    return seen.joinToString("|")
}

/** 房间所有已解析标签，保留服务端原始顺序；空标签返回空列表。 */
val RoomDescription.labels: List<String>
    get() = parseRoomLabels(label)

/** 当前房间是否包含给定标签（大小写、首尾空格不敏感）。 */
fun RoomDescription.hasRoomLabel(label: String): Boolean {
    val key = label.trim().lowercase()
    if (key.isEmpty()) return false
    return labels.any { it.lowercase() == key }
}

/**
 * OR 语义的标签筛选：[selected] 为空时不过滤（返回 true）；
 * 否则当房间任一已解析标签命中已选标签时保留。比较对大小写、首尾空格不敏感。
 */
fun RoomDescription.matchesAnyRoomLabel(selected: Set<String>): Boolean {
    if (selected.isEmpty()) return true
    val keys = selected.mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.toHashSet()
    if (keys.isEmpty()) return true
    return labels.any { it.lowercase() in keys }
}

/**
 * 组合公开发布标签：在用户已选普通标签（保持给定顺序、去重）之后，
 * 若普通标签为空则回退 [DEFAULT_PUBLISH_ROOM_TYPE]；
 * 当 [includeModSync] 为真时追加协议哨兵 [MOD_SYNC_ROOM_TYPE]（若已在普通标签中则不重复）。
 * 返回可直接传给 RWList `roomtype` 的 wire 串。
 */
fun composePublishRoomType(
    selectedOrdinary: List<String>,
    includeModSync: Boolean,
): String {
    val ordered = mutableListOf<String>()
    val seen = HashSet<String>()
    selectedOrdinary.forEach { label ->
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return@forEach
        val key = trimmed.lowercase()
        if (seen.add(key)) ordered.add(trimmed)
    }
    if (ordered.isEmpty()) {
        val fallback = DEFAULT_PUBLISH_ROOM_TYPE.trim()
        if (fallback.isNotEmpty() && seen.add(fallback.lowercase())) {
            ordered.add(fallback)
        }
    }
    if (includeModSync) {
        val key = MOD_SYNC_ROOM_TYPE.lowercase()
        if (seen.add(key)) ordered.add(MOD_SYNC_ROOM_TYPE)
    }
    return ordered.joinToString("|")
}

/**
 * 模组同步的能力状态。服务端房间类型 [MOD_SYNC_ROOM_TYPE] 仅作为协议哨兵，不应直接作为 UI 文案。
 */
enum class ModSyncStatus {
    NotModded,
    Enabled,
    NotEnabled,
}

/** True when the room requires non-empty mods (RWList `required_mod` or [version] == modded). */
val RoomDescription.isModdedRoom: Boolean
    get() = version.equals("modded", ignoreCase = true) || parseRequiredModNames(mods).isNotEmpty()

/**
 * 从已解析标签集合推导模组同步能力；标签筛选应使用 [matchesAnyRoomLabel]。
 */
val RoomDescription.modSyncStatus: ModSyncStatus
    get() = when {
        !isModdedRoom -> ModSyncStatus.NotModded
        hasRoomLabel(MOD_SYNC_ROOM_TYPE) -> ModSyncStatus.Enabled
        else -> ModSyncStatus.NotEnabled
    }

/** Whether the list UI should offer join; password rooms stay joinable (password at connect time). */
val RoomDescription.isJoinableFromList: Boolean
    get() = when (listDegradeReason()) {
        RoomListDegradeReason.Unavailable,
        RoomListDegradeReason.Full,
        RoomListDegradeReason.VersionMismatch -> false
        RoomListDegradeReason.PasswordRequired,
        RoomListDegradeReason.None -> true
    }

private fun RoomDescription.battleroomSubRank(): Int {
    if (!status.contains("battleroom", ignoreCase = true)) return 0
    return when {
        playerCurrentCount != null && playerMaxCount != null
            && playerCurrentCount < playerMaxCount
            && gameVersion == io.github.rwpp.gameVersion
            && isOpen -> if (isUpperCase) 3 else 5
        gameVersion == io.github.rwpp.gameVersion -> 6
        isUpperCase -> 7
        isOpen -> 9
        else -> 10
    }
}

/**
 * Room list ordering: [listAvailable] first, then within each group:
 * 1. Password-required after open rooms.
 * 2. Full rooms (no available slots) after rooms with space.
 * 3. Version-mismatched rooms after version-matched rooms.
 * 4. Within the same tier: uuid relay → local → RELAY tag → other uppercase → normal.
 */
val List<RoomDescription>.sorted
    get() = sortedWith(
        compareBy<RoomDescription>(
            { if (!it.listAvailable) 1 else 0 },
            { if (it.requiredPassword) 1 else 0 },
            {
                if (it.playerCurrentCount != null && it.playerMaxCount != null
                    && it.playerCurrentCount >= it.playerMaxCount) 1 else 0
            },
            { if (it.gameVersion != io.github.rwpp.gameVersion) 1 else 0 },
            {
                when {
                    it.isUpperCase && it.netWorkAddress.startsWith("uuid:") -> 0
                    it.isLocal -> 1
                    it.isUpperCase && it.creator.contains("RELAY") -> 2
                    it.isUpperCase -> 3
                    else -> 4
                }
            },
        )
    )