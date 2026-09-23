/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rwpp.logger
import io.github.rwpp.net.account.AccountApiException
import io.github.rwpp.net.account.BlockItem
import io.github.rwpp.net.account.ChatItem
import io.github.rwpp.net.account.ChatMessageDto
import io.github.rwpp.net.account.FriendItem
import io.github.rwpp.net.account.FriendRequestBox
import io.github.rwpp.net.account.FriendRequestDto
import io.github.rwpp.net.account.PublicUser
import io.github.rwpp.net.account.RoomInviteCodec
import io.github.rwpp.ui.RoomInviteNotification
import io.github.rwpp.ui.UI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 好友申请 / 列表 / 一对一聊天，对接文档中的 friends / chats 接口。
 * 只调用文档中的 friends / chats / lookup 路径，用轮询拉新消息。
 */
object FriendsSession {
    val friends = mutableStateListOf<FriendItem>()
    val incoming = mutableStateListOf<FriendRequestDto>()
    val outgoing = mutableStateListOf<FriendRequestDto>()
    val chats = mutableStateListOf<ChatItem>()
    val messages = mutableStateListOf<ChatMessageDto>()
    val blocks = mutableStateListOf<BlockItem>()

    var listError: String by mutableStateOf("")
        private set

    var chatError: String by mutableStateOf("")
        private set

    var loadingLists: Boolean by mutableStateOf(false)
        private set

    var activePeer: PublicUser? by mutableStateOf(null)
        private set

    var activeConversationId: Long? by mutableStateOf(null)
        private set

    fun lastMessagePreview(userId: Long): String? {
        return chats.firstOrNull { it.peer.id == userId }?.lastMessage?.body
    }

    fun unreadOf(userId: Long): Int {
        return chats.firstOrNull { it.peer.id == userId }?.unread ?: 0
    }

    /** 好友的在线状态（文档 6.16 随列表下发）；不在好友列表（或非好友）返回 null。 */
    fun presenceOf(userId: Long): FriendItem? {
        return friends.firstOrNull { it.user.id == userId }
    }

    suspend fun refreshLists() {
        if (!AccountSession.networkEnabled || !AccountSession.loggedIn) return
        loadingLists = true
        try {
            val token = AccountSession.requireToken()
            val api = AccountSession.client()
            val (f, inn, out, c, b) = withContext(Dispatchers.IO) {
                val friends = api.listFriends(token)
                val incoming = api.listFriendRequests(token, FriendRequestBox.INCOMING)
                val outgoing = api.listFriendRequests(token, FriendRequestBox.OUTGOING)
                val chats = api.listChats(token)
                // 旧版本服务端没有 blocks 端点：失败不拖垮整个列表刷新
                val blocks = runCatching { api.listBlocks(token) }.getOrDefault(emptyList())
                Quintuple(friends, incoming, outgoing, chats, blocks)
            }
            friends.clear()
            friends.addAll(f)
            incoming.clear()
            incoming.addAll(inn)
            outgoing.clear()
            outgoing.addAll(out)
            chats.clear()
            chats.addAll(c)
            blocks.clear()
            blocks.addAll(b)
            listError = ""
            val peer = activePeer
            if (peer != null && activeConversationId == null) {
                activeConversationId = c.firstOrNull { it.peer.id == peer.id }?.id
            }
            maybeNotifyRoomInvite(c)
        } catch (e: AccountApiException) {
            listError = accountErrorText(e)
            if (e.code == io.github.rwpp.net.account.AccountErrorCode.UNAUTHORIZED) {
                logger.warn("好友列表鉴权失败")
            }
        } catch (e: Exception) {
            listError = e.message.orEmpty()
            logger.warn("刷新好友失败：{}", e.message)
        } finally {
            loadingLists = false
        }
    }

