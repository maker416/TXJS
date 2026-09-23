/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import kotlinx.serialization.Serializable
import org.koin.core.annotation.Single

/** 统一账号 JSON API 默认基址（不含路径）。可用 [AccountPreferences.apiUrl] 或环境变量 `RWJS_ACCOUNT_API_URL` 覆盖。 */
const val DEFAULT_ACCOUNT_API_URL = "http://210.16.170.71:11455"

/**
 * 统一账号客户端配置与会话持久化。
 *
 * 不读写 [CoreData.loginCookie] / `userId`，与 BBS / rtsbox 隔离。
 * AppKey 可被设置页、环境变量 `RWJS_ACCOUNT_APP_KEY` 或系统属性 `rwjs.account.appKey` 覆盖。
 */
@Single
@Serializable
data class AccountPreferences(
    var apiUrl: String = DEFAULT_ACCOUNT_API_URL,
    /** 空则使用出厂默认；可用设置页或环境变量覆盖。 */
    var appKey: String = "",
    var token: String = "",
    var lastUsername: String = "",
) : Config

/**
 * 出厂默认 AppKey，保证未改配置也能连上默认服务器。
 * 不要把该值写进 PR / 提交说明 / 用户可见文档。
 */
internal const val DEFAULT_ACCOUNT_APP_KEY = "ak_607b04bac2c96567ea132fed5a84652c"

/**
 * 上一代出厂默认 AppKey。老用户的配置里可能已通过设置页持久化了该值，
 * 解析时按空处理以回落到新默认，避免换 Key 对这部分用户不生效。
 */
internal const val LEGACY_ACCOUNT_APP_KEY = "ak_5980ceebf89affa4b88ecdf3c9a72456"

fun resolveAccountApiUrl(stored: String): String {
    val override = System.getProperty("rwjs.account.apiUrl")
        ?: System.getenv("RWJS_ACCOUNT_API_URL")
    val raw = override?.trim().orEmpty().ifBlank { stored.trim() }
    return raw.ifBlank { DEFAULT_ACCOUNT_API_URL }.trimEnd('/')
}

fun resolveAccountAppKey(stored: String): String {
    val override = System.getProperty("rwjs.account.appKey")
        ?: System.getenv("RWJS_ACCOUNT_APP_KEY")
    if (!override.isNullOrBlank()) return override.trim()
    val raw = stored.trim()
    if (raw.isBlank() || raw == LEGACY_ACCOUNT_APP_KEY) return DEFAULT_ACCOUNT_APP_KEY
    return raw
}
