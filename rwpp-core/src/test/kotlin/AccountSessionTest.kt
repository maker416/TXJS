/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.net.account.ChatMessageDto
import io.github.rwpp.net.account.FriendItem
import io.github.rwpp.net.account.PublicUser
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountSessionTest {

    @AfterTest
    fun reset() {
        FriendsSession.clear()
        AccountSession.resetForTests()
    }

    @Test
    fun previewLoginUsesNicknameAsDisplayName() {
        AccountSession.applyPreview(
            AccountUser(
                id = 7,
                username = "xiuyu",
                email = "x@example.com",
                nickname = "修玉",
                createdAt = "2026-09-19T00:00:00Z",
            ),
        )
        assertTrue(AccountSession.loggedIn)
        assertEquals("xiuyu", AccountSession.username)
        assertEquals("修玉", AccountSession.displayName)
        assertEquals("xiuyu", AccountSession.lastUsername)
        assertFalse(AccountSession.networkEnabled)
    }

    @Test
    fun logoutClearsSessionAndKeepsLastUsername() {
        AccountSession.applyPreview(
            AccountUser(id = 1, username = "xiuyu", nickname = "修玉", createdAt = "t"),
        )
        kotlinx.coroutines.runBlocking { AccountSession.logout() }
        assertFalse(AccountSession.loggedIn)
        assertEquals("", AccountSession.username)
        assertEquals("xiuyu", AccountSession.lastUsername)
    }

    @Test
    fun friendsPreviewHasNoPresetBots() {
        val peer = PublicUser(2, "bob", "Bob")
        FriendsSession.applyPreview(
            previewFriends = listOf(FriendItem(peer, "2026-09-14T23:01:00+08:00")),
            previewMessages = listOf(
                ChatMessageDto(20, 5, 2, "hello", "2026-09-14T23:02:00+08:00"),
            ),
            peer = peer,
            conversationId = 5,
        )
        assertEquals(1, FriendsSession.friends.size)
        assertEquals("bob", FriendsSession.friends.first().user.username)
        assertFalse(FriendsSession.friends.any { it.user.username == "钢铁指挥官" })
        assertEquals("hello", FriendsSession.messages.last().body)
    }
}
