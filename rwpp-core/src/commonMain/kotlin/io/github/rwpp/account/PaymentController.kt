/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.account

import io.github.rwpp.appKoin
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.resolveAccountApiUrl
import io.github.rwpp.config.resolveAccountAppKey
import io.github.rwpp.config.resolveOpenPlatformUrl
import io.github.rwpp.net.Net
import io.github.rwpp.net.payment.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

data class PaymentUiState(
    val visible: Boolean = false,
    val instance: Long = 0,
    val paymentId: String = "",
    val order: ClientPayment? = null,
    val security: PaymentPasswordStatus? = null,
    val busy: Boolean = false,
    val editor: String? = null,
    val error: String = "",
    val notice: String = "",
    val resendAt: Long = 0,
)

/** No PIN, code, JWT or ticket is kept in UI state, preferences or logs. */
object PaymentController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val epoch = AtomicLong()
    @Volatile private var requestJob: Job? = null
    private val mutable = MutableStateFlow(PaymentUiState())
    val state: StateFlow<PaymentUiState> = mutable
    // Only a non-secret order/account reference survives the email-bind re-login, never credentials.
    @Volatile private var resumeAfterLogin: Pair<String, String>? = null

    private data class Identity(
        val session: AccountSessionSnapshot,
        val accountId: String,
        val uasUrl: String,
        val platformUrl: String,
        val appKey: String,
    )

    private fun identity(): Identity {
        val snapshot = AccountSession.sessionSnapshotOrNull() ?: throw PaymentApiException("payment_login_required")
        val account = AccountSession.user?.accountId?.takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) }
            ?: throw PaymentApiException("payment_login_required")
        val prefs = appKoin.get<AccountPreferences>()
        return Identity(snapshot, account, resolveAccountApiUrl(prefs.apiUrl), resolveOpenPlatformUrl(), resolveAccountAppKey(prefs.appKey))
    }

    private fun current(expected: Identity, generation: Long): Boolean = epoch.get() == generation &&
        AccountSession.isCurrentSession(expected.session) && runCatching {
            val now = identity()
            expected.accountId == now.accountId && expected.uasUrl == now.uasUrl &&
                expected.platformUrl == now.platformUrl && expected.appKey == now.appKey
        }.getOrDefault(false)

    private fun clients(i: Identity): Pair<PaymentSecurityClient, PaymentClient> {
        val http = AccountSession.httpOverride ?: appKoin.get<Net>().client
        return PaymentSecurityClient(i.uasUrl, i.appKey, http) to PaymentClient(i.platformUrl, i.appKey, http)
    }

    fun open(paymentId: String = "", passwordOnly: Boolean = false) {
        resumeAfterLogin = null
        onAccountInvalidated()
        mutable.value = PaymentUiState(visible = true, instance = epoch.get(), paymentId = paymentId)
        if (paymentId.isNotBlank()) load(paymentId) else if (passwordOnly) editPassword()
    }

    fun close() {
        resumeAfterLogin = null
        onAccountInvalidated()
    }

    fun prepareEmailBinding() {
        val order = mutable.value.order
        resumeAfterLogin = order?.let { it.id to it.payerAccountId }
        onAccountInvalidated()
    }

    fun resumeForCurrentAccount() {
        val pending = resumeAfterLogin ?: return
        val account = AccountSession.user?.accountId ?: return
        resumeAfterLogin = null
        if (pending.second == account && AccountSession.loggedIn) open(pending.first)
    }

    fun onAccountInvalidated() {
        epoch.incrementAndGet()
        requestJob?.cancel()
        requestJob = null
        mutable.value = PaymentUiState(instance = epoch.get())
    }

    private fun errorKey(error: Throwable): String = when (error) {
        is PaymentApiException -> when {
            error.httpStatus == 401 -> "payment_login_required"
            error.httpStatus == 404 -> "payment_not_found"
            error.httpStatus == 429 && !error.code.contains("locked") -> "payment_rate_limited"
            else -> error.code
        }
        is IOException -> "payment_network"
        else -> "payment_unavailable"
    }

    private fun action(block: suspend (Identity, PaymentSecurityClient, PaymentClient, Long) -> Unit) {
        if (mutable.value.busy) return
        val generation = epoch.incrementAndGet()
        requestJob?.cancel()
        requestJob = scope.launch {
            var caller: Identity? = null
            try {
                caller = identity()
                val (security, platform) = clients(caller)
                mutable.value = mutable.value.copy(busy = true, error = "", notice = "")
                block(caller, security, platform, generation)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (epoch.get() == generation && (caller == null || current(caller, generation))) {
                    mutable.value = mutable.value.copy(error = errorKey(error))
                }
            } finally {
                if (epoch.get() == generation && (caller == null || current(caller, generation))) {
                    mutable.value = mutable.value.copy(busy = false)
                }
            }
        }
    }

    fun load(paymentId: String) {
        if (!PaymentRules.validId(paymentId)) {
            mutable.value = mutable.value.copy(error = "payment_invalid_id")
            return
        }
        mutable.value = mutable.value.copy(paymentId = paymentId, order = null, editor = null)
        action { i, security, platform, generation ->
            val order = platform.order(i.session.token, paymentId, i.accountId)
            val status = security.status(i.session.token)
            if (!current(i, generation)) return@action
            mutable.value = mutable.value.copy(order = order, security = status)
            pollIfProcessing(i, platform, generation)
        }
    }

    fun refresh() {
        val id = mutable.value.paymentId
        if (!PaymentRules.validId(id)) return
        action { i, security, platform, generation ->
            val order = platform.order(i.session.token, id, i.accountId)
            val status = security.status(i.session.token)
            if (!current(i, generation)) return@action
            mutable.value = mutable.value.copy(order = order, security = status)
            pollIfProcessing(i, platform, generation)
        }
    }

    fun editPassword() = action { i, security, _, generation ->
        val status = security.status(i.session.token)
        if (current(i, generation)) mutable.value = mutable.value.copy(security = status,
            editor = if (status.hasPassword) "reset" else "setup")
    }

    fun cancelEditor() {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(editor = null, error = "", notice = "")
    }

    fun sendCode() {
        if (mutable.value.resendAt > System.currentTimeMillis() / 1000) return
        action { i, security, _, generation ->
            security.sendCode(i.session.token, mutable.value.editor == "reset")
            if (current(i, generation)) mutable.value = mutable.value.copy(notice = "payment_code_sent",
                resendAt = System.currentTimeMillis() / 1000 + 60)
        }
    }

    fun savePassword(code: String, password: String) {
        if (!PaymentRules.validPin(code) || !PaymentRules.validPin(password)) return
        val reset = mutable.value.editor == "reset"
        action { i, security, _, generation ->
            security.setPassword(i.session.token, code, password, reset)
            val status = security.status(i.session.token)
            if (current(i, generation)) mutable.value = mutable.value.copy(security = status,
                editor = null, notice = "payment_password_saved", resendAt = 0)
        }
    }

    fun confirm(password: String) {
        val order = mutable.value.order ?: return
        if (order.status != "pending" || !PaymentRules.validPin(password) || mutable.value.security?.hasPassword != true) return
        action { i, security, platform, generation ->
            val authorization = security.authorize(i.session.token, order.id, password)
            if (!current(i, generation)) return@action
            mutable.value = mutable.value.copy(order = order.copy(status = "processing"))
            try {
                val result = platform.confirm(i.session.token, order.id, i.accountId, authorization.ticket)
                if (current(i, generation)) mutable.value = mutable.value.copy(order = result)
            } catch (error: IOException) {
                if (current(i, generation)) mutable.value = mutable.value.copy(notice = "payment_processing")
                // A lost response cannot prove failure. Read the same order; never create or auto-pay another.
                if (error is PaymentApiException && error.httpStatus != null) throw error
            }
            pollIfProcessing(i, platform, generation)
        }
    }

    private suspend fun pollIfProcessing(i: Identity, platform: PaymentClient, generation: Long) {
        // Leave controls usable while polling; close/account invalidation cancels this job and the HTTP call.
        if (current(i, generation)) mutable.value = mutable.value.copy(busy = false)
        repeat(30) {
            if (!current(i, generation) || mutable.value.order?.status !in setOf("preparing", "processing", "closing")) return
            delay(2000)
            val result = platform.order(i.session.token, mutable.value.paymentId, i.accountId)
            if (!current(i, generation)) return
            mutable.value = mutable.value.copy(order = result)
        }
    }
}
