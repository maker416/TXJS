/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder

private const val API_PREFIX = "/api/v1"
private const val APP_KEY_HEADER = "X-App-Key"
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null, 0, 0)

/**
 * 统一账号 JSON API 客户端。只实现文档写出的 `/api/v1` 路径。
 *
 * 所有请求带 [APP_KEY_HEADER]；需要登录的接口再加 `Authorization: Bearer`。
 * **不**发送 `X-App-Secret`（增减积分才需要，且不得进客户端）。
 */
class AccountApiClient(
    baseUrl: String,
    private val appKey: String,
    private val http: OkHttpClient,
) {
    private val baseUrl: String = baseUrl.trim().trimEnd('/')

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    init {
        require(baseUrl.isNotBlank()) { "AccountApiClient 需要 baseUrl" }
        require(appKey.isNotBlank()) { "AccountApiClient 需要 AppKey" }
    }

    suspend fun sendEmailCode(email: String, purpose: String): OkResponse =
        postJson("/email/send-code", SendEmailCodeRequest(email, purpose))

    suspend fun register(req: RegisterRequest): AccountUser =
        postJson<RegisterRequest, UserResponse>("/users/register", req, expectedCode = 201).user

    suspend fun login(username: String, password: String): LoginResponse =
        postJson("/users/login", LoginRequest(username, password))

    suspend fun resetPassword(email: String, code: String, newPassword: String): OkResponse =
        postJson("/users/reset-password", ResetPasswordRequest(email, code, newPassword))

    suspend fun me(token: String): AccountUser =
        getJson<UserResponse>("/users/me", token).user

    suspend fun logout(token: String): OkResponse =
        postEmpty("/users/logout", token)

    suspend fun changeNickname(token: String, nickname: String): AccountUser =
        postJson<ChangeNicknameRequest, UserResponse>(
            "/users/change-nickname",
            ChangeNicknameRequest(nickname),
            token,
        ).user

    suspend fun lookup(token: String, username: String): PublicUser =
        getJson<LookupUserResponse>("/users/lookup?username=${enc(username)}", token).user

    suspend fun sendFriendRequest(token: String, username: String): FriendRequestDto =
        postJson<FriendRequestBody, FriendRequestResponse>(
            "/friends/requests",
            FriendRequestBody(username),
            token,
        ).request

    suspend fun listFriendRequests(token: String, box: String): List<FriendRequestDto> =
        getJson<FriendRequestListResponse>("/friends/requests?box=${enc(box)}", token).requests

    suspend fun acceptFriendRequest(token: String, id: Long): FriendRequestDto =
        requestJson<FriendRequestResponse>(
            method = "POST",
            path = "/friends/requests/$id/accept",
            token = token,
            body = EMPTY_BODY,
        ).request

    suspend fun rejectFriendRequest(token: String, id: Long): OkResponse =
        postEmpty("/friends/requests/$id/reject", token)

    suspend fun cancelFriendRequest(token: String, id: Long): OkResponse =
        postEmpty("/friends/requests/$id/cancel", token)

    suspend fun listFriends(token: String): List<FriendItem> =
        getJson<FriendsResponse>("/friends", token).friends

    suspend fun deleteFriend(token: String, userId: Long): OkResponse =
        requestJson(method = "DELETE", path = "/friends/$userId", token = token)

    suspend fun listChats(token: String): List<ChatItem> =
        getJson<ChatsResponse>("/chats", token).chats

    suspend fun sendMessage(token: String, userId: Long, body: String): ChatMessageDto =
        postJson<SendChatRequest, ChatMessageResponse>(
            "/chats/messages",
            SendChatRequest(userId, body),
            token,
        ).message

    suspend fun listMessages(
        token: String,
        conversationId: Long,
        afterId: Long? = null,
        beforeId: Long? = null,
        pageSize: Int? = null,
    ): ChatMessagesResponse {
        val q = buildString {
            append("/chats/").append(conversationId).append("/messages")
            val parts = mutableListOf<String>()
            if (afterId != null) parts += "after_id=$afterId"
            if (beforeId != null) parts += "before_id=$beforeId"
            if (pageSize != null) parts += "page_size=$pageSize"
            if (parts.isNotEmpty()) {
                append('?')
                append(parts.joinToString("&"))
            }
        }
        return getJson(q, token)
    }

    suspend fun markRead(token: String, conversationId: Long, lastMessageId: Long): OkResponse =
        postJson("/chats/$conversationId/read", MarkChatReadRequest(lastMessageId), token)

    private suspend inline fun <reified Req, reified Res> postJson(
        path: String,
        body: Req,
        token: String? = null,
        expectedCode: Int? = null,
    ): Res = requestJson(
        method = "POST",
        path = path,
        token = token,
        body = json.encodeToString(body).toRequestBody(JSON_MEDIA),
        expectedCode = expectedCode,
    )

    private suspend inline fun <reified Res> postJson(
        path: String,
        token: String,
        expectedCode: Int? = null,
    ): Res = requestJson(
        method = "POST",
        path = path,
        token = token,
        body = EMPTY_BODY,
        expectedCode = expectedCode,
    )

    private suspend inline fun <reified Res> postEmpty(
        path: String,
        token: String,
    ): Res = requestJson(method = "POST", path = path, token = token, body = EMPTY_BODY)

    private suspend inline fun <reified Res> getJson(
        path: String,
        token: String? = null,
    ): Res = requestJson(method = "GET", path = path, token = token)

    private suspend inline fun <reified Res> requestJson(
        method: String,
        path: String,
        token: String? = null,
        body: RequestBody? = null,
        expectedCode: Int? = null,
    ): Res = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(baseUrl + API_PREFIX + path)
            .header(APP_KEY_HEADER, appKey)
            .header("Accept", "application/json")
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> if (body != null) builder.delete(body) else builder.delete()
            else -> builder.method(method, body ?: EMPTY_BODY)
        }
        val request = builder.build()
        try {
            http.executeCancellable(request).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful || (expectedCode != null && response.code != expectedCode)) {
                    throw response.toAccountException(text)
                }
                if (Res::class == Unit::class) {
                    @Suppress("UNCHECKED_CAST")
                    return@use Unit as Res
                }
                if (text.isBlank() && Res::class == OkResponse::class) {
                    @Suppress("UNCHECKED_CAST")
                    return@use OkResponse(true) as Res
                }
                json.decodeFromString(text)
            }
        } catch (e: AccountApiException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw AccountApiException(AccountApiException.NETWORK, e.message ?: "network error", null)
        }
    }

    private fun Response.toAccountException(bodyText: String): AccountApiException {
        val parsed = runCatching { json.decodeFromString<AccountErrorBody>(bodyText) }.getOrNull()
        val retry = header("Retry-After")?.toIntOrNull()
        return AccountApiException(
            code = parsed?.code?.ifBlank { null } ?: AccountErrorCode.INTERNAL_ERROR,
            message = parsed?.message?.ifBlank { null } ?: "HTTP $code",
            statusCode = code,
            retryAfterSeconds = retry,
        )
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

private suspend fun OkHttpClient.executeCancellable(request: Request): Response {
    val call = newCall(request)
    val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
        if (cause is CancellationException) {
            call.cancel()
        }
    }
    return try {
        currentCoroutineContext().ensureActive()
        val response = call.execute()
        try {
            currentCoroutineContext().ensureActive()
            response
        } catch (e: Throwable) {
            response.close()
            throw e
        }
    } finally {
        cancelHandle.dispose()
    }
}
