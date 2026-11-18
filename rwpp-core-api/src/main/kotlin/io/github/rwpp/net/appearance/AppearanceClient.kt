/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.appearance

import io.github.rwpp.config.normalizeOpenPlatformUrl
import io.github.rwpp.net.games.OnlineGameArtBinding
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class VerifiedIdentityChallenge(
    @SerialName("server_id") val serverId: String,
    @SerialName("room_instance_id") val roomInstanceId: String,
    @SerialName("connection_id") val connectionId: String,
    @SerialName("client_nonce") val clientNonce: String,
    @SerialName("expires_at") val expiresAt: Long,
)

@Serializable
data class IdentityTicket(val ticket: String, @SerialName("expires_at") val expiresAt: Long)

@Serializable
data class VerifiedAppearanceMember(
    @SerialName("connection_id") val connectionId: String,
    val slot: Int,
    val name: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("expires_at") val expiresAt: Long,
)

@Serializable
data class VerifiedAppearanceRoster(
    @SerialName("server_id") val serverId: String,
    @SerialName("room_instance_id") val roomInstanceId: String,
    @SerialName("connection_id") val connectionId: String,
    val revision: Long,
    @SerialName("expires_at") val expiresAt: Long,
    val members: List<VerifiedAppearanceMember> = emptyList(),
)

@Serializable
data class AppearanceAssignment(
    @SerialName("account_id") val accountId: String,
    val slot: String,
    @SerialName("variant_id") val variantId: String,
)

@Serializable
data class AppearanceResolution(
    val revision: Long,
    @SerialName("expires_at") val expiresAt: Long,
    val assignments: List<AppearanceAssignment> = emptyList(),
)

/** Identity metadata is obtained from the platform using the actual game socket endpoint. */
@Serializable
data class VerifiedGameServer(
    @SerialName("server_id") val serverId: String,
    @SerialName("game_app_id") val gameAppId: String? = null,
    @SerialName("art_binding") val artBinding: OnlineGameArtBinding? = null,
    @SerialName("expansion_binding") val expansionBinding: io.github.rwpp.expansion.ExpansionBinding? = null,
)

/** 官方 HTTPS 桥接；UAS 凭据只发送到该固定地址，不跟随重定向。 */
class AppearanceClient(baseUrl: String, http: OkHttpClient = OkHttpClient()) {
    private val base = requireNotNull(normalizeOpenPlatformUrl(baseUrl)).toHttpUrl()
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun url(path: String) = base.newBuilder().apply {
        path.split('/').filter(String::isNotEmpty).forEach(::addPathSegment)
    }

    private fun read(request: Request): JsonObject? = client.newCall(request).execute().use { response ->
        if (response.code == 404) return null
        if (!response.isSuccessful) throw IOException("Appearance service HTTP ${response.code}")
        val body = response.body ?: throw IOException("Empty appearance response")
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Appearance response too large")
        val source = body.source()
        source.request(MAX_RESPONSE_BYTES + 1)
        if (source.buffer.size > MAX_RESPONSE_BYTES) throw IOException("Appearance response too large")
        json.parseToJsonElement(source.readUtf8()).jsonObject["data"]?.jsonObject
            ?: throw IOException("Invalid appearance response")
    }

    fun lookupServer(endpoint: String): String? = lookupServerInfo(endpoint)?.serverId

    fun lookupServerInfo(endpoint: String): VerifiedGameServer? {
        val data = read(Request.Builder().url(url("api/v1/identity/servers/lookup")
            .addQueryParameter("endpoint", endpoint).build()).build()) ?: return null
        return json.decodeFromJsonElement(VerifiedGameServer.serializer(), data).also {
            if (it.serverId.isBlank() || it.serverId.length > 128 || it.serverId.any(Char::isWhitespace)) {
                throw IOException("Invalid identity server")
            }
            if (it.gameAppId != null && !UUID.matches(it.gameAppId)) throw IOException("Invalid game identity")
            if (it.gameAppId == null && it.artBinding != null) throw IOException("Unscoped art binding")
            if (it.expansionBinding != null && (it.gameAppId == null || !it.expansionBinding.valid())) throw IOException("Invalid game expansion binding")
        }
    }

    fun inspectChallenge(challengeId: String, observerToken: String): VerifiedIdentityChallenge {
        require(HEX_TOKEN.matches(challengeId) && HEX_TOKEN.matches(observerToken))
        val data = read(Request.Builder().url(url("api/v1/identity/challenges/$challengeId")
            .addQueryParameter("observer_token", observerToken).build()).build())
            ?: throw IOException("Identity challenge expired")
        return json.decodeFromJsonElement(VerifiedIdentityChallenge.serializer(), data)
    }

    fun claim(challengeId: String, nonce: String, serverId: String, appKey: String, token: String): IdentityTicket {
        val body = json.encodeToString(Claim.serializer(), Claim(challengeId, nonce, serverId))
        val data = read(Request.Builder().url(url("api/v1/identity/claims").build())
            .header("X-App-Key", appKey).header("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON_TYPE)).build()) ?: throw IOException("Identity challenge expired")
        return json.decodeFromJsonElement(IdentityTicket.serializer(), data).also {
            if (!HEX_TOKEN.matches(it.ticket)) throw IOException("Invalid identity ticket")
        }
    }

    fun members(room: String, observerToken: String): VerifiedAppearanceRoster {
        require(room.length in 1..128 && HEX_TOKEN.matches(observerToken))
        val data = read(Request.Builder().url(url("api/v1/identity/rooms/$room/members")
            .addQueryParameter("observer_token", observerToken).build()).build())
            ?: throw IOException("Identity roster unavailable")
        return json.decodeFromJsonElement(VerifiedAppearanceRoster.serializer(), data)
    }

    fun resolve(packId: String, catalogHash: String, accounts: List<String>, gameAppId: String? = null): AppearanceResolution {
        require(accounts.size <= 256 && accounts.all(ACCOUNT_ID::matches) && HEX_TOKEN.matches(catalogHash))
        require(gameAppId == null || UUID.matches(gameAppId))
        if (accounts.isEmpty()) return AppearanceResolution(0, System.currentTimeMillis() / 1000 + 60)
        val chunks = accounts.distinct().chunked(64).map { batch ->
            val body = json.encodeToString(Resolve.serializer(), Resolve(catalogHash, batch, gameAppId))
            val data = read(Request.Builder().url(url("api/v1/art-packs/$packId/resolve").build())
                .post(body.toRequestBody(JSON_TYPE)).build())
                ?: return AppearanceResolution(0, 0)
            json.decodeFromJsonElement(AppearanceResolution.serializer(), data)
        }
        if (chunks.map { it.revision }.distinct().size != 1) throw IOException("Appearance revision changed during batch")
        return AppearanceResolution(chunks.first().revision, chunks.minOf { it.expiresAt }, chunks.flatMap { it.assignments })
    }

    @Serializable private data class Claim(
        @SerialName("challenge_id") val challengeId: String,
        @SerialName("client_nonce") val nonce: String,
        @SerialName("server_id") val serverId: String,
    )
    @Serializable private data class Resolve(
        @SerialName("catalog_hash") val catalogHash: String,
        @SerialName("account_ids") val accounts: List<String>,
        @SerialName("game_app_id") val gameAppId: String? = null,
    )

    companion object {
        private const val MAX_RESPONSE_BYTES = 256L * 1024
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val HEX_TOKEN = Regex("[0-9a-f]{64}")
        val CLIENT_NONCE = Regex("[0-9a-f]{32}")
        val ACCOUNT_ID = Regex("[1-9][0-9]{0,18}")
    }
}
