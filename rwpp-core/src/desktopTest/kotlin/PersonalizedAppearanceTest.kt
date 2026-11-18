/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.theme

import io.github.rwpp.net.appearance.VerifiedAppearanceMember
import io.github.rwpp.net.appearance.VerifiedAppearanceRoster
import io.github.rwpp.net.appearance.VerifiedIdentityChallenge
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalizedAppearanceTest {
    private val identity = VerifiedIdentityChallenge("official", "room-A", "self", "a".repeat(32), 220)
    private val member = VerifiedAppearanceMember("peer", 2, "Player", "123", 220)
    private val roster = VerifiedAppearanceRoster("official", "room-A", "self", 5, 115, listOf(member))
    private fun valid(value: VerifiedAppearanceRoster) =
        PersonalizedAppearanceController.validRoster(value, identity, 5, 100)

    @Test fun onlyCurrentServerRoomObserverAndRevisionAreAccepted() {
        assertTrue(valid(roster))
        assertFalse(valid(roster.copy(serverId = "another-server")))
        assertFalse(valid(roster.copy(roomInstanceId = "room-B")))
        assertFalse(valid(roster.copy(connectionId = "another-observer")))
        assertFalse(valid(roster.copy(revision = 4)))
    }

    @Test fun ambiguousAccountsSlotsAndConnectionsNeverReceivePersonalizedContent() {
        assertFalse(valid(roster.copy(members = listOf(member, member.copy(connectionId = "new", accountId = "456")))))
        assertFalse(valid(roster.copy(members = listOf(member, member.copy(slot = 3, accountId = "456")))))
        assertFalse(valid(roster.copy(members = listOf(member, member.copy(slot = 3, connectionId = "new")))))
        assertFalse(valid(roster.copy(members = listOf(member.copy(accountId = "0123")))))
        assertFalse(valid(roster.copy(members = listOf(member.copy(slot = -1)))))
    }

    @Test fun expiredOrExcessivelyLongProofsFailClosed() {
        assertFalse(valid(roster.copy(expiresAt = 100)))
        assertFalse(valid(roster.copy(expiresAt = 121)))
        assertFalse(valid(roster.copy(members = listOf(member.copy(expiresAt = 100)))))
        assertFalse(valid(roster.copy(members = listOf(member.copy(expiresAt = 226)))))
        assertTrue(valid(roster.copy(members = emptyList())))
    }
}
