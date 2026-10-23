/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rwpp.net.RoomDescription

internal data class RoomJoinRequest(val address: String, val room: RoomDescription?)

/** 列表确认弹窗的选择与已经发起的连接分开，退出动画不能改写进行中的连接。 */
internal class MultiplayerRoomJoinContext {
    var selectedRoom by mutableStateOf<RoomDescription?>(null)
    private var connectionRoom by mutableStateOf<RoomDescription?>(null)

    fun dismissSelection() {
        selectedRoom = null
    }

    /** 非列表入口省略 room，必须同时清除此前列表选择及连接描述。 */
    fun beginConnection(room: RoomDescription? = null) {
        connectionRoom = room
        if (room == null) selectedRoom = null
    }

    fun snapshotConnection(address: String): RoomJoinRequest = RoomJoinRequest(address, connectionRoom)
}
