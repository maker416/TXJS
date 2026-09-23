/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.account

import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.account.AccountApiException
import io.github.rwpp.net.account.AccountErrorCode

fun accountErrorText(error: AccountApiException): String {
    val serverMessage = error.message.orEmpty()
    if (error.code == AccountErrorCode.FEATURE_DISABLED &&
        serverMessage.isNotBlank() &&
        serverMessage != "this feature is disabled"
    ) {
        return serverMessage
    }
    val key = when (error.code) {
        AccountErrorCode.UNAUTHORIZED -> "accountError.unauthorized"
        AccountErrorCode.RATE_LIMITED -> "accountError.rateLimited"
        AccountErrorCode.APP_DISABLED -> "accountError.appDisabled"
        AccountErrorCode.USER_DISABLED -> "accountError.userDisabled"
        AccountErrorCode.FEATURE_DISABLED -> "accountError.featureDisabled"
        AccountErrorCode.ALREADY_FRIENDS -> "accountError.alreadyFriends"
        AccountErrorCode.REQUEST_PENDING -> "accountError.requestPending"
        AccountErrorCode.BLOCKED -> "accountError.blocked"
        AccountErrorCode.NOT_FRIENDS -> "accountError.notFriends"
        AccountErrorCode.INVALID_REQUEST -> "accountError.invalidRequest"
        AccountErrorCode.INVALID_CODE -> "accountError.invalidCode"
        AccountErrorCode.NICKNAME_UNCHANGED -> "accountError.nicknameUnchanged"
        AccountErrorCode.EMAIL_UNCHANGED -> "accountError.emailUnchanged"
        AccountErrorCode.NOT_FOUND -> "accountError.notFound"
        AccountErrorCode.USERNAME_TAKEN -> "accountError.usernameTaken"
        AccountErrorCode.EMAIL_TAKEN -> "accountError.emailTaken"
        AccountErrorCode.CODE_TOO_FREQUENT -> "accountError.codeTooFrequent"
        AccountErrorCode.NICKNAME_CHANGE_TOO_FREQUENT -> "accountError.nicknameTooFrequent"
        AccountErrorCode.MAIL_NOT_CONFIGURED -> "accountError.mailNotConfigured"
        AccountErrorCode.MAIL_SEND_FAILED -> "accountError.mailSendFailed"
        AccountErrorCode.INTERNAL_ERROR -> "accountError.internal"
        AccountApiException.NETWORK -> "accountError.network"
        else -> "accountError.generic"
    }
    return runCatching { readI18n(key, I18nType.RWPP, serverMessage) }
        .getOrElse { serverMessage.ifBlank { error.code } }
}

fun accountLoginUnauthorizedText(error: AccountApiException): String {
    return if (error.code == AccountErrorCode.UNAUTHORIZED) {
        readI18n("accountError.badCredentials", I18nType.RWPP)
    } else {
        accountErrorText(error)
    }
}
