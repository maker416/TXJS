/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 房间邀请策略控制消息的识别与应用测试。
 */
class RoomInvitePolicyTest {

    @AfterTest
    fun tearDown() = RoomInvitePolicy.reset()

    @Test
    fun normalChatIsNotConsumed() {
        assertFalse(RoomInvitePolicy.handleControlMessage("今晚开黑吗"))
        assertFalse(RoomInvitePolicy.handleControlMessage(""))
        assertTrue(RoomInvitePolicy.membersCanInvite)
    }

    @Test
    fun controlMessageAppliesAndIsConsumed() {
        assertTrue(RoomInvitePolicy.handleControlMessage(RoomInvitePolicy.controlMessage(false)))
        assertFalse(RoomInvitePolicy.membersCanInvite)

        assertTrue(RoomInvitePolicy.handleControlMessage(RoomInvitePolicy.controlMessage(true)))
        assertTrue(RoomInvitePolicy.membersCanInvite)
    }

    @Test
    fun unknownControlPayloadStillConsumedButKeepsState() {
        assertTrue(RoomInvitePolicy.handleControlMessage("$INVITE_POLICY_CONTROL_PREFIX something-else"))
        assertTrue(RoomInvitePolicy.membersCanInvite)
    }

    @Test
    fun resetRestoresDefault() {
        RoomInvitePolicy.handleControlMessage(RoomInvitePolicy.controlMessage(false))
        RoomInvitePolicy.reset()
        assertTrue(RoomInvitePolicy.membersCanInvite)
    }
}
