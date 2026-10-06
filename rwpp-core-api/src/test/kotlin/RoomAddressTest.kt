/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import io.github.rwpp.net.account.isPlausibleJoinAddress
import io.github.rwpp.net.roomid.identityKeysForAddress
import io.github.rwpp.net.sync.forAddress
import io.github.rwpp.net.sync.forRoomDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomAddressTest {
    @Test
    fun tRoomDetailsExposeTheDomainJoinAddress() {
        val details = "Room: T77182\nStarting Credits: 4000"
        assertEquals("t.mxy.wang/77182", extractRoomJoinAddress(details))
        assertEquals("Room: t.mxy.wang/77182\nStarting Credits: 4000", normalizeRoomAddressesInDetails(details))
        assertEquals("t.mxy.wang/77182", extractRoomJoinAddress("Room: t.mxy.wang/77182"))
        assertEquals("Q77182", extractRoomJoinAddress("Room: Q77182"))
        assertEquals("R77182", extractRoomJoinAddress("Room: R77182"))
        assertNull(extractRoomJoinAddress("QnewsP20 TmodsP10 t.mxy.wang/news"))
    }

    @Test
    fun hostAndJoinersShareATRoomKeyWithoutCollidingWithQAndR() {
        listOf("T77182", "t.mxy.wang/77182", "  T.MXY.WANG/77182  ", "t.mxy.wang:5123/77182").forEach {
            assertEquals("t.mxy.wang/77182", roomJoinAddressForId(it))
            assertEquals(listOf("code:T77182"), forAddress(it))
            assertEquals(listOf("code:T77182"), identityKeysForAddress(it))
            assertTrue(isPlausibleJoinAddress(it))
        }
        assertEquals(listOf("code:Q77182"), forAddress("Q77182"))
        assertEquals(listOf("code:R77182"), forAddress("R77182"))
    }

    @Test
    fun publishedTRoomRoundTripsWithoutAddingAPortToTheRoomNumber() {
        val publishedAddress = roomListPublishAddress("T77182")
        assertEquals("t.mxy.wang/77182", publishedAddress)
        val entry = RwListServerEntry("T room", publishedAddress, false, "map", "默认", 10, 1, "[]", "1", "server-id")
        listOf(publishedAddress, "t.mxy.wang:5123/77182", "t.mxy.wang/77182:5123").forEach { address ->
            val room = mapRwListEntryToRoomDescription(entry.copy(ip = address))
            assertEquals(RoomJoinType.SHORT, room.roomJoinType)
            assertEquals("t.mxy.wang/77182", room.addressProvider())
            assertEquals(listOf("sid:server-id", "code:T77182"), forRoomDescription(room))
        }
    }

    @Test
    fun tHostCommandsAreNeverOfferedAsJoinAddressesOrRoomKeys() {
        listOf("t.mxy.wang/news", "t.mxy.wang/modsP10", "t.mxy.wang/C6666", "t.mxy.wang/CM6666",
            "t.mxy.wang:5123/news", "t.mxy.wang/77182bad").forEach {
            assertNull(roomCodeForAddress(it))
            assertFalse(isPlausibleJoinAddress(it))
            assertTrue(forAddress(it).isEmpty())
            assertTrue(identityKeysForAddress(it).isEmpty())
        }
    }
}
