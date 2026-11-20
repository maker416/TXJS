/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.payment

import io.github.rwpp.config.normalizeOpenPlatformUrl
import io.github.rwpp.net.useCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** An order is always fetched from the official platform, never trusted from game chat. */
@Serializable
data class ClientPayment(
    val id: String,
    @SerialName("app_id") val appId: String,
    @SerialName("app_name") val appName: String,
    val subject: String,
    @SerialName("payer_account_id") val payerAccountId: String,
    @SerialName("point_type_id") val pointTypeId: Long,
    val points: Long,
    val status: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("last_error") val lastError: String = "",
) {
    fun validFor(paymentId: String, accountId: String) = id == paymentId && PaymentRules.validId(id) &&
        payerAccountId == accountId && pointTypeId > 0 && points in 1..9007199254740991 &&
        appName.isNotBlank() && appName.length <= 256 && subject.length in 1..512 && expiresAt > 0 &&
        status in setOf("preparing", "pending", "processing", "closing", "succeeded", "closed", "refunded")
    val terminal: Boolean get() = status in setOf("succeeded", "closed", "refunded")
}

@Serializable
data class PaymentPasswordStatus(
    @SerialName("has_password") val hasPassword: Boolean,
    @SerialName("email_masked") val emailMasked: String = "",
    @SerialName("email_bound") val emailBound: Boolean = false,
    @SerialName("locked_until") val lockedUntil: Long = 0,
)

/** Ticket has deliberately no generated toString containing the bearer material. */
class PaymentAuthorization(val ticket: String, val expiresAt: Long) {
    override fun toString() = "PaymentAuthorization(redacted)"
}

class PaymentApiException(val code: String, val httpStatus: Int? = null) : IOException(code)

object PaymentRules {
    private val id = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun validId(value: String) = id.matches(value)
    fun validPin(value: String) = value.length == 6 && value.all { it in '0'..'9' }
    fun chatPayment(message: String): String? = message.takeIf { it.startsWith("[RWJSPAY1] ") }
        ?.removePrefix("[RWJSPAY1] ")?.takeIf(::validId)
}

/** Separate trusted transport: HTTPS only (literal loopback development excepted), no redirects. */
internal class PaymentTransport(baseUrl: String, http: OkHttpClient) {
    private val base = normalizeOpenPlatformUrl(baseUrl)?.toHttpUrl()
        ?: throw PaymentApiException("payment_https_required")
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS).apply {
            if (!base.isHttps) proxy(Proxy.NO_PROXY)
        }.build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun request(path: String, appKey: String, token: String, body: JsonObject? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val url = base.newBuilder().apply {
                path.split('/').filter(String::isNotEmpty).forEach(::addPathSegment)
            }.build()
            val builder = Request.Builder().url(url).header("Accept", "application/json")
                .header("X-App-Key", appKey).header("Authorization", "Bearer $token")
            if (body != null) builder.post(body.toString().toRequestBody(JSON_TYPE))
            client.useCancellable(builder.build()) { response ->
                val content = response.body ?: throw PaymentApiException("payment_invalid_response")
                if (content.contentLength() > MAX_BYTES) throw PaymentApiException("payment_invalid_response")
                val source = content.source()
                source.request(MAX_BYTES + 1)
                if (source.buffer.size > MAX_BYTES) throw PaymentApiException("payment_invalid_response")
                val parsed = runCatching { json.parseToJsonElement(source.readUtf8()).jsonObject }.getOrNull()
                if (!response.isSuccessful) {
                    val code = (parsed?.get("code") ?: parsed?.get("error"))?.jsonPrimitive?.contentOrNull
                    throw PaymentApiException(code?.takeIf { it.matches(Regex("[a-z_]{1,80}")) }
                        ?: "payment_unavailable", response.code)
                }
                parsed ?: throw PaymentApiException("payment_invalid_response")
            }
        }

    companion object {
        private const val MAX_BYTES = 128L * 1024
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** UAS alone receives the PIN and email code. The platform and game never receive them. */
class PaymentSecurityClient(baseUrl: String, private val appKey: String, http: OkHttpClient = OkHttpClient()) {
    private val transport = PaymentTransport(baseUrl, http)
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun status(token: String): PaymentPasswordStatus =
        json.decodeFromJsonElement(transport.request("api/v1/users/payment-password", appKey, token))
    suspend fun sendCode(token: String, reset: Boolean) {
        transport.request("api/v1/users/payment-password/send-code", appKey, token,
            buildJsonObject { put("purpose", if (reset) "reset" else "setup") })
    }
    suspend fun setPassword(token: String, code: String, password: String, reset: Boolean) {
        require(PaymentRules.validPin(code) && PaymentRules.validPin(password))
        transport.request("api/v1/users/payment-password/${if (reset) "reset" else "setup"}", appKey, token,
            buildJsonObject { put("code", code); put("password", password) })
    }
    suspend fun authorize(token: String, paymentId: String, password: String): PaymentAuthorization {
        require(PaymentRules.validId(paymentId) && PaymentRules.validPin(password))
        val result = transport.request("api/v1/payment-authorizations", appKey, token,
            buildJsonObject { put("payment_id", paymentId); put("payment_password", password) })
        val ticket = result["ticket"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val expires = result["expires_at"]?.jsonPrimitive?.longOrNull ?: 0
        if (!ticket.matches(Regex("[0-9a-f]{64}")) || expires <= System.currentTimeMillis() / 1000) {
            throw PaymentApiException("payment_invalid_response")
        }
        return PaymentAuthorization(ticket, expires)
    }
    suspend fun sendOldEmailCode(token: String) {
        transport.request("api/v1/users/change-email/send-old-code", appKey, token, buildJsonObject {})
    }
}

/** Fixed official platform only. All order reads are authenticated as the expected payer. */
class PaymentClient(baseUrl: String, private val appKey: String, http: OkHttpClient = OkHttpClient()) {
    private val transport = PaymentTransport(baseUrl, http)
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun order(token: String, paymentId: String, accountId: String): ClientPayment =
        read(token, paymentId, accountId, null)
    suspend fun confirm(token: String, paymentId: String, accountId: String, ticket: String): ClientPayment {
        require(ticket.matches(Regex("[0-9a-f]{64}")))
        return read(token, paymentId, accountId, buildJsonObject { put("ticket", ticket) })
    }
    private suspend fun read(token: String, id: String, accountId: String, body: JsonObject?): ClientPayment {
        require(PaymentRules.validId(id))
        val result = transport.request("api/v1/client/payments/$id${if (body != null) "/confirm" else ""}", appKey, token, body)
        val data = result["data"] ?: throw PaymentApiException("payment_invalid_response")
        return json.decodeFromJsonElement<ClientPayment>(data).also {
            if (!it.validFor(id, accountId)) throw PaymentApiException("payment_invalid_response")
        }
    }
}