    suspend fun sendRequest(username: String): FriendRequestDto {
        val token = AccountSession.requireToken()
        val req = withContext(Dispatchers.IO) {
            AccountSession.client().sendFriendRequest(token, username)
        }
        refreshLists()
        return req
    }

    suspend fun accept(id: Long) {
        val token = AccountSession.requireToken()
        withContext(Dispatchers.IO) { AccountSession.client().acceptFriendRequest(token, id) }
        refreshLists()
    }

    suspend fun reject(id: Long) {
        val token = AccountSession.requireToken()
        withContext(Dispatchers.IO) { AccountSession.client().rejectFriendRequest(token, id) }
        refreshLists()
    }

    suspend fun cancel(id: Long) {
        val token = AccountSession.requireToken()
        withContext(Dispatchers.IO) { AccountSession.client().cancelFriendRequest(token, id) }
        refreshLists()
    }

    suspend fun deleteFriend(userId: Long) {
        val token = AccountSession.requireToken()
        withContext(Dispatchers.IO) { AccountSession.client().deleteFriend(token, userId) }
        if (activePeer?.id == userId) {
            closeChat()
        }
        refreshLists()
    }

    /** 拉黑（文档 6.17）：服务端会删除双方好友关系与互申记录。 */
    suspend fun block(userId: Long) {
        val token = AccountSession.requireToken()
        withContext(Dispatchers.IO) { AccountSession.client().block(token, userId) }
        if (activePeer?.id == userId) {
            closeChat()
        }
        refreshLists()
    }

    suspend fun unblock(userId: Long) {
        val token = AccountSession.requireToken()
        withContext(Dispatchers.IO) { AccountSession.client().unblock(token, userId) }
        refreshLists()
    }

    suspend fun openChat(peer: PublicUser) {
        activePeer = peer
        chatError = ""
        val existing = chats.firstOrNull { it.peer.id == peer.id }
        activeConversationId = existing?.id
        messages.clear()
        if (existing != null) {
            loadLatestMessages(existing.id)
        }
    }

    fun closeChat() {
        activePeer = null
        activeConversationId = null
        messages.clear()
        chatError = ""
    }

    suspend fun send(body: String): Boolean {
        val peer = activePeer ?: return false
        val token = AccountSession.requireToken()
        return try {
            val msg = withContext(Dispatchers.IO) {
                AccountSession.client().sendMessage(token, peer.id, body)
            }
            activeConversationId = msg.conversationId
            if (messages.none { it.id == msg.id }) {
                messages.add(msg)
            }
            chatError = ""
            markReadIfNeeded()
            true
        } catch (e: AccountApiException) {
            chatError = accountErrorText(e)
            false
        }
    }

    /** 向任意好友发送私信（不依赖当前打开的会话，不影响 [messages] 与 [chatError]），供房间邀请等带外消息使用。 */
    suspend fun sendTo(peerId: Long, body: String): Boolean {
        val token = AccountSession.requireToken()
        return try {
            withContext(Dispatchers.IO) {
                AccountSession.client().sendMessage(token, peerId, body)
            }
            true
        } catch (e: Exception) {
            // 不写 chatError：与当前打开的会话无关，错误由调用方自行呈现
            logger.warn("发送私信失败：{}", e.message)
            false
        }
    }

    suspend fun pollMessages() {
        val conversationId = activeConversationId ?: return
        if (!AccountSession.networkEnabled || !AccountSession.loggedIn) return
        try {
            val token = AccountSession.requireToken()
            val after = messages.maxOfOrNull { it.id }
            val page = withContext(Dispatchers.IO) {
                AccountSession.client().listMessages(token, conversationId, afterId = after, pageSize = 50)
            }
            var added = false
            page.messages.forEach { msg ->
                if (messages.none { it.id == msg.id }) {
                    messages.add(msg)
                    added = true
                }
            }
            if (added) {
                messages.sortBy { it.id }
                markReadIfNeeded()
            }
            chatError = ""
        } catch (e: AccountApiException) {
            chatError = accountErrorText(e)
        } catch (e: Exception) {
            logger.warn("拉聊天失败：{}", e.message)
        }
    }

