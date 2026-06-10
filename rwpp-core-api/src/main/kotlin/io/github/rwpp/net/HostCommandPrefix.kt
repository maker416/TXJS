/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

/** Default join port for Q-series rooms (e.g. Q77182). */
const val Q_ROOM_JOIN_PORT = 5129

/** Default join port for R-series rooms (e.g. R77182). */
const val R_ROOM_JOIN_PORT = 5123

/** Resolve list/join port from a room short code such as Q77182 or R77182. */
fun roomJoinPortForId(roomId: String): Int =
    if (roomId.startsWith('Q')) Q_ROOM_JOIN_PORT else R_ROOM_JOIN_PORT

/** Address payload for publishing a room to RWList (`roomId:port`). */
fun roomListPublishAddress(roomId: String): String =
    "$roomId:${roomJoinPortForId(roomId)}"

/** Quick-host command family used when creating a multiplayer room from the UI. */
enum class HostCommandPrefix {
    /** Q-series: Qnews / Qmods / QC / QCM; join port [Q_ROOM_JOIN_PORT]. */
    Q,
    /** R-series: Rnews / Rmods / RC / RCM; join port [R_ROOM_JOIN_PORT]. */
    R,
}
