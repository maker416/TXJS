/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.roomid

import io.github.rwpp.logger
import io.github.rwpp.net.sync.ErrorResponse
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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val API_PREFIX = "/api/v1/roomid"
private const val APP_KEY_HEADER = "X-App-Key"
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * 房间身份公示调用失败。[statusCode] 为 HTTP 状态码；网络层失败时为 null。
 */
open class RoomIdException(message: String, val statusCode: Int?) : IOException(message)

/** Token 无效（401）：调用方应停止重试，不换镜像。 */
class RoomIdUnauthorizedException(message: String) : RoomIdException(message, 401)

/**
 * 服务端未提供身份公示功能：全部镜像返回 404（旧版 relay 无此路径）或 503（未配置该功能）。
 * 调用方应做会话内降级，不影响其它功能。
 */
class RoomIdFeatureUnavailableException(message: String) : RoomIdException(message, null)

/**
 * relaymod 房间身份公示端点组（`/api/v1/roomid/`）的 HTTP 客户端。
 *
 * - 全部方法为 suspend，并在 [Dispatchers.IO] 上执行，支持协程取消；
 * - 请求带 [APP_KEY_HEADER] 与 `Authorization: Bearer`（由服务端向 UAS 转发核验）；
 * - 多 [baseUrls] failover 语义**与 [io.github.rwpp.net.sync.ModSyncClient.withFailover] 不同**：
 *   404 / 5xx / 网络错误换下一镜像；401 直接抛 [RoomIdUnauthorizedException] 不换镜像；
 *   2xx 返回；全部镜像 404/503 抛 [RoomIdFeatureUnavailableException]（会话内降级）。
 */
class RoomIdentityClient(
    baseUrls: List<String>,
    client: OkHttpClient,
) {
    /** 规范化后的 baseUrl 列表（trim 并去掉尾部斜杠，丢弃空白项）。 */
    private val baseUrls: List<String> = baseUrls
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotEmpty() }

    init {
        require(this.baseUrls.isNotEmpty()) { "RoomIdentityClient 至少需要一个 baseUrl" }
    }

    /** 独立 15s 超时：身份公示是轻量请求，不沿用共享客户端的长读超时。 */
    private val http: OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
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
     * 多 baseUrl 按序 failover：任一地址 2xx 即返回；
     * 404 / 503 / 5xx / 网络错误记录后尝试下一个；401 与其它 4xx 立即抛出；
     * 全部镜像都是 404/503 抛 [RoomIdFeatureUnavailableException]，否则抛出最后记录的错误。
     */
    private suspend fun <T> requestWithFailover(
        path: String,
        appKey: String,
        token: String,
        customize: Request.Builder.() -> Unit,
        onSuccess: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        var featureUnavailableCount = 0
        for (base in baseUrls) {
            coroutineContext.ensureActive()
            val request = Request.Builder()
                .url(base + path)
                .header(APP_KEY_HEADER, appKey)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .apply(customize)
                .build()
            try {
                http.executeCancellable(request).use { response ->
                    when {
                        response.isSuccessful -> return@withContext onSuccess(response)
                        // 旧版 relay 无此路径（404）/ 服务端未配置该功能（503）：换下一镜像
                        response.code == 404 || response.code == 503 -> {
                            logger.warn("房间身份服务 $base 返回 HTTP ${response.code}，尝试下一个地址")
                            featureUnavailableCount++
                            lastError = response.toRoomIdException()
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
        if (featureUnavailableCount == baseUrls.size) {
            throw RoomIdFeatureUnavailableException("房间身份公示功能不可用（全部镜像返回 404/503）")
        }
        throw lastError ?: RoomIdException("没有可用的房间身份服务地址", null)
    }

    private fun Response.toRoomIdException(): RoomIdException {
        val detail = runCatching {
            json.decodeFromString<ErrorResponse>(body?.string().orEmpty()).error
        }.getOrNull().orEmpty()
        val message = if (detail.isNotBlank()) detail else "HTTP $code"
        return if (code == 401) {
            RoomIdUnauthorizedException(message)
        } else {
            RoomIdException(message, code)
        }
    }

    /**
     * 使用 charset 名称重载编码查询参数。
     * 勿用 `URLEncoder.encode(String, Charset)`：该重载在 Android API 33 之前不存在（本项目 minSdk=26）。
     */
    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}

/**
 * 执行请求并支持协程取消（取消时中断底层 OkHttp 调用）。
 * 与 `ModSyncClient` / `AccountApiClient` 中的同名私有实现等价（均为文件内私有不可复用）。
 */
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
