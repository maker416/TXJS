/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.games

import io.github.rwpp.config.normalizeOpenPlatformUrl
import io.github.rwpp.net.useCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

class GamePortalException(val code: String, val httpStatus: Int? = null) : IOException(code)

/** JWTs are sent only to the configured official platform, never to the provider website. */
class GamePortalClient(baseUrl: String, private val appKey: String, http: OkHttpClient = OkHttpClient()) {
    private val base = normalizeOpenPlatformUrl(baseUrl)?.toHttpUrl()
        ?: throw GamePortalException("https_required")
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS).apply { if (!base.isHttps) proxy(Proxy.NO_PROXY) }.build()

    suspend fun player(token: String, appId: String, revision: Long): JsonObject {
        val data = request(token, appId, revision, null)
        val id = (data["account_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val nickname = (data["nickname"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (!id.matches(Regex("[1-9][0-9]{0,18}")) || nickname.length > 256) invalid()
        return buildJsonObject { put("account_id", id); put("nickname", nickname) }
    }

    suspend fun authorize(token: String, appId: String, revision: Long, nonce: String): JsonObject {
        if (!validNonce(nonce)) throw GamePortalException("invalid_nonce")
        val data = request(token, appId, revision, nonce)
        val code = (data["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val expires = (data["expires_at"] as? JsonPrimitive)?.longOrNull ?: 0
        if (!code.matches(Regex("[A-Za-z0-9_-]{32,256}")) || (data["game_app_id"] as? JsonPrimitive)?.contentOrNull != appId ||
            expires <= System.currentTimeMillis() / 1000) invalid()
        return buildJsonObject { put("code", code); put("expires_at", expires); put("game_app_id", appId) }
    }

    private suspend fun request(token: String, appId: String, revision: Long, nonce: String?): JsonObject = withContext(Dispatchers.IO) {
        if (!APP_ID.matches(appId) || revision <= 0 || token.isBlank() || appKey.isBlank()) throw GamePortalException("invalid_request")
        val url = base.newBuilder().apply {
            listOf("api", "v1", "client", "game-portals", appId, if (nonce == null) "player" else "identity").forEach(::addPathSegment)
            if (nonce == null) addQueryParameter("portal_revision", revision.toString())
        }.build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
            .header("X-App-Key", appKey).header("Authorization", "Bearer $token")
        if (nonce != null) builder.post(buildJsonObject { put("portal_revision", revision); put("nonce", nonce) }
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.useCancellable(builder.build()) { response ->
            val body = response.body ?: invalid()
            if (body.contentLength() > MAX_BYTES) invalid()
            val source = body.source()
            source.request(MAX_BYTES + 1)
            if (source.buffer.size > MAX_BYTES) invalid()
            val parsed = runCatching { Json.parseToJsonElement(source.readUtf8()) as? JsonObject }.getOrNull()
            if (!response.isSuccessful) {
                val code = ((parsed?.get("code") ?: parsed?.get("error")) as? JsonPrimitive)?.contentOrNull
                throw GamePortalException(code?.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "portal_unavailable", response.code)
            }
            parsed?.get("data") as? JsonObject ?: invalid()
        }
    }

    private fun invalid(): Nothing = throw GamePortalException("invalid_response")
    companion object {
        private const val MAX_BYTES = 128L * 1024
        private val APP_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun validNonce(value: String) = value.matches(Regex("[A-Za-z0-9_-]{32,128}"))
    }
}
