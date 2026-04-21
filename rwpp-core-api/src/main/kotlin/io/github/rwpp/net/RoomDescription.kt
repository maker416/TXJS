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
     * Join transport hint from the third-party list protocol.
     *
     * - [RoomJoinType.IP]: treat [netWorkAddress] + [port] as a direct endpoint.
     * - [RoomJoinType.SHORT]: [netWorkAddress] is a short code, [port] is ignored.
     */
    val roomJoinType: String = RoomJoinType.IP,
) {
    fun addressProvider(): String {
        if (this.roomId != 0) {
            return "get|" + uuid2.replace("|", ".") + "|" + roomId + "|" + requiredPassword + "|" + port
        }
        return when (roomJoinType) {
            RoomJoinType.SHORT -> customIp ?: netWorkAddress
            else -> customIp ?: "$netWorkAddress:$port"
        }
    }
}

object RoomJoinType {
    const val IP = "IP"
    const val SHORT = "short"
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
 * Room list ordering by joinability first, then special tiers within the same tier:
 * 1. `ingame` always last.
 * 2. Password-required after open rooms.
 * 3. Full rooms (no available slots) after rooms with space.
 * 4. Version-mismatched rooms after version-matched rooms.
 * 5. Within the same tier: uuid relay → local → RELAY tag → other uppercase → normal.
 */
val List<RoomDescription>.sorted
    get() = sortedWith(
        compareBy<RoomDescription>(
            { if (it.status.contains("ingame", ignoreCase = true)) 1 else 0 },
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