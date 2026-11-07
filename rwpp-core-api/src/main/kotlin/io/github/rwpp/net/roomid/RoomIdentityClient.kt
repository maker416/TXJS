/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.roomid

import io.github.rwpp.logger
import io.github.rwpp.net.sync.ErrorResponse
import io.github.rwpp.net.useCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val API_PREFIX = "/api/v1/roomid"
private const val CAPABILITIES_PATH = "/api/v1/capabilities"
private const val MEMBERSHIP_UNAVAILABLE = "room_id_membership_unavailable"
private const val APP_KEY_HEADER = "X-App-Key"
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

@Serializable
private data class RoomIdCapabilities(
    @SerialName("room_id_membership_verified") val membershipVerified: Boolean = false,
)

/**
 * 房间身份公示调用失败。[statusCode] 为 HTTP 状态码；网络层失败时为 null。
 */
open class RoomIdException(message: String, val statusCode: Int?) : IOException(message)

/** Token 无效（401）：调用方应停止重试，不换镜像。 */
class RoomIdUnauthorizedException(message: String) : RoomIdException(message, 401)

/**
 * 没有可用的成员核验节点，或服务端明确拒绝身份交换。调用方会话内停用身份公示。
 */
class RoomIdFeatureUnavailableException(message: String) : RoomIdException(message, null)

/**
 * relaymod 房间身份公示端点组（`/api/v1/roomid/`）的 HTTP 客户端。
 *
 * - 全部方法为 suspend，并在 [Dispatchers.IO] 上执行，支持协程取消；
 * - 请求带 [APP_KEY_HEADER] 与 `Authorization: Bearer`（由服务端向 UAS 转发核验）；
 * - 只在通过 HTTPS（本机联调除外）和成员核验能力探测后发送凭据，不跟随重定向。
 * - 网络错误可换已验证的镜像，401 或明确拒绝成员身份交换时停止。
 */
class RoomIdentityClient(
    baseUrls: List<String>,
    client: OkHttpClient,
) {
    private val baseUrls = baseUrls.mapNotNull { raw ->
        val url = raw.trim().toHttpUrlOrNull() ?: return@mapNotNull null
        val secure = url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1")
        if (secure && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            url.toString().trimEnd('/')
        } else null
    }.distinct()

    /** 独立 15s 超时：身份公示是轻量请求，不沿用共享客户端的长读超时。 */
    private val http: OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val connection = chain.connection()
            if (!request.url.isHttps && (connection == null ||
                    connection.route().proxy.type() != Proxy.Type.DIRECT ||
                    !connection.socket().inetAddress.isLoopbackAddress)
            ) throw IOException("房间身份服务的 HTTP 连接必须直连本机")
            chain.proceed(request)
        }
        .build()

    private val json = Json {
        // 容忍服务端后续新增字段，避免老客户端解析失败
        ignoreUnknownKeys = true
    }

    /**
     * 公示本玩家在当前房间的「房间 key + 玩家名 → 账号」映射（TTL 90s，须周期性续期）。
     * 对应 `PUT /api/v1/roomid/publish`；200 `{"ok":true}`。
     */
    suspend fun publish(roomKeys: List<String>, playerName: String, appKey: String, token: String) {
        requestWithFailover(
            path = "$API_PREFIX/publish",
            appKey = appKey,
            token = token,
            customize = {
                put(json.encodeToString(RoomIdPublishRequest(roomKeys, playerName)).toRequestBody(JSON_MEDIA))
            },
        ) { }
    }

    /**
     * 按 `room_key + player_name` 查询账号三元组。
     * 对应 `GET /api/v1/roomid/lookup?room_key=A&room_key=B&player_name=X`（room_key 重复 query 参数）。
     * 一律 200：无匹配 / 无权限返回空列表。
     */
    suspend fun lookup(
        roomKeys: List<String>,
        playerName: String,
        appKey: String,
        token: String,
    ): List<RoomIdEntry> {
        val query = buildString {
            append("$API_PREFIX/lookup?")
            append(roomKeys.joinToString("&") { "room_key=${enc(it)}" })
            append("&player_name=${enc(playerName)}")
        }
        return requestWithFailover(query, appKey, token, { get() }) { response ->
            json.decodeFromString<RoomIdLookupResponse>(response.body?.string().orEmpty()).users
        }
    }

    /** 撤销公示（离房时调用，幂等）。对应 `DELETE /api/v1/roomid/publish`。 */
    suspend fun delete(appKey: String, token: String) {
        requestWithFailover("$API_PREFIX/publish", appKey, token, { delete() }) { }
    }

    /**
     * 仅向通过无凭据探测并声明验证房间成员身份的节点发送账号凭据。
     * 网络错误与临时服务错误可换到其他已验证节点，服务端明确拒绝成员身份交换时立即停止。
     */
    private suspend fun <T> requestWithFailover(
        path: String,
        appKey: String,
        token: String,
        customize: Request.Builder.() -> Unit,
        onSuccess: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        for (base in baseUrls) {
            coroutineContext.ensureActive()
            try {
                if (!supportsVerifiedMembership(base)) continue
                val request = Request.Builder()
                    .url(base + path)
                    .header(APP_KEY_HEADER, appKey)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .apply(customize)
                    .build()
                http.useCancellable(request) { response ->
                    when {
                        response.isSuccessful -> return@withContext onSuccess(response)
                        // 旧版 relay 无此路径（404）/ 服务端未配置该功能（503）：换下一镜像
                        response.code == 404 || response.code == 503 -> {
                            logger.warn("房间身份服务 $base 返回 HTTP ${response.code}，尝试下一个地址")
                        }
                        response.code in 500..599 -> {
                            logger.warn("房间身份服务 $base 返回 HTTP ${response.code}，尝试下一个地址")
                            lastError = response.toRoomIdException()
                        }
                        // 401（token 无效）与其它 4xx 是确定性失败，不换镜像直接抛
                        else -> throw response.toRoomIdException()
                    }
                }
            } catch (e: RoomIdException) {
                throw e
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                logger.warn("连接房间身份服务 $base 失败：${e.message}，尝试下一个地址")
                lastError = e
            }
        }
        throw lastError ?: RoomIdFeatureUnavailableException("没有支持成员身份核验的房间身份服务")
    }

    private suspend fun supportsVerifiedMembership(base: String): Boolean =
        http.useCancellable(Request.Builder().url(base + CAPABILITIES_PATH).build()) { response ->
            if (response.code in 500..599 && response.code != 503) {
                throw IOException("HTTP ${response.code}")
            }
            if (!response.isSuccessful) return@useCancellable false
            try {
                json.decodeFromString<RoomIdCapabilities>(response.body?.string().orEmpty()).membershipVerified
            } catch (_: SerializationException) {
                false
            }
        }

    private fun Response.toRoomIdException(): RoomIdException {
        val detail = runCatching {
            json.decodeFromString<ErrorResponse>(body?.string().orEmpty()).error
        }.getOrNull().orEmpty()
        val message = if (detail.isNotBlank()) detail else "HTTP $code"
        return when {
            code == 401 -> RoomIdUnauthorizedException(message)
            code == 403 && detail == MEMBERSHIP_UNAVAILABLE -> RoomIdFeatureUnavailableException(message)
            else -> RoomIdException(message, code)
        }
    }

    /**
     * 使用 charset 名称重载编码查询参数。
     * 勿用 `URLEncoder.encode(String, Charset)`：该重载在 Android API 33 之前不存在（本项目 minSdk=26）。
     */
    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
