/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.games

import io.github.rwpp.config.normalizeOpenPlatformUrl
import io.github.rwpp.expansion.ExpansionBinding
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@Serializable
data class OnlineGameArtBinding(
    @SerialName("pack_id") val packId: String,
    val version: String,
    @SerialName("catalog_hash") val catalogHash: String,
    @SerialName("download_url") val downloadUrl: String = "",
    @SerialName("archive_sha256") val archiveSha256: String = "",
)

@Serializable
data class OnlineGameServer(
    @SerialName("server_id") val serverId: String,
    val name: String,
    val endpoint: String,
    val available: Boolean = false,
    val online: Boolean = false,
    @SerialName("current_players") val currentPlayers: Int = 0,
    @SerialName("max_players") val maxPlayers: Int = 0,
    @SerialName("closed_message") val closedMessage: String = "",
)

@Serializable
data class OnlineGame(
    @SerialName("app_id") val appId: String,
    val name: String,
    val description: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    val available: Boolean = false,
    val servers: List<OnlineGameServer> = emptyList(),
    @SerialName("art_binding") val artBinding: OnlineGameArtBinding? = null,
    @SerialName("expansion_binding") val expansionBinding: ExpansionBinding? = null,
    @SerialName("portal_url") val portalUrl: String = "",
    @SerialName("portal_revision") val portalRevision: Long = 0,
    @SerialName("expansion_sync_endpoint") val expansionSyncEndpoint: String? = null,
    @SerialName("has_test_password") val testPasswordSet: Boolean = false,
) {
    val canEnter: Boolean get() = available && portalRevision > 0 && isOnlineGamePortalUrl(portalUrl) &&
        servers.any { it.online && it.available }
}

/** Public directory only: never accepts, stores or sends UAS tokens or developer credentials. */
class OnlineGameClient(baseUrl: String, http: OkHttpClient = OkHttpClient()) {
    private val base = requireNotNull(normalizeOpenPlatformUrl(baseUrl)).toHttpUrl()
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun games(): List<OnlineGame> {
        val data = get("api", "v1", "games") ?: return emptyList() // Older platforms have no directory.
        val games = json.decodeFromJsonElement(Directory.serializer(), data).games
        if (games.size > 256 || games.map { it.appId }.distinct().size != games.size) invalid()
        return games.map { game ->
            val entry = if (game.portalRevision < 0 || game.portalUrl.isNotEmpty() &&
                (game.portalRevision == 0L || !isOnlineGamePortalUrl(game.portalUrl))) {
                game.copy(available = false, portalUrl = "", portalRevision = 0)
            } else game
            validate(entry)
            entry
        }
    }

    /** Refresh before joining: a previously visible entry may have been suspended or moved. */
    fun game(appId: String): OnlineGame? {
        require(UUID.matches(appId))
        val data = get("api", "v1", "games", appId) ?: return null
        return decodeGame(data, appId)
    }

    internal fun decodeGame(data: JsonObject, appId: String): OnlineGame =
        json.decodeFromJsonElement(OnlineGame.serializer(), data).also {
            validate(it)
            if (it.appId != appId) invalid()
        }

