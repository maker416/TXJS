/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.account.ForumSsoRequiresHttpsException
import io.github.rwpp.net.account.ForumSsoStep
import io.github.rwpp.net.account.ForumSsoStepException
import java.io.IOException

/** Only locally defined text and a numeric HTTP status reach the UI. */
internal fun forumSsoErrorText(error: Exception, text: (String) -> String = { readI18n(it) }): String {
    if (error is ForumSsoRequiresHttpsException) return text("browser.httpsRequired")
    if (error !is ForumSsoStepException) return text("browser.autoLoginFailed")
    val reason = when {
        error.step == ForumSsoStep.REVOKE -> "revoke"
        error.step == ForumSsoStep.SAVE -> "save"
        error.statusCode in listOf(401, 403) && error.step == ForumSsoStep.TICKET -> "ticketDenied"
        error.statusCode in listOf(401, 403) -> "denied"
        error.statusCode == 404 -> "unavailable"
        error.statusCode == 419 -> "csrf"
        error.statusCode == 429 -> "limited"
        error.statusCode?.let { it in 300..399 } == true -> "redirect"
        error.statusCode?.let { it in 500..599 } == true -> "server"
        error.statusCode == null && error.cause is IOException -> "network"
        else -> "response"
    }
    val status = error.statusCode?.let { " (HTTP $it)" }.orEmpty()
    return text("browser.autoLoginFailed") + "\n" + text("browser.ssoStage_${error.step.name.lowercase()}") + status + " — " + text("browser.ssoReason_$reason")
}
