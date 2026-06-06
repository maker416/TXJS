/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.impl

import com.eclipsesource.json.Json
import io.github.rwpp.AppContext
import io.github.rwpp.config.DEFAULT_ONLINE_PRESENCE_API_URL
import io.github.rwpp.config.normalizeOnlinePresenceBaseUrl
import io.github.rwpp.core.Initialization
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import io.github.rwpp.net.OnlinePresenceTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var heartbeatIntervalSec: Int = 15

    private val baseUrl: String
        get() = normalizeOnlinePresenceBaseUrl(DEFAULT_ONLINE_PRESENCE_API_URL)

    override fun init() {
        val appContext = get<AppContext>()
        appContext.onExit { stop() }
        startHeartbeatLoop()
    }

    override fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        sessionId = null
    }

    private fun startHeartbeatLoop() {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = scope.launch {
            val registrationBackoffSec = listOf(0L, 5L, 10L, 30L)

            while (isActive) {
                if (sessionId == null) {
                    var registered = false
                    for (backoff in registrationBackoffSec) {
                        if (!isActive) return@launch
                        if (backoff > 0) delay(backoff * 1000L)
                        val registeredNow = runCatching { registerSession() }.getOrElse { error ->
                            logger.debug("Online presence registration failed: ${error.message}")
                            false
                        }
                        if (registeredNow) {
                            registered = true
                            break
                        }
                    }
                    if (!registered) {
                        delay(30_000L)
                        continue
                    }
                }

                val jitterSec = Random.nextInt(-1, 2)
                val waitSec = (heartbeatIntervalSec + jitterSec).coerceAtLeast(5)
                delay(waitSec * 1000L)
                if (!isActive) return@launch

                val statusCode = runCatching { sendHeartbeat() }.getOrElse { error ->
                    logger.debug("Online presence heartbeat failed: ${error.message}")
                    -1
                }

                when (statusCode) {
                    200 -> Unit
                    404 -> {
                        logger.info("Online presence session expired, re-registering")
                        sessionId = null
                    }
                    -1 -> Unit
                    else -> logger.debug("Online presence heartbeat returned HTTP $statusCode")
                }
            }
        }
    }

    private fun registerSession(): Boolean {
        val appContext = get<AppContext>()
        val platform = when {
            appContext.isAndroid() -> "android"
            appContext.isDesktop() -> "windows"
            else -> {
                logger.warn("Online presence: unknown platform, skipping registration")
                return false
            }
        }

        val body = """{"platform":"$platform"}""".toRequestBody(JSON_MEDIA_TYPE)
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
                return false
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrBlank()) {
                logger.debug("Online presence registration returned empty body")
                return false
            }

            val json = Json.parse(responseBody).asObject()
            val id = json.getString("session_id", "").trim()
            if (id.isEmpty()) {
                logger.debug("Online presence registration missing session_id")
                return false
            }

            sessionId = id
            heartbeatIntervalSec = json.getInt("heartbeat_interval_sec", 15).coerceAtLeast(5)
            logger.info("Online presence session registered ($platform), interval=${heartbeatIntervalSec}s")
            return true
        }
    }

    private fun sendHeartbeat(): Int {
        val id = sessionId ?: return -1
        val request = Request.Builder()
            .url("$baseUrl/api/v1/sessions/$id/heartbeat")
            .post(EMPTY_BODY)
            .build()

        get<Net>().client.newCall(request).execute().use { response ->
            return response.code
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}
