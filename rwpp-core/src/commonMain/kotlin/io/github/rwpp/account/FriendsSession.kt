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
import io.github.rwpp.net.account.ChatItem
import io.github.rwpp.net.account.ChatMessageDto
import io.github.rwpp.net.account.FriendItem
import io.github.rwpp.net.account.FriendRequestBox
import io.github.rwpp.net.account.FriendRequestDto
import io.github.rwpp.net.account.PublicUser
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

    suspend fun refreshLists() {
        if (!AccountSession.networkEnabled || !AccountSession.loggedIn) return
        loadingLists = true
        try {
            val token = AccountSession.requireToken()
            val api = AccountSession.client()
            val (f, inn, out, c) = withContext(Dispatchers.IO) {
                val friends = api.listFriends(token)
                val incoming = api.listFriendRequests(token, FriendRequestBox.INCOMING)
                val outgoing = api.listFriendRequests(token, FriendRequestBox.OUTGOING)
                val chats = api.listChats(token)
                Quadruple(friends, incoming, outgoing, chats)
            }
            friends.clear()
            friends.addAll(f)
            incoming.clear()
            incoming.addAll(inn)
            outgoing.clear()
            outgoing.addAll(out)
            chats.clear()
            chats.addAll(c)
            listError = ""
            val peer = activePeer
            if (peer != null && activeConversationId == null) {
                activeConversationId = c.firstOrNull { it.peer.id == peer.id }?.id
            }
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
        closeChat()
        listError = ""
        loadingLists = false
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

private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
