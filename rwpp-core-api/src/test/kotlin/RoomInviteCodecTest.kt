/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 房间邀请编解码测试：锚定前缀格式、round-trip、非法输入与 TTL 边界。
 */
class RoomInviteCodecTest {

    private val invite = RoomInvite(
        address = "1.2.3.4:5123",
        code = "Q123",
        inviter = "房主",
        map = "荒漠对决",
        players = "3/10",
        mods = 2,
        version = "v1.6.4",
        invitedAt = 1_000_000L,
    )

    @Test
    fun encodeStartsWithPrefix() {
        assertTrue(RoomInviteCodec.encode(invite).startsWith(ROOM_INVITE_PREFIX))
    }

    @Test
    fun roundTrip() {
        assertEquals(invite, RoomInviteCodec.decode(RoomInviteCodec.encode(invite)))
    }

    @Test
    fun roundTripWithoutCode() {
        val noCode = invite.copy(code = null)
        assertEquals(noCode, RoomInviteCodec.decode(RoomInviteCodec.encode(noCode)))
    }

    @Test
    fun decodeRejectsPlainText() {
        assertNull(RoomInviteCodec.decode("今晚开黑吗"))
        assertNull(RoomInviteCodec.decode(""))
    }

    @Test
    fun decodeRejectsBrokenJson() {
        assertNull(RoomInviteCodec.decode("$ROOM_INVITE_PREFIX{not json"))
    }

    @Test
    fun decodeRejectsBlankAddress() {
        assertNull(RoomInviteCodec.decode(RoomInviteCodec.encode(invite.copy(address = ""))))
    }

    @Test
    fun expiryBoundary() {
        val t0 = 10_000_000L
        val inv = invite.copy(invitedAt = t0)
        assertFalse(RoomInviteCodec.isExpired(inv, t0 + ROOM_INVITE_TTL_MS - 60_000L))
        assertTrue(RoomInviteCodec.isExpired(inv, t0 + ROOM_INVITE_TTL_MS + 60_000L))
        // invitedAt 缺失视为过期
        assertTrue(RoomInviteCodec.isExpired(invite.copy(invitedAt = 0), t0))
    }

    @Test
    fun plausibleJoinAddressAcceptsCodesAndHosts() {
        assertTrue(isPlausibleJoinAddress("Q34091"))
        assertTrue(isPlausibleJoinAddress("R12345"))
        assertTrue(isPlausibleJoinAddress("q123"))
        assertTrue(isPlausibleJoinAddress("1.2.3.4:5123"))
        assertTrue(isPlausibleJoinAddress("example.com"))
        assertTrue(isPlausibleJoinAddress("::1"))
    }

    @Test
    fun plausibleJoinAddressRejectsQuickHostCommands() {
        assertFalse(isPlausibleJoinAddress(""))
        assertFalse(isPlausibleJoinAddress("Qnews"))
        assertFalse(isPlausibleJoinAddress("Qmods"))
        assertFalse(isPlausibleJoinAddress("QC6666"))
        assertFalse(isPlausibleJoinAddress("QnewsP20U3000C5000Z5"))
        assertFalse(isPlausibleJoinAddress("localhost"))
    }
}
