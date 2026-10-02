/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.playtime

import io.github.rwpp.net.useCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

class ModPlaytimeHttpException(val status: Int) : IOException("Mod playtime HTTP $status")

/** 网络请求在 IO 执行，并随协程取消关闭连接，不阻塞引擎主循环。 */
class ModPlaytimeClient(baseUrl: String, private val http: OkHttpClient) {
    private val endpoint = baseUrl.trim().trimEnd('/') + "/api/v1/playtime/reports"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun report(token: String, report: ModPlaytimeReport): ModPlaytimeReceipt = withContext(Dispatchers.IO) {
        require(token.isNotBlank())
        require(UUID.fromString(report.sessionId).toString() == report.sessionId)
        require(report.elapsedSeconds in 0..MAX_SESSION_SECONDS)
        require(report.mods.size in 1..128)
        require(report.mods.all {
            it.name.isNotBlank() && it.name.codePointCount(0, it.name.length) <= 255 &&
                it.name.none { c -> c.isISOControl() } && it.sha256.matches(Regex("[0-9a-f]{64}"))
        })
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .post(json.encodeToString(report).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.useCancellable(request) { response ->
            if (!response.isSuccessful) throw ModPlaytimeHttpException(response.code)
            val receipt = json.decodeFromString<ModPlaytimeReceipt>(response.body?.string() ?: throw IOException("Empty playtime response"))
            if (receipt.sessionId != report.sessionId || receipt.elapsedSeconds < report.elapsedSeconds ||
                receipt.addedSeconds < 0 || report.ended && !receipt.ended
            ) throw IOException("Invalid playtime acknowledgement")
            receipt
        }
    }

    companion object { const val MAX_SESSION_SECONDS = 604800L }
}