    fun applyPreview(
        previewFriends: List<FriendItem>,
        previewIncoming: List<FriendRequestDto> = emptyList(),
        previewOutgoing: List<FriendRequestDto> = emptyList(),
        previewChats: List<ChatItem> = emptyList(),
        previewMessages: List<ChatMessageDto> = emptyList(),
        previewBlocks: List<BlockItem> = emptyList(),
        peer: PublicUser? = null,
        conversationId: Long? = null,
    ) {
        AccountSession.networkEnabled = false
        friends.clear()
        friends.addAll(previewFriends)
        incoming.clear()
        incoming.addAll(previewIncoming)
        outgoing.clear()
        outgoing.addAll(previewOutgoing)
        chats.clear()
        chats.addAll(previewChats)
        blocks.clear()
        blocks.addAll(previewBlocks)
        messages.clear()
        messages.addAll(previewMessages)
        activePeer = peer
        activeConversationId = conversationId
        listError = ""
        chatError = ""
    }

    fun clear() {
        friends.clear()
        incoming.clear()
        outgoing.clear()
        chats.clear()
        blocks.clear()
        closeChat()
        listError = ""
        loadingLists = false
        inviteToastSeen.clear()
        // 纯单元测试环境没有初始化 appKoin，触碰 UI 会抛 ExceptionInInitializerError
        runCatching { UI.incomingInviteNotification = null }
    }

    /** 每个会话已弹过悬浮卡片的邀请消息 id（peerId -> messageId），避免轮询重复弹。 */
    private val inviteToastSeen = mutableMapOf<Long, Long>()

    /**
     * 房间邀请悬浮通知：从未读私信里识别 `[RWJSINV1]` 邀请并置位 [UI.incomingInviteNotification]。
     *
     * 同一会话同一条消息只弹一次（含被抑制的情况：消息仍留在聊天流里，不重复打扰）；
     * 过期邀请、正在查看该会话、在房间/对局中时不弹。
     */
    private fun maybeNotifyRoomInvite(newChats: List<ChatItem>) {
        val selfId = AccountSession.user?.id ?: return
        newChats.forEach { chat ->
            val last = chat.lastMessage ?: return@forEach
            if (last.senderId == selfId || chat.unread <= 0) return@forEach
            if (inviteToastSeen[chat.peer.id] == last.id) return@forEach
            val invite = RoomInviteCodec.decode(last.body) ?: return@forEach
            inviteToastSeen[chat.peer.id] = last.id
            if (RoomInviteCodec.isExpired(invite, System.currentTimeMillis())) return@forEach
            if (UI.showRoomView) return@forEach
            if (UI.showFriendsView && activePeer?.id == chat.peer.id) return@forEach
            // 多个好友同时邀请时后到的覆盖先到的；被覆盖的邀请仍可在会话里点卡片加入
            UI.incomingInviteNotification = RoomInviteNotification(invite, chat.peer, last.id)
        }
    }

    private suspend fun loadLatestMessages(conversationId: Long) {
        val token = AccountSession.requireToken()
        val page = withContext(Dispatchers.IO) {
            AccountSession.client().listMessages(token, conversationId, pageSize = 50)
        }
        messages.clear()
        messages.addAll(page.messages.sortedBy { it.id })
        markReadIfNeeded()
    }

    private suspend fun markReadIfNeeded() {
        val conversationId = activeConversationId ?: return
        val last = messages.lastOrNull() ?: return
        val selfId = AccountSession.user?.id ?: return
        if (last.senderId == selfId) return
        runCatching {
            val token = AccountSession.requireToken()
            withContext(Dispatchers.IO) {
                AccountSession.client().markRead(token, conversationId, last.id)
            }
        }
    }
}

private data class Quintuple<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
