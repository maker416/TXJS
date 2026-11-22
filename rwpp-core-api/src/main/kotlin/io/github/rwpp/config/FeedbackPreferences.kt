/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.config

import io.github.rwpp.rwpp_core_api.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.koin.core.annotation.Single

@Single
@Serializable
data class FeedbackPreferences(
    @Transient @Volatile var apiUrl: String = "",
    /** 仅保存回执编号和身份分组，不保存正文、日志、账号密码或 Token。 */
    var history: List<FeedbackHistoryEntry> = emptyList(),
) : Config

@Serializable
data class FeedbackHistoryEntry(val endpoint: String, val owner: String = "", val id: String)

fun resolveFeedbackApiUrl(stored: String): String =
    (System.getProperty("rwjs.feedback.apiUrl") ?: System.getenv("RWJS_FEEDBACK_API_URL")
        ?: stored.ifBlank { BuildConfig.FEEDBACK_API_URL }).trim().trimEnd('/')
