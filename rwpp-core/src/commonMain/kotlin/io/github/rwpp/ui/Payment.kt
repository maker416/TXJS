/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.PaymentController
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.payment.PaymentRules
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.UiCircularProgressIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject

internal fun paymentText(key: String, vararg args: String) = readI18n("payment.$key", I18nType.RWPP, *args)

internal fun paymentError(code: String): String = paymentText(when {
    code == "payment_https_required" -> "httpsRequired"
    code == "payment_login_required" -> "loginRequired"
    code == "payment_invalid_id" -> "invalidId"
    code == "payment_not_found" -> "notFound"
    code == "payment_network" -> "network"
    code.contains("locked") -> "locked"
    code.contains("rate_limit") || code.contains("too_frequent") -> "rateLimited"
    code.contains("email") && (code.contains("bound") || code.contains("required")) -> "emailRequired"
    code.contains("code") && (code.contains("invalid") || code.contains("expired")) -> "invalidCode"
    code.contains("password") && (code.contains("invalid") || code.contains("incorrect")) -> "wrongPassword"
    code.contains("password") && (code.contains("not_set") || code.contains("required")) -> "setupRequired"
    code.contains("insufficient") -> "insufficient"
    code.contains("closed") || code.contains("expired") -> "expired"
    else -> "unavailable"
})

