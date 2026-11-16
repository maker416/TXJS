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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
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

/** 公开状态回执不含描述、联系方式、账号或管理员内部备注。 */
@Serializable
data class FeedbackStatus(
    val id: String,
    val status: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("public_reply") val publicReply: String = "",
)

@Serializable
data class AccountFeedback(
    val id: String,
    @SerialName("request_id") val requestId: String = "",
    val title: String,
    val description: String,
    val contact: String = "",
    val platform: String = "",
    @SerialName("os_version") val osVersion: String = "",
    @SerialName("app_version") val appVersion: String = "",
    val channel: String = "",
    @SerialName("submitted_at") val submittedAt: String = "",
    @SerialName("log_days") val logDays: List<String> = emptyList(),
    @SerialName("log_bytes") val logBytes: Long = 0,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("updated_at") val updatedAt: String = "",
    val status: String,
    @SerialName("public_reply") val publicReply: String = "",
)

@Serializable
data class AccountFeedbackPage(
    val items: List<AccountFeedback>,
    val total: Int,
    @SerialName("next_offset") val nextOffset: Int,
)

class FeedbackCredentials(val appKey: String, val token: String) {
    init { require(appKey.isNotBlank() && token.isNotBlank()) }
    override fun toString() = "FeedbackCredentials([redacted])"
}

class FeedbackException(message: String, val statusCode: Int? = null) : IOException(message)

class FeedbackClient(http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    // 本机 HTTP 仅用于联调，既不经过系统代理，也不把 localhost 交给可改写的 DNS。
    private val loopbackHttp = this.http.newBuilder().proxy(Proxy.NO_PROXY).dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
            "localhost", "127.0.0.1" -> listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            "::1" -> listOf(InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 }))
            else -> throw IOException("本机反馈连接不能解析外部地址。")
        }
    }).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun submit(baseUrl: String, report: FeedbackReport, archive: LogArchive, credentials: FeedbackCredentials? = null): FeedbackReceipt {
        require(report.title.isNotBlank() && report.title.length <= 120) { "请填写 1–120 字的问题标题。" }
        require(report.description.isNotBlank() && report.description.length <= 10000) { "请填写问题描述（最多 10000 字）。" }
        require(report.contact.length <= 200) { "联系方式最多 200 字。" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", json.encodeToString(report))
            .addFormDataPart("logs", "logs.zip", archive.file.asRequestBody("application/zip".toMediaType()))
            .build()
        val request = request(baseUrl, "", credentials).post(body).build()
        return execute<FeedbackReceipt>(request, 16 * 1024L).also { receipt ->
            if (receipt.id != report.requestId || receipt.receivedAt.isBlank()) throw FeedbackException("服务端未返回匹配的反馈编号。")
        }
    }

    suspend fun status(baseUrl: String, id: String): FeedbackStatus {
        require(id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) { "反馈编号格式不正确。" }
        return execute<FeedbackStatus>(request(baseUrl, "/$id/status", null).get().build(), 32 * 1024L).also {
            if (it.id != id) throw FeedbackException("服务端返回了不匹配的反馈编号。")
        }
    }

    suspend fun mine(baseUrl: String, credentials: FeedbackCredentials, offset: Int = 0): AccountFeedbackPage {
        require(offset >= 0)
        return execute(request(baseUrl, "/mine?offset=$offset", credentials).get().build(), 4 * 1024 * 1024L)
    }

    suspend fun detail(baseUrl: String, credentials: FeedbackCredentials, id: String): AccountFeedback {
        require(id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
        return execute<AccountFeedback>(request(baseUrl, "/$id", credentials).get().build(), 128 * 1024L).also {
            if (it.id != id) throw FeedbackException("服务端返回了不匹配的反馈编号。")
        }
    }

    private fun request(baseUrl: String, suffix: String, credentials: FeedbackCredentials?): Request.Builder {
        val base = baseUrl.trim().trimEnd('/').toHttpUrl()
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "反馈服务地址不正确。" }
        if (credentials != null) {
            require(base.isHttps || base.host in setOf("127.0.0.1", "localhost", "::1")) { "账号反馈需要 HTTPS 连接，请更新反馈服务地址；仍可匿名提交。" }
        }
        return Request.Builder().url(base.toString().trimEnd('/') + "/v1/feedback" + suffix).apply {
            if (credentials != null) {
                header("X-App-Key", credentials.appKey)
                header("Authorization", "Bearer ${credentials.token}")
            }
        }
    }

    private suspend inline fun <reified T> execute(request: Request, limit: Long): T = withContext(Dispatchers.IO) {
        val client = if (request.url.scheme == "http" && request.header("Authorization") != null) loopbackHttp else http
        client.useCancellable(request) { response ->
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401, 403 -> "账号登录已失效或无权访问，请重新登录后重试。"
                    404 -> "暂未查到此反馈，可能尚未提交成功或服务端尚未升级。"
                    413 -> "日志包超过服务端限制，请联系开发者。"
                    429 -> "请求过于频繁，请稍后重试。"
                    else -> "反馈请求失败（HTTP ${response.code}），请稍后重试。"
                }
                throw FeedbackException(message, response.code)
            }
            val source = response.body?.source() ?: throw FeedbackException("服务端未返回反馈信息。")
            if (source.request(limit)) throw FeedbackException("反馈响应过大。")
            json.decodeFromString<T>(source.readUtf8())
        }
    }
}
