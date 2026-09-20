/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.account

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 完整账号资料。对应文档 4.1 `user`。 */
@Serializable
data class AccountUser(
    val id: Long,
    val username: String,
    val email: String? = null,
    val nickname: String,
    val status: Int = 1,
    @SerialName("last_login_at") val lastLoginAt: String? = null,
    @SerialName("nickname_changed_at") val nicknameChangedAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("has_avatar") val hasAvatar: Boolean = false,
)

/** 社交公开资料。对应文档 4.2 `public_user`。 */
@Serializable
data class PublicUser(
    val id: Long,
    val username: String,
    val nickname: String,
    val status: Int = 1,
    @SerialName("has_avatar") val hasAvatar: Boolean = false,
)

@Serializable
data class SendEmailCodeRequest(
    val email: String,
    val purpose: String,
)

@Serializable
data class OkResponse(
    val ok: Boolean = true,
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val nickname: String? = null,
    val email: String,
    val code: String,
)

@Serializable
data class UserResponse(
    val user: AccountUser,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val token: String,
    val user: AccountUser,
)

@Serializable
data class ResetPasswordRequest(
    val email: String,
    val code: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class ChangeNicknameRequest(
    val nickname: String,
)

@Serializable
data class FriendRequestBody(
    val username: String,
)

@Serializable
data class FriendRequestDto(
    val id: Long,
    @SerialName("from_user") val fromUser: PublicUser,
    @SerialName("to_user") val toUser: PublicUser,
    val status: String,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class FriendRequestResponse(
    val request: FriendRequestDto,
)

@Serializable
data class FriendRequestListResponse(
    val requests: List<FriendRequestDto> = emptyList(),
)

@Serializable
data class FriendItem(
    val user: PublicUser,
    val since: String = "",
)

@Serializable
data class FriendsResponse(
    val friends: List<FriendItem> = emptyList(),
)

@Serializable
data class LookupUserResponse(
    val user: PublicUser,
)

@Serializable
data class ChatMessageDto(
    val id: Long,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("sender_id") val senderId: Long,
    val body: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ChatItem(
    val id: Long,
    val peer: PublicUser,
    @SerialName("last_message") val lastMessage: ChatMessageDto? = null,
    val unread: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class ChatsResponse(
    val chats: List<ChatItem> = emptyList(),
)

@Serializable
data class SendChatRequest(
    @SerialName("user_id") val userId: Long,
    val body: String,
)

@Serializable
data class ChatMessageResponse(
    val message: ChatMessageDto,
)

@Serializable
data class ChatMessagesResponse(
    val messages: List<ChatMessageDto> = emptyList(),
    @SerialName("page_size") val pageSize: Int = 20,
)

@Serializable
data class MarkChatReadRequest(
    @SerialName("last_message_id") val lastMessageId: Long,
)

@Serializable
data class AccountErrorBody(
    val code: String = "",
    val message: String = "",
)

object EmailCodePurpose {
    const val REGISTER = "register"
    const val RESET_PASSWORD = "reset_password"
}

object FriendRequestBox {
    const val INCOMING = "incoming"
    const val OUTGOING = "outgoing"
}

object FriendRequestStatus {
    const val PENDING = "pending"
    const val ACCEPTED = "accepted"
    const val REJECTED = "rejected"
    const val CANCELLED = "cancelled"
}

object AccountErrorCode {
    const val UNAUTHORIZED = "unauthorized"
    const val RATE_LIMITED = "rate_limited"
    const val APP_DISABLED = "app_disabled"
    const val USER_DISABLED = "user_disabled"
    const val FEATURE_DISABLED = "feature_disabled"
    const val ALREADY_FRIENDS = "already_friends"
    const val REQUEST_PENDING = "request_pending"
    const val BLOCKED = "blocked"
    const val NOT_FRIENDS = "not_friends"
    const val INVALID_REQUEST = "invalid_request"
    const val INVALID_CODE = "invalid_code"
    const val NICKNAME_UNCHANGED = "nickname_unchanged"
    const val NOT_FOUND = "not_found"
    const val USERNAME_TAKEN = "username_taken"
    const val EMAIL_TAKEN = "email_taken"
    const val CODE_TOO_FREQUENT = "code_too_frequent"
    const val NICKNAME_CHANGE_TOO_FREQUENT = "nickname_change_too_frequent"
    const val MAIL_NOT_CONFIGURED = "mail_not_configured"
    const val MAIL_SEND_FAILED = "mail_send_failed"
    const val INTERNAL_ERROR = "internal_error"
}
