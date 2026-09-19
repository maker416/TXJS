/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.ui.AddFriendResult
import io.github.rwpp.ui.FakeAccountSession
import io.github.rwpp.ui.FakeFriendsSession
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeFriendsSessionTest {

    @BeforeTest
    fun setup() {
        FakeFriendsSession.resetToPresets()
        FakeAccountSession.applyLoggedOut()
    }

    @AfterTest
    fun tearDown() {
        FakeFriendsSession.resetToPresets()
        FakeAccountSession.applyLoggedOut()
    }

    @Test
    fun presetsExist() {
        assertEquals(3, FakeFriendsSession.friends.size)
        assertTrue(FakeFriendsSession.friends.any { it.identifier == "钢铁指挥官" })
        assertTrue(FakeFriendsSession.messagesOf("钢铁指挥官").isNotEmpty())
    }

    @Test
    fun addFriendSucceedsWhenNonEmptyAndNew() {
        val result = FakeFriendsSession.addFriend(" 修玉张 ")
        assertEquals(AddFriendResult.Ok, result)
        assertTrue(FakeFriendsSession.friends.any { it.identifier == "修玉张" })
        assertEquals("修玉张", FakeFriendsSession.friends.last().displayName)
        assertTrue(FakeFriendsSession.messagesOf("修玉张").isEmpty())
    }

    @Test
    fun addFriendRejectsBlankDuplicateAndSelf() {
        assertEquals(AddFriendResult.Blank, FakeFriendsSession.addFriend("  "))
        assertEquals(AddFriendResult.Duplicate, FakeFriendsSession.addFriend("萌新云"))
        FakeAccountSession.signIn("钢铁指挥官")
        assertEquals(AddFriendResult.Self, FakeFriendsSession.addFriend("钢铁指挥官"))
    }

    @Test
    fun sendMessageAppendsToActiveChat() {
        FakeFriendsSession.openChat("地图作者")
        assertTrue(FakeFriendsSession.sendMessage(" 收到 "))
        val last = FakeFriendsSession.messagesOf("地图作者").last()
        assertTrue(last.fromMe)
        assertEquals("收到", last.text)
        FakeFriendsSession.closeChat()
        assertFalse(FakeFriendsSession.sendMessage("还在吗"))
    }
}
