/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.impl

import com.eclipsesource.json.Json
import io.github.rwpp.AppContext
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.DEFAULT_ONLINE_CHANNEL
import io.github.rwpp.config.DEFAULT_ONLINE_PRESENCE_API_URL
import io.github.rwpp.config.OnlinePresencePreferences
import io.github.rwpp.config.normalizeOnlinePresenceBaseUrl
import io.github.rwpp.core.Initialization
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import io.github.rwpp.net.OnlinePresenceTracker
import io.github.rwpp.projectVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.random.Random

@Single(binds = [OnlinePresenceTracker::class, Initialization::class])
class OnlinePresenceTrackerImpl : OnlinePresenceTracker, KoinComponent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private val endpoint = MutableStateFlow(DEFAULT_ONLINE_PRESENCE_API_URL)

    private data class PresenceSession(val id: String, val heartbeatIntervalSec: Int)

    override fun refreshEndpoint() {
        endpoint.value = normalizeOnlinePresenceBaseUrl(get<OnlinePresencePreferences>().apiUrl)
    }

    override fun init() {
        refreshEndpoint()
        val appContext = get<AppContext>()
        appContext.onExit { stop() }
        if (heartbeatJob?.isActive != true) {
            heartbeatJob = scope.launch { endpoint.collectLatest { runHeartbeatLoop(it) } }
        }
    }

    override fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private suspend fun runHeartbeatLoop(baseUrl: String) = coroutineScope {
        val registrationBackoffSec = listOf(0L, 5L, 10L, 30L)
        var session: PresenceSession? = null
        var deviceId: String? = null
        while (isActive) {
            if (session == null) {
                for (backoff in registrationBackoffSec) {
                    if (!isActive) return@coroutineScope
                    if (backoff > 0) delay(backoff * 1000L)
                    session = runCatching {
                        deviceId = deviceId ?: resolveDeviceId(baseUrl)
                        registerSession(baseUrl, deviceId)
                    }.getOrElse { error ->
                        logger.debug("Online presence registration failed: ${error.message}")
                        null
                    }
                    if (session != null) break
                }
            }

            val activeSession = session
            if (activeSession == null) {
                delay(30_000L)
                continue
            }
            val jitterSec = Random.nextInt(-1, 2)
            val waitSec = (activeSession.heartbeatIntervalSec + jitterSec).coerceAtLeast(5)
            delay(waitSec * 1000L)
            if (!isActive) return@coroutineScope

            val statusCode = runCatching { sendHeartbeat(baseUrl, activeSession.id) }.getOrElse { error ->
                logger.debug("Online presence heartbeat failed: ${error.message}")
                -1
            }

            when (statusCode) {
                200, -1 -> Unit
                404 -> {
                    logger.info("Online presence session expired, re-registering")
                    session = null
                }
                else -> logger.debug("Online presence heartbeat returned HTTP $statusCode")
            }
        }
    }

    private fun registerSession(baseUrl: String, deviceId: String?): PresenceSession? {
        val appContext = get<AppContext>()
        val platform = when {
            appContext.isAndroid() -> "android"
            appContext.isDesktop() -> "windows"
            else -> {
                logger.warn("Online presence: unknown platform, skipping registration")
                return null
            }
        }

        val deviceField = deviceId?.let { ""","device_id":"$it"""" } ?: ""
        val body = """{"platform":"$platform","version":"$projectVersion","channel":"$DEFAULT_ONLINE_CHANNEL"$deviceField}"""
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/sessions")
            .header("Accept", "application/json")
            .post(body)
            .build()

        get<Net>().client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                logger.debug(
                    "Online presence registration failed: HTTP ${response.code}" +
                        if (errorBody.isNotEmpty()) " ($errorBody)" else ""
                )
                return null
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrBlank()) {
                logger.debug("Online presence registration returned empty body")
                return null
            }

            val json = Json.parse(responseBody).asObject()
            val id = json.getString("session_id", "").trim()
            if (id.isEmpty()) {
                logger.debug("Online presence registration missing session_id")
                return null
            }

            val interval = json.getInt("heartbeat_interval_sec", 15).coerceAtLeast(5)
            logger.info("Online presence session registered ($platform), interval=${interval}s")
            return PresenceSession(id, interval)
        }
    }

    /**
     * 获取本机唯一设备 ID：优先读本地持久化配置；首次安装时向服务器申请一个并保存。
     * 只要应用数据不被清除（卸载重装、清数据）就一直沿用，升级不受影响。
     * 申请失败返回 null，本次注册不带 device_id（服务端回退按 IP 统计）。
     */
    private fun resolveDeviceId(baseUrl: String): String? {
        val configIO = get<ConfigIO>()
        val isDefaultEndpoint = baseUrl == normalizeOnlinePresenceBaseUrl(DEFAULT_ONLINE_PRESENCE_API_URL)
        if (isDefaultEndpoint) {
            val saved = configIO.readSingleConfig(CONFIG_GROUP, DEVICE_ID_KEY)?.trim()
            if (!saved.isNullOrEmpty()) return saved
        }

        val request = Request.Builder()
            .url("$baseUrl/api/v1/devices")
            .header("Accept", "application/json")
            .post(EMPTY_BODY)
            .build()

        return runCatching {
            get<Net>().client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.debug("Online presence device id request failed: HTTP ${response.code}")
                    null
                } else {
                    val body = response.body?.string()
                    val id = body
                        ?.let { runCatching { Json.parse(it).asObject().getString("device_id", "").trim() }.getOrNull() }
                        ?.takeIf { it.isNotEmpty() }
                    if (id == null) {
                        null
                    } else {
                        if (isDefaultEndpoint) configIO.saveSingleConfig(CONFIG_GROUP, DEVICE_ID_KEY, id)
                        id
                    }
                }
            }
        }.getOrElse { error ->
            logger.debug("Online presence device id request failed: ${error.message}")
            null
        }
    }

    private fun sendHeartbeat(baseUrl: String, id: String): Int {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/sessions/$id/heartbeat")
            .post(EMPTY_BODY)
            .build()

        get<Net>().client.newCall(request).execute().use { response ->
            return response.code
        }
    }

    companion object {
        private const val CONFIG_GROUP = "online_presence"
        private const val DEVICE_ID_KEY = "device_id"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}
