/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.net.RoomDescription
import io.github.rwpp.net.RoomJoinType
import io.github.rwpp.net.sync.forAddress
import io.github.rwpp.net.sync.forRoomDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MultiplayerRoomJoinContextTest {
    private val listedRoom = RoomDescription(
        uuid = "listed-server", uuid2 = "listed-relay", netWorkAddress = "Q1234",
        roomJoinType = RoomJoinType.SHORT,
    )

    @Test
    fun cancellingListSelectionThenConnectingDirectlyCannotReuseItsSyncKeysOrRelay() {
        for (address in listOf("192.168.1.2:5123", "R5678", "t.mxy.wang/9876", "Qnews", "Rmods")) {
            val context = MultiplayerRoomJoinContext()
            context.selectedRoom = listedRoom
            context.dismissSelection()
            context.beginConnection()
            val request = context.snapshotConnection(address)
            assertNull(request.room?.joinRelayUuid(), address)
            assertEquals(forAddress(address), request.room?.let(::forRoomDescription) ?: forAddress(request.address), address)
        }
    }

    @Test
    fun failedListConnectionThenDirectOrHostRequestClearsBothRoomContexts() {
        val context = MultiplayerRoomJoinContext()
        context.selectedRoom = listedRoom
        context.beginConnection(listedRoom)
        // 列表连接在预同步/地址校验时失败，尚未抵达旧代码清空描述的位置。
        context.beginConnection()
        assertNull(context.selectedRoom)
        assertNull(context.snapshotConnection("Qnews").room)
    }

    @Test
    fun delayedSelectionDismissDoesNotEraseAListedConnectionInProgress() {
        val context = MultiplayerRoomJoinContext()
        context.selectedRoom = listedRoom
        context.beginConnection(listedRoom)
        val request = context.snapshotConnection(listedRoom.addressProvider())
        context.dismissSelection()
        assertNull(context.selectedRoom)
        assertSame(listedRoom, context.snapshotConnection(listedRoom.addressProvider()).room)
        assertEquals("listed-relay", request.room?.joinRelayUuid())
        assertEquals(listOf("sid:listed-server", "code:Q1234"), forRoomDescription(request.room!!))
    }

    @Test
    fun replacingThePendingRequestCannotRewriteTheAddressOrRoomAlreadyCaptured() {
        val context = MultiplayerRoomJoinContext()
        context.beginConnection(listedRoom)
        val runningRequest = context.snapshotConnection(listedRoom.addressProvider())
        context.beginConnection()
        val nextRequest = context.snapshotConnection("example.com:5123")
        context.dismissSelection()
        assertEquals("Q1234", runningRequest.address)
        assertSame(listedRoom, runningRequest.room)
        assertEquals("example.com:5123", nextRequest.address)
        assertNull(nextRequest.room)
    }
}
