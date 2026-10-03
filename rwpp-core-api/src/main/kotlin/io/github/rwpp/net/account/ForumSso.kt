/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.account

import io.github.rwpp.net.useCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class ForumTicketRequest(@SerialName("target_app_code") val targetAppCode: String, @SerialName("code_challenge") val codeChallenge: String)

@Serializable
data class ForumTicketResponse(val ticket: String, @SerialName("expires_in") val expiresIn: Int)

@Serializable
data class ForumClientConfig(@SerialName("target_app_code") val targetAppCode: String, val protocol: Int)

@Serializable
data class ForumPreparedLogin(val handoff: String, @SerialName("logout_key") val logoutKey: String, @SerialName("expires_in") val expiresIn: Int)

@Serializable
data class ForumClientRevocation(val url: String, val key: String)

@Serializable
private data class ForumPrepareRequest(val ticket: String, @SerialName("code_verifier") val codeVerifier: String)

@Serializable
private data class ForumRevokeRequest(@SerialName("logout_key") val logoutKey: String)

/** 票据持有者还必须持有 native 生成的 verifier，论坛和 UAS 都不保存明文 verifier。 */
data class ForumProof(val verifier: String, val challenge: String) {
    companion object {
        fun create(): ForumProof {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return ForumProof(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(hash))
        }
    }
}

class ForumSsoRequiresHttpsException : IllegalArgumentException("Forum SSO requires HTTPS for the forum")

/** Diagnostic metadata only. Never expose response bodies, request credentials or exception messages. */
class ForumSsoHttpException(val statusCode: Int) : IllegalStateException("Forum SSO HTTP $statusCode")

enum class ForumSsoStep { REVOKE, CONFIG, TICKET, PREPARE, SAVE }

class ForumSsoStepException(val step: ForumSsoStep, cause: Exception) : IllegalStateException("Forum SSO step $step failed", cause) {
    val statusCode: Int? = when (cause) {
        is AccountApiException -> cause.statusCode
        is ForumSsoHttpException -> cause.statusCode
        else -> null
    }
}

object ForumSsoUrls {
    fun base(value: String): String {
        val url = value.trim().toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Invalid forum URL" }
        return url.toString().trimEnd('/')
    }

    fun requireSecure(value: String) {
        val url = base(value).toHttpUrl()
        if (!url.isHttps && url.host !in setOf("localhost", "127.0.0.1", "::1")) throw ForumSsoRequiresHttpsException()
    }

    fun bootstrap(value: String): String = base(value) + "/sso/client"
}

/** Native 专用 CookieJar，不读取/写入 WebView 或 Chromium Cookie；所有变更保留 CSRF。 */
class ForumSsoClient(value: String, http: OkHttpClient) {
    val baseUrl = ForumSsoUrls.base(value)
    val bootstrapUrl = ForumSsoUrls.bootstrap(value)
    private val json = Json { ignoreUnknownKeys = true }
    private val cookies = mutableListOf<Cookie>()
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(object : CookieJar {
        @Synchronized override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) {
            incoming.forEach { cookie -> cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }; cookies.add(cookie) }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
    }).build()

    init { ForumSsoUrls.requireSecure(value) }

    suspend fun config(): ForumClientConfig = withContext(Dispatchers.IO) {
        client.useCancellable(Request.Builder().url("$bootstrapUrl/config").build()) { response ->
            if (response.code != 200) throw ForumSsoHttpException(response.code)
            json.decodeFromString<ForumClientConfig>(response.body!!.string()).also { require(it.protocol == 1 && it.targetAppCode.matches(Regex("[A-Za-z0-9_-]{1,64}"))) }
        }
    }

    private suspend fun csrf(): String = withContext(Dispatchers.IO) {
        client.useCancellable(Request.Builder().url(bootstrapUrl).build()) { response ->
            if (response.code != 200) throw ForumSsoHttpException(response.code)
            response.header("X-CSRF-Token")?.takeIf { it.isNotBlank() } ?: error("Missing forum CSRF token")
        }
    }

    private suspend fun post(action: String, body: String): String {
        val csrf = csrf()
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$bootstrapUrl/$action").header("X-CSRF-Token", csrf)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            client.useCancellable(request) { response ->
                if (response.code != 200) throw ForumSsoHttpException(response.code)
                response.body!!.string()
            }
        }
    }

    suspend fun prepare(ticket: String, verifier: String): ForumPreparedLogin =
        json.decodeFromString<ForumPreparedLogin>(post("prepare", json.encodeToString(ForumPrepareRequest(ticket, verifier)))).also {
            require(it.handoff.matches(Regex("[a-f0-9]{64}")) && it.logoutKey.matches(Regex("[a-f0-9]{64}")) && it.expiresIn in 1..60)
        }

    suspend fun revoke(key: String) { post("revoke", json.encodeToString(ForumRevokeRequest(key))) }
}
