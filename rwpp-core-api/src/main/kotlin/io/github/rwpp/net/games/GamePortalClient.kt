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

/** Kept only in the native navigation/session state. Never added to a provider URL or bridge response. */
class GameTestAccess(val token: String, val expiresAt: Long, private val issuer: String = "") {
    fun validFor(platformUrl: String): Boolean =
        issuer.isNotEmpty() && issuer == normalizeOpenPlatformUrl(platformUrl) && expiresAt > System.currentTimeMillis() / 1000
}

/** JWTs are sent only to the configured official platform, never to the provider website. */
class GamePortalClient(baseUrl: String, private val appKey: String, http: OkHttpClient = OkHttpClient()) {
    private val base = normalizeOpenPlatformUrl(baseUrl)?.toHttpUrl()
        ?: throw GamePortalException("https_required")
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS).apply { if (!base.isHttps) proxy(Proxy.NO_PROXY) }.build()
    private val directory = OnlineGameClient(baseUrl, http)

    suspend fun testAccess(token: String, appId: String, password: String): GameTestAccess {
        val data = request(token, appId, "test-access", body = buildJsonObject { put("password", password) })
        val access = (data["access_token"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val expires = (data["expires_at"] as? JsonPrimitive)?.longOrNull ?: 0
        if (!access.matches(Regex("[A-Za-z0-9_-]{32,256}")) || expires <= System.currentTimeMillis() / 1000) invalid()
        return GameTestAccess(access, expires, requireNotNull(normalizeOpenPlatformUrl(base.toString())))
    }

    suspend fun game(token: String, appId: String, testAccess: GameTestAccess? = null): OnlineGame {
        val game = try {
            directory.decodeGame(request(token, appId, "access", testAccess = testAccess), appId)
        } catch (error: GamePortalException) {
            if (error.httpStatus != 404 || testAccess != null) throw error
            // Older platforms serve only the public directory. Apply the same public admission rules.
            withContext(Dispatchers.IO) { directory.game(appId) } ?: throw error
        }
        if (game.portalRevision <= 0 || !isOnlineGamePortalUrl(game.portalUrl) || testAccess == null && !game.canEnter)
            throw GamePortalException("game_maintenance")
        // A successful scoped access response lifts the game-wide hold only. Each node still needs a live lease.
        return if (testAccess == null) game else game.copy(available = game.servers.any { it.online && it.available })
    }

    suspend fun player(token: String, appId: String, revision: Long, testAccess: GameTestAccess? = null): JsonObject {
        if (revision <= 0) throw GamePortalException("invalid_request")
        val data = request(token, appId, "player", revision, testAccess = testAccess)
        val id = (data["account_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val nickname = (data["nickname"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (!id.matches(Regex("[1-9][0-9]{0,18}")) || nickname.length > 256) invalid()
        return buildJsonObject { put("account_id", id); put("nickname", nickname) }
    }

    suspend fun authorize(token: String, appId: String, revision: Long, nonce: String, testAccess: GameTestAccess? = null): JsonObject {
        if (!validNonce(nonce)) throw GamePortalException("invalid_nonce")
        if (revision <= 0) throw GamePortalException("invalid_request")
        val data = request(token, appId, "identity", body = buildJsonObject { put("portal_revision", revision); put("nonce", nonce) }, testAccess = testAccess)
        val code = (data["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val expires = (data["expires_at"] as? JsonPrimitive)?.longOrNull ?: 0
        if (!code.matches(Regex("[A-Za-z0-9_-]{32,256}")) || (data["game_app_id"] as? JsonPrimitive)?.contentOrNull != appId ||
            expires <= System.currentTimeMillis() / 1000) invalid()
        return buildJsonObject { put("code", code); put("expires_at", expires); put("game_app_id", appId) }
    }

    private suspend fun request(token: String, appId: String, action: String, revision: Long? = null,
                                body: JsonObject? = null, testAccess: GameTestAccess? = null): JsonObject = withContext(Dispatchers.IO) {
        if (!APP_ID.matches(appId) || token.isBlank() || appKey.isBlank()) throw GamePortalException("invalid_request")
        if (testAccess != null && !testAccess.validFor(base.toString())) throw GamePortalException("game_maintenance")
        val url = base.newBuilder().apply {
            listOf("api", "v1", "client", "game-portals", appId, action).forEach(::addPathSegment)
            if (revision != null) addQueryParameter("portal_revision", revision.toString())
        }.build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (action != "access" || testAccess != null)
            builder.header("X-App-Key", appKey).header("Authorization", "Bearer $token")
        if (testAccess != null) builder.header("X-Game-Test-Access", testAccess.token)
        if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.useCancellable(builder.build()) { response ->
            val body = response.body ?: invalid()
            val limit = if (action == "access") 1024L * 1024 else MAX_BYTES
            if (body.contentLength() > limit) invalid()
            val source = body.source()
            source.request(limit + 1)
            if (source.buffer.size > limit) invalid()
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
