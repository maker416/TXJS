/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

/** Default join port for Q-series rooms (e.g. Q77182). */
const val Q_ROOM_JOIN_PORT = 5123

/** Default join port for R-series rooms (e.g. R77182). */
const val R_ROOM_JOIN_PORT = 5123

/** T 房间使用原版引擎的「中转域名/房间号」格式。 */
const val T_ROOM_ADDRESS_PREFIX = "t.mxy.wang/"
const val T_ROOM_JOIN_PORT = 5123

private val roomCodeRegex = Regex("""^([QRT])(\d+)(?::\d+)?$""", RegexOption.IGNORE_CASE)
private val tRoomAddressRegex = Regex(
    """^t\.mxy\.wang(?::5123)?/(\d+)(?::5123)?$""",
    RegexOption.IGNORE_CASE,
)
private val roomAddressInDetailsRegex = Regex(
    """(?<![\w./])(?:t\.mxy\.wang(?::5123)?/\d+|[QRT]\d+)(?![\w])""",
    RegexOption.IGNORE_CASE,
)

/** 统一同步/身份公示标识；T 地址归一为 T 短码，避免 key 中出现服务端不接受的斜线。 */
fun roomCodeForAddress(address: String): String? {
    val trimmed = address.trim()
    roomCodeRegex.matchEntire(trimmed)?.let { return it.groupValues[1].uppercase() + it.groupValues[2] }
    return tRoomAddressRegex.matchEntire(trimmed)?.let { "T${it.groupValues[1]}" }
}

/** 对外可加入的房间地址；T 短码只能通过指定中转域名加入。 */
fun roomJoinAddressForId(roomId: String): String {
    val code = roomCodeForAddress(roomId) ?: return roomId.trim()
    return if (code.startsWith('T')) T_ROOM_ADDRESS_PREFIX + code.drop(1) else code
}

/** 从引擎房间详情中提取可加入地址，不把快速建房指令识别成房间号。 */
fun extractRoomJoinAddress(details: String): String? =
    roomAddressInDetailsRegex.find(details)?.value?.let(::roomJoinAddressForId)

/** 等待室展示可直接复制的加入地址，T 短码展示为完整中转地址。 */
fun normalizeRoomAddressesInDetails(details: String): String =
    roomAddressInDetailsRegex.replace(details) { roomJoinAddressForId(it.value) }

/** Resolve list/join port from a Q/R/T room address. */
fun roomJoinPortForId(roomId: String): Int = when (roomCodeForAddress(roomId)?.firstOrNull()) {
    'Q' -> Q_ROOM_JOIN_PORT
    'T' -> T_ROOM_JOIN_PORT
    else -> R_ROOM_JOIN_PORT
}

/** Address payload for RWList; a T relay path must not have a port appended to its room number. */
fun roomListPublishAddress(roomId: String): String {
    val address = roomJoinAddressForId(roomId)
    return if (address.startsWith(T_ROOM_ADDRESS_PREFIX)) address else "$address:${roomJoinPortForId(roomId)}"
}

/** Quick-host command family used when creating a multiplayer room from the UI. */
enum class HostCommandPrefix {
    /** Q-series: Qnews / Qmods / QC / QCM; join port [Q_ROOM_JOIN_PORT]. */
    Q,
    /** R-series: Rnews / Rmods / RC / RCM; join port [R_ROOM_JOIN_PORT]. */
    R,
    /** T-series: t.mxy.wang/news / mods / C / CM; join via [T_ROOM_ADDRESS_PREFIX]. */
    T,
}
