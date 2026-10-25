/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.net.feedback

import io.github.rwpp.diagnostics.LogArchive
import io.github.rwpp.net.useCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class FeedbackReport(
    @SerialName("request_id") val requestId: String,
    val title: String,
    val description: String,
    val contact: String = "",
    val platform: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("app_version") val appVersion: String,
    val channel: String,
    @SerialName("submitted_at") val submittedAt: String,
    @SerialName("log_days") val logDays: List<String>,
)

@Serializable
data class FeedbackReceipt(val id: String, @SerialName("received_at") val receivedAt: String)

class FeedbackClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun submit(baseUrl: String, report: FeedbackReport, archive: LogArchive): FeedbackReceipt {
        require(report.title.isNotBlank() && report.title.length <= 120) { "请填写 1–120 字的问题标题。" }
        require(report.description.isNotBlank() && report.description.length <= 10000) { "请填写问题描述（最多 10000 字）。" }
        require(report.contact.length <= 200) { "联系方式最多 200 字。" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", json.encodeToString(report))
            .addFormDataPart("logs", "logs.zip", archive.file.asRequestBody("application/zip".toMediaType()))
            .build()
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/v1/feedback").post(body).build()
        return withContext(Dispatchers.IO) { http.useCancellable(request) {
            when (it.code) {
                413 -> throw IOException("日志包超过服务端限制，请联系开发者。")
                429 -> throw IOException("提交过于频繁，请稍后重试。")
            }
            if (!it.isSuccessful) throw IOException("反馈提交失败（HTTP ${it.code}），请稍后重试。")
            val source = it.body?.source() ?: throw IOException("服务端未返回反馈编号。")
            if (source.request(16 * 1024L)) throw IOException("反馈响应过大。")
            json.decodeFromString<FeedbackReceipt>(source.readUtf8()).also { receipt ->
                if (receipt.id.isBlank() || receipt.receivedAt.isBlank()) throw IOException("服务端未返回有效的反馈编号。")
            }
        } }
    }
}
