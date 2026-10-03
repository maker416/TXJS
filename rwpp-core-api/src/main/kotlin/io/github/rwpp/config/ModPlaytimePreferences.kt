/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import io.github.rwpp.net.playtime.PendingModPlaytimeReport
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koin.core.annotation.Single

/** 模组云端游玩时长服务基址；设置、环境变量或系统属性可以覆盖。 */
const val DEFAULT_MOD_PLAYTIME_API_URL = "http://210.16.170.71:11456"

@Single
@Serializable
data class ModPlaytimePreferences(
    /** 默认连接云端统计服务；显式空值关闭云端统计。 */
    @Volatile var apiUrl: String = DEFAULT_MOD_PLAYTIME_API_URL,
    @Volatile var pendingReports: List<PendingModPlaytimeReport> = emptyList(),
) : Config

fun resolveModPlaytimeApiUrl(stored: String): String {
    val raw = (System.getProperty("rwjs.modPlaytime.apiUrl")
        ?: System.getenv("RWJS_MOD_PLAYTIME_API_URL") ?: stored).trim().trimEnd('/')
    val url = raw.toHttpUrlOrNull() ?: return ""
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return ""
    return raw
}
