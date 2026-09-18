/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * RWJS 好友与一对一聊天的进程内假数据。
 *
 * 只服务账号主页上的好友列表 / 添加 / 聊天 UI，**不**发网络请求，
 * **不**接 BBS / rtsbox，也**不**编造 userId / token / URL。
 * 进程重启后回到预设；添加好友与发消息只改内存。
 */
data class FakeFriend(
    val identifier: String,
    val displayName: String,
)

data class FakeChatMessage(
    val fromMe: Boolean,
    val text: String,
    val timeLabel: String,
)

enum class AddFriendResult {
    Ok,
    Blank,
    Duplicate,
    Self,
}

object FakeFriendsSession {
    val friends = mutableStateListOf<FakeFriend>()

    private val messagesByFriend = mutableStateMapOf<String, SnapshotStateList<FakeChatMessage>>()

    var activeChatIdentifier: String? by mutableStateOf(null)
        private set

    init {
        seedPresets()
    }

    fun messagesOf(identifier: String): SnapshotStateList<FakeChatMessage> {
        val key = identifier.trim()
        return messagesByFriend.getOrPut(key) { mutableStateListOf() }
    }

    fun lastMessagePreview(identifier: String): String? {
        return messagesOf(identifier).lastOrNull()?.text
    }

    fun activeFriend(): FakeFriend? {
        val id = activeChatIdentifier ?: return null
        return friends.firstOrNull { it.identifier.equals(id, ignoreCase = true) }
    }

    fun addFriend(raw: String): AddFriendResult {
        val id = raw.trim()
        if (id.isEmpty()) return AddFriendResult.Blank
        if (FakeAccountSession.loggedIn &&
            FakeAccountSession.identifier.equals(id, ignoreCase = true)
        ) {
            return AddFriendResult.Self
        }
        if (friends.any { it.identifier.equals(id, ignoreCase = true) }) {
            return AddFriendResult.Duplicate
        }
        friends.add(FakeFriend(identifier = id, displayName = id))
        messagesByFriend[id] = mutableStateListOf()
        return AddFriendResult.Ok
    }

    fun sendMessage(text: String): Boolean {
        val id = activeChatIdentifier ?: return false
        val body = text.trim()
        if (body.isEmpty()) return false
        messagesOf(id).add(
            FakeChatMessage(fromMe = true, text = body, timeLabel = "刚刚"),
        )
        return true
    }

    fun openChat(identifier: String) {
        activeChatIdentifier = identifier.trim()
    }

    fun closeChat() {
        activeChatIdentifier = null
    }

    /** 测试 / 截图：回到预设好友与聊天。 */
    fun resetToPresets() {
        closeChat()
        friends.clear()
        messagesByFriend.clear()
        seedPresets()
    }

    private fun seedPresets() {
        val steel = FakeFriend("钢铁指挥官", "钢铁指挥官")
        val newbie = FakeFriend("萌新云", "萌新云")
        val mapper = FakeFriend("地图作者", "地图作者")
        friends.addAll(listOf(steel, newbie, mapper))
        messagesByFriend[steel.identifier] = mutableStateListOf(
            FakeChatMessage(false, "今晚开把经典地图？", "昨天"),
            FakeChatMessage(true, "可以，我带海陆空。", "昨天"),
            FakeChatMessage(false, "那我先把房间开好。", "10:12"),
        )
        messagesByFriend[newbie.identifier] = mutableStateListOf(
            FakeChatMessage(false, "模组怎么同步？", "周一"),
            FakeChatMessage(true, "进房后会自动下，看名字旁边进度就行。", "周一"),
        )
        messagesByFriend[mapper.identifier] = mutableStateListOf(
            FakeChatMessage(true, "新图我玩过了，很有意思。", "刚刚"),
            FakeChatMessage(false, "谢谢，下版再加个桥。", "刚刚"),
        )
    }
}