/** PIN and codes are plain remember state, never saveable, and destroyed on close/account change. */
@Composable
internal fun PaymentDialog(onBindEmail: () -> Unit) {
    val state by PaymentController.state.collectAsState()
    val identity = AccountSession.user?.accountId
    var id by remember(state.instance, identity) { mutableStateOf(state.paymentId) }
    var pin by remember(state.instance, identity, state.editor, state.order?.status) { mutableStateOf("") }
    var confirmation by remember(state.instance, identity, state.editor) { mutableStateOf("") }
    var code by remember(state.instance, identity, state.editor) { mutableStateOf("") }
    var localError by remember(state.instance, identity, state.editor) { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(state.visible) {
        if (state.visible) while (true) { now = System.currentTimeMillis() / 1000; delay(1000) }
    }
    LaunchedEffect(state.paymentId) { if (state.paymentId.isNotBlank()) id = state.paymentId }
    LaunchedEffect(state.order?.id, state.order?.status) {
        if (state.order?.status in setOf("succeeded", "refunded") && AccountSession.networkEnabled) {
            try { AccountSession.refreshPoints() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Payment result remains authoritative when balance refresh is offline. */ }
        }
    }
    DisposableEffect(Unit) { onDispose { PaymentController.close() } }
    AnimatedAlertDialog(
        visible = state.visible,
        onDismissRequest = { PaymentController.close() },
        // Closing cancels UI requests only; an accepted payment remains queryable by its original ID.
        enableDismiss = true,
    ) { dismiss ->
        AccountAccessCard(
            title = paymentText(if (state.editor == "setup") "setup" else if (state.editor == "reset") "reset" else "title"),
            subtitle = paymentText("subtitle"), busy = false, onClose = dismiss,
        ) {
            if (state.editor != null) {
                val security = state.security
                if (security?.emailBound != true) {
                    AccountMessageBanner(paymentText("emailRequired"))
                    AccountPrimaryButton(paymentText("bindEmail"), onBindEmail, Modifier.fillMaxWidth(), enabled = !state.busy)
                } else {
                    Text(paymentText("emailTarget", security.emailMasked), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccountAccessField(paymentText("code"), code, Modifier.weight(1f), enabled = !state.busy,
                            keyboardType = KeyboardType.Number,
                            onValueChange = { code = it.filter { digit -> digit in '0'..'9' }.take(6) })
                        val wait = (state.resendAt - now).coerceAtLeast(0)
                        AccountCompactButton(if (wait > 0) paymentText("resend", wait.toString()) else paymentText("sendCode"),
                            onClick = { PaymentController.sendCode() }, enabled = !state.busy && wait == 0L)
                    }
                    AccountAccessField(paymentText("newPin"), pin, Modifier.fillMaxWidth(), enabled = !state.busy,
                        visualTransformation = PasswordVisualTransformation(), keyboardType = KeyboardType.NumberPassword,
                        onValueChange = { pin = it.filter { digit -> digit in '0'..'9' }.take(6) })
                    AccountAccessField(paymentText("confirmPin"), confirmation, Modifier.fillMaxWidth(), enabled = !state.busy,
                        visualTransformation = PasswordVisualTransformation(), keyboardType = KeyboardType.NumberPassword,
                        onValueChange = { confirmation = it.filter { digit -> digit in '0'..'9' }.take(6) })
                    AccountPrimaryButton(paymentText("savePin"), {
                        if (pin != confirmation) localError = paymentText("mismatch")
                        else {
                            val submittedPin = pin
                            val submittedCode = code
                            pin = ""; confirmation = ""; code = ""; localError = ""
                            PaymentController.savePassword(submittedCode, submittedPin)
                        }
                    }, Modifier.fillMaxWidth(), enabled = !state.busy && PaymentRules.validPin(pin) && PaymentRules.validPin(confirmation) && PaymentRules.validPin(code))
                }
                TextButton(onClick = { PaymentController.cancelEditor() }, enabled = !state.busy) { Text(paymentText("backToOrder")) }
            } else {
                if (state.order == null) {
                    AccountAccessField(paymentText("orderId"), id, Modifier.fillMaxWidth(), enabled = !state.busy,
                        onValueChange = { id = it.trim().take(36) })
                    AccountPrimaryButton(paymentText("loadOrder"), { PaymentController.load(id) }, Modifier.fillMaxWidth(),
                        enabled = !state.busy && PaymentRules.validId(id))
                    TextButton(onClick = { PaymentController.editPassword() }, enabled = !state.busy) { Text(paymentText("managePin")) }
                } else {
                    val order = state.order!!
                    Text(order.appName, style = MaterialTheme.typography.titleMedium)
                    Text(order.subject, style = MaterialTheme.typography.bodyLarge)
                    Text(paymentText("amount", order.points.toString()), style = MaterialTheme.typography.headlineSmall)
                    AccountSession.points.firstOrNull { it.id == order.pointTypeId }?.let {
                        Text(paymentText("balance", it.balance.toString()), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(paymentText("orderId") + ": " + order.id, style = MaterialTheme.typography.bodySmall)
                    when (order.status) {
                        "succeeded" -> AccountMessageBanner(paymentText("succeeded"))
                        "closed" -> AccountMessageBanner(paymentText("expired"), isError = true)
                        "refunded" -> AccountMessageBanner(paymentText("refunded"))
                        "preparing", "processing" -> AccountMessageBanner(paymentText("processing"))
                        "closing" -> AccountMessageBanner(paymentText("closing"))
                        "pending" -> {
                            if (order.lastError.isNotBlank()) AccountMessageBanner(paymentError(order.lastError), isError = true)
                            val security = state.security
                            when {
                                order.expiresAt <= now -> AccountMessageBanner(paymentText("expired"), isError = true)
                                security?.hasPassword != true -> {
                                    AccountMessageBanner(paymentText("setupRequired"))
                                    AccountPrimaryButton(paymentText("setup"), { PaymentController.editPassword() }, Modifier.fillMaxWidth(), enabled = !state.busy)
                                }
                                security.lockedUntil > now -> AccountMessageBanner(paymentText("locked"), isError = true)
                                else -> {
                                    AccountAccessField(paymentText("pin"), pin, Modifier.fillMaxWidth(), enabled = !state.busy,
                                        visualTransformation = PasswordVisualTransformation(), keyboardType = KeyboardType.NumberPassword,
                                        onValueChange = { pin = it.filter { digit -> digit in '0'..'9' }.take(6) })
                                    AccountPrimaryButton(paymentText("confirmPay"), {
                                        val submitted = pin; pin = ""; PaymentController.confirm(submitted)
                                    }, Modifier.fillMaxWidth(), enabled = !state.busy && PaymentRules.validPin(pin))
                                }
                            }
                            TextButton(onClick = { pin = ""; PaymentController.editPassword() }, enabled = !state.busy) { Text(paymentText("forgotPin")) }
                        }
                    }
                    if (!order.terminal) TextButton(onClick = { PaymentController.refresh() }, enabled = !state.busy) { Text(paymentText("refresh")) }
                }
            }
            if (state.busy) UiCircularProgressIndicator(Modifier.size(24.dp))
            if (localError.isNotBlank()) AccountMessageBanner(localError, isError = true)
            if (state.error.isNotBlank()) AccountMessageBanner(paymentError(state.error), isError = true)
            if (state.notice.isNotBlank()) AccountMessageBanner(paymentText(when (state.notice) {
                "payment_password_saved" -> "saved"
                "payment_code_sent" -> "codeSent"
                else -> "processing"
            }))
        }
    }
}
