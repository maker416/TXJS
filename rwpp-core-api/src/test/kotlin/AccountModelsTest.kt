/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.account.AccountErrorBody
import io.github.rwpp.net.account.AccountUser
import io.github.rwpp.net.account.ChatItem
import io.github.rwpp.net.account.ChatMessageDto
import io.github.rwpp.net.account.FriendRequestDto
import io.github.rwpp.net.account.LoginRequest
import io.github.rwpp.net.account.LoginResponse
import io.github.rwpp.net.account.PublicUser
import io.github.rwpp.net.account.RegisterRequest
import io.github.rwpp.net.account.ResetPasswordRequest
import io.github.rwpp.net.account.SendChatRequest
import io.github.rwpp.net.account.SendEmailCodeRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 统一账号 DTO 与文档 snake_case 字段名锚定，防止与 Go 端漂移。
 */
class AccountModelsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun sendEmailCodeRequestUsesDocumentFields() {
        val encoded = json.encodeToString(SendEmailCodeRequest("Alice@Example.com", "register"))
        assertTrue(encoded.contains("\"email\""), encoded)
        assertTrue(encoded.contains("\"purpose\""), encoded)
        assertTrue(encoded.contains("register"), encoded)
    }

    @Test
    fun registerRequestOmitsBlankNickname() {
        val encoded = json.encodeToString(
            RegisterRequest("alice", "passw0rd", nickname = null, email = "a@b.com", code = "123456"),
        )
        assertTrue(encoded.contains("\"username\""), encoded)
        assertTrue(!encoded.contains("nickname"), encoded)
    }

    @Test
    fun resetPasswordUsesNewPasswordSnakeCase() {
        val encoded = json.encodeToString(ResetPasswordRequest("a@b.com", "123456", "newpass12"))
        assertTrue(encoded.contains("\"new_password\""), encoded)
        assertTrue(!encoded.contains("newPassword"), encoded)
    }

    @Test
    fun loginResponseDecodesWireUser() {
        val wire = """
            {
              "token": "jwt-token",
              "user": {
                "id": 1,
                "username": "alice",
                "email": "alice@example.com",
                "nickname": "Alice",
                "status": 1,
                "last_login_at": "2026-09-14T10:40:00+08:00",
                "nickname_changed_at": null,
                "created_at": "2026-09-01T00:00:00+08:00",
                "has_avatar": false
              }
            }
        """.trimIndent()
        val resp = json.decodeFromString<LoginResponse>(wire)
        assertEquals("jwt-token", resp.token)
        assertEquals(1, resp.user.id)
        assertEquals("alice", resp.user.username)
        assertEquals("Alice", resp.user.nickname)
        assertEquals("alice@example.com", resp.user.email)
        assertEquals(false, resp.user.hasAvatar)
        assertNull(resp.user.nicknameChangedAt)
    }

    @Test
    fun publicUserIgnoresUndocumentedFields() {
        val wire = """
            {"id":2,"username":"bob","nickname":"Bob","status":1,"has_avatar":true,"email":"secret@x.com"}
        """.trimIndent()
        val user = json.decodeFromString<PublicUser>(wire)
        assertEquals(2, user.id)
        assertEquals("bob", user.username)
        assertEquals(true, user.hasAvatar)
    }

    @Test
    fun friendRequestDecodesFromToUsers() {
        val wire = """
            {
              "id": 11,
              "from_user": {"id":1,"username":"alice","nickname":"Alice","status":1},
              "to_user": {"id":2,"username":"bob","nickname":"Bob","status":1},
              "status": "pending",
              "created_at": "2026-09-14T23:00:00+08:00",
              "updated_at": "2026-09-14T23:00:00+08:00"
            }
        """.trimIndent()
        val req = json.decodeFromString<FriendRequestDto>(wire)
        assertEquals(11, req.id)
        assertEquals("alice", req.fromUser.username)
        assertEquals("bob", req.toUser.username)
        assertEquals("pending", req.status)
    }

    @Test
    fun chatItemAllowsNullLastMessage() {
        val wire = """
            {
              "id": 5,
              "peer": {"id":2,"username":"bob","nickname":"Bob","status":1},
              "last_message": null,
              "unread": 0,
              "updated_at": null,
              "created_at": "2026-09-14T23:01:00+08:00"
            }
        """.trimIndent()
        val chat = json.decodeFromString<ChatItem>(wire)
        assertEquals(5, chat.id)
        assertNull(chat.lastMessage)
        assertNull(chat.updatedAt)
        assertEquals(0, chat.unread)
    }

    @Test
    fun sendChatRequestUsesUserIdSnakeCase() {
        val encoded = json.encodeToString(SendChatRequest(2, "hello"))
        assertTrue(encoded.contains("\"user_id\""), encoded)
        assertTrue(encoded.contains("\"body\""), encoded)
    }

    @Test
    fun chatMessageDecodesConversationId() {
        val msg = json.decodeFromString<ChatMessageDto>(
            """{"id":20,"conversation_id":5,"sender_id":1,"body":"hello","created_at":"2026-09-14T23:02:00+08:00"}""",
        )
        assertEquals(20, msg.id)
        assertEquals(5, msg.conversationId)
        assertEquals(1, msg.senderId)
    }

    @Test
    fun errorBodyDecodesCodeAndMessage() {
        val err = json.decodeFromString<AccountErrorBody>(
            """{"code":"username_taken","message":"request is invalid"}""",
        )
        assertEquals("username_taken", err.code)
        assertEquals("request is invalid", err.message)
    }

    @Test
    fun accountUserRoundTripKeepsSnakeCase() {
        val user = AccountUser(
            id = 1,
            username = "alice",
            email = null,
            nickname = "Alice",
            createdAt = "2026-09-01T00:00:00+08:00",
        )
        val encoded = json.encodeToString(user)
        assertTrue(encoded.contains("\"has_avatar\"") || encoded.contains("\"created_at\""), encoded)
        assertEquals(user, json.decodeFromString<AccountUser>(encoded))
    }

    @Test
    fun loginRequestOnlyHasUsernamePassword() {
        val encoded = json.encodeToString(LoginRequest("alice", "passw0rd"))
        assertTrue(encoded.contains("\"username\""), encoded)
        assertTrue(encoded.contains("\"password\""), encoded)
        assertTrue(!encoded.contains("email"), encoded)
    }
}