    private fun get(vararg path: String): JsonObject? {
        val url = base.newBuilder().apply { path.forEach(::addPathSegment) }.build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("Game directory HTTP ${response.code}")
            val body = response.body ?: invalid()
            if (body.contentLength() > MAX_BYTES) invalid()
            val source = body.source()
            source.request(MAX_BYTES + 1)
            if (source.buffer.size > MAX_BYTES) invalid()
            try {
                json.parseToJsonElement(source.readUtf8()).jsonObject["data"]?.jsonObject ?: invalid()
            } catch (error: IllegalArgumentException) {
                throw IOException("Invalid game directory", error)
            }
        }
    }

    private fun validate(game: OnlineGame) {
        if (!UUID.matches(game.appId) || game.name.isBlank() || game.name.codePointCount(0, game.name.length) > 128 ||
            game.description.codePointCount(0, game.description.length) > 4000 || game.servers.size > 256 ||
            game.servers.map { it.serverId }.distinct().size != game.servers.size ||
            game.coverUrl.length > 2048 || game.coverUrl.isNotEmpty() && parseOnlineGameWebUrl(game.coverUrl) == null) invalid()
        if (game.portalUrl.isNotEmpty() && (!isOnlineGamePortalUrl(game.portalUrl) || game.portalRevision <= 0) ||
            game.portalRevision < 0) invalid()
        game.servers.forEach {
            if (!SERVER_ID.matches(it.serverId) || it.name.isBlank() || it.name.codePointCount(0, it.name.length) > 128 ||
                !isOnlineGameEndpoint(it.endpoint) || it.currentPlayers !in 0..10000 ||
                it.maxPlayers !in 0..10000 || it.closedMessage.codePointCount(0, it.closedMessage.length) > 2000) invalid()
        }
        game.artBinding?.let {
            if (!UUID.matches(it.packId) || !VERSION.matches(it.version) || !SHA256.matches(it.catalogHash) ||
                (it.downloadUrl.isEmpty() != it.archiveSha256.isEmpty()) ||
                it.downloadUrl.isNotEmpty() && (!isOnlineGameAssetUrl(it.downloadUrl) || !SHA256.matches(it.archiveSha256))) invalid()
        }
        game.expansionBinding?.let { expansion ->
            if (!expansion.valid()) invalid()
            val art = game.artBinding ?: invalid()
            if (art.packId != expansion.artBinding.packId || art.version != expansion.artBinding.version ||
                art.catalogHash != expansion.artBinding.catalogHash || art.downloadUrl.isNotEmpty() || art.archiveSha256.isNotEmpty()) invalid()
        }
        game.expansionSyncEndpoint?.let { endpoint ->
            if (game.expansionBinding == null || !isPublicExpansionSyncEndpoint(endpoint)) invalid()
        }
    }

    private fun invalid(): Nothing = throw IOException("Invalid game directory")

    @Serializable private data class Directory(val games: List<OnlineGame> = emptyList())

    companion object {
        private const val MAX_BYTES = 1024L * 1024
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val SERVER_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}")
    }
}

private fun parseOnlineGameWebUrl(value: String): HttpUrl? {
    if (value.any(Char::isISOControl)) return null
    return value.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
}

/** The published portal may use an HTTP or HTTPS origin. */
fun isOnlineGamePortalUrl(value: String): Boolean = value.length <= 2048 && parseOnlineGameWebUrl(value) != null

fun onlineGamePortalOrigin(value: String): String? = parseOnlineGameWebUrl(value)?.let {
    it.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
}

/** Literal socket endpoints only; prevents directory entries becoming host commands or room short codes. */
fun isOnlineGameEndpoint(endpoint: String): Boolean {
    if (endpoint.length > 100 || endpoint.any(Char::isWhitespace)) return false
    val split = endpoint.lastIndexOf(':')
    if (split <= 0 || endpoint.substring(split + 1).toIntOrNull() !in 1..65535) return false
    val host = endpoint.substring(0, split)
    if (host.startsWith('[') && host.endsWith(']')) {
        val address = host.substring(1, host.length - 1)
        if (':' !in address || !address.matches(Regex("[0-9a-fA-F:.]+"))) return false
        return runCatching { InetAddress.getByName(address) is Inet6Address }.getOrDefault(false)
    }
    return host.split('.').let { octets ->
        octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 }
    }
}

/** Only public literal addresses may receive automatic connections from the portal. */
fun isPublicExpansionSyncEndpoint(endpoint: String): Boolean {
    if (!isOnlineGameEndpoint(endpoint)) return false
    val host = endpoint.substringBeforeLast(':').removeSurrounding("[", "]")
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    val (first, second, third) = bytes
    if (bytes.size == 4) {
        return first != 0 && first < 224 &&
            !(first == 100 && second in 64..127) &&
            !(first == 192 && (second == 0 && third in setOf(0, 2) || second == 88 && third == 99)) &&
            !(first == 198 && (second in 18..19 || second == 51 && third == 100)) &&
            !(first == 203 && second == 0 && third == 113)
    }
    return first in 0x20..0x3f &&
        !(first == 0x20 && second == 0x01 && third == 0x0d && bytes[3] == 0xb8) &&
        !(first == 0x20 && second == 0x02)
}

/** HTTPS assets, with literal loopback HTTP permitted for local verification. No credentials or fragment. */
fun isOnlineGameAssetUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return value.length <= 2048 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
        (url.isHttps || url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost", "::1"))
}
