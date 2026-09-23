/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.roomid

import io.github.rwpp.net.RoomDescription
import io.github.rwpp.net.RoomJoinType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomIdentityKeysTest {

    @Test
    fun shortCodeIsNormalizedToUpperCase() {
        assertEquals(listOf("code:Q77182"), identityKeysForAddress("Q77182"))
        assertEquals(listOf("code:Q77182"), identityKeysForAddress("q77182"))
        assertEquals(listOf("code:R1024"), identityKeysForAddress("r1024"))
        assertEquals(listOf("code:Q77182"), identityKeysForAddress("  Q77182  "))
    }

    @Test
    fun ipv4AddressGetsAddrKeyWithPort() {
        assertEquals(listOf("addr:1.2.3.4:5123"), identityKeysForAddress("1.2.3.4:5123"))
        assertEquals(listOf("addr:1.2.3.4:11451"), identityKeysForAddress("1.2.3.4:11451"))
    }

    @Test
    fun addressWithoutPortGetsDefaultPort() {
        assertEquals(listOf("addr:1.2.3.4:5123"), identityKeysForAddress("1.2.3.4"))
        assertEquals(listOf("addr:example.com:5123"), identityKeysForAddress("example.com"))
    }

    @Test
    fun addrKeyIsNormalized() {
        // host 小写、去空白
        assertEquals(listOf("addr:example.com:5123"), identityKeysForAddress("  EXAMPLE.COM "))
        assertEquals(listOf("addr:example.com:5123"), identityKeysForAddress("Example.Com:5123"))
    }

    @Test
    fun domainAddressGetsAddrKey() {
        assertEquals(listOf("addr:game.example.org:7777"), identityKeysForAddress("game.example.org:7777"))
    }

    @Test
    fun quickHostCommandsYieldNoKey() {
        // 快速建房指令串不是可加入地址，不产出 key
        assertTrue(identityKeysForAddress("Qnews").isEmpty())
        assertTrue(identityKeysForAddress("Qmods").isEmpty())
        assertTrue(identityKeysForAddress("QC6666").isEmpty())
        assertTrue(identityKeysForAddress("QnewsP20U3000C5000Z5").isEmpty())
    }

    @Test
    fun blankAndGarbageYieldNoKey() {
        assertTrue(identityKeysForAddress("").isEmpty())
        assertTrue(identityKeysForAddress("   ").isEmpty())
        assertTrue(identityKeysForAddress("localhost").isEmpty())
    }

    @Test
    fun roomDescriptionDelegatesToModSyncKeys() {
        val desc = RoomDescription(
            uuid = "e72d379e-b489-480e-b356-cb3251c0c2b6",
            roomJoinType = RoomJoinType.SHORT,
            netWorkAddress = "Q77182",
        )
        assertEquals(
            listOf("sid:e72d379e-b489-480e-b356-cb3251c0c2b6", "code:Q77182"),
            identityKeysForRoomDescription(desc)
        )
    }

    @Test
    fun prioritizeTruncatesToEightBySidCodeAddrOrder() {
        val keys = buildList {
            repeat(5) { add("addr:10.0.0.$it:5123") }
            repeat(4) { add("code:Q100$it") }
            repeat(2) { add("sid:sid-$it") }
        }
        val prioritized = prioritizeIdentityKeys(keys)
        assertEquals(MAX_IDENTITY_KEYS, prioritized.size)
        // sid > code > addr：2 个 sid 全保留，4 个 code 全保留，addr 只留前 2 个
        assertEquals(
            listOf(
                "sid:sid-0", "sid:sid-1",
                "code:Q1000", "code:Q1001", "code:Q1002", "code:Q1003",
                "addr:10.0.0.0:5123", "addr:10.0.0.1:5123",
            ),
            prioritized,
        )
    }

    @Test
    fun prioritizeDeduplicatesKeys() {
        assertEquals(
            listOf("code:Q77182"),
            prioritizeIdentityKeys(listOf("code:Q77182", "code:Q77182")),
        )
    }
}
