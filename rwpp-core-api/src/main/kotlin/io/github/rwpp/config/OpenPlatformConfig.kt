/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import io.github.rwpp.rwpp_core_api.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Volatile
private var savedOpenPlatformUrl: String? = null

/** 启动时读取已保存地址，使本次运行的开放平台会话使用同一个入口。 */
fun initializeOpenPlatformUrl(stored: String) {
    savedOpenPlatformUrl = normalizeOpenPlatformUrl(stored)
}

/** 开放平台入口只接受公网 HTTPS 或本机 HTTP；非法运行时覆盖回落到构建时的入口。 */
fun normalizeOpenPlatformUrl(raw: String): String? {
    val value = raw.trim().trimEnd('/')
    val url = value.toHttpUrlOrNull() ?: return null
    if (!url.isHttps && url.host !in setOf("localhost", "127.0.0.1", "::1")) return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
    return value
}

fun resolveOpenPlatformUrl(): String =
    normalizeOpenPlatformUrl(System.getProperty("rwjs.openPlatform.url")
        ?: System.getenv("RWJS_OPEN_PLATFORM_URL") ?: savedOpenPlatformUrl ?: BuildConfig.OPEN_PLATFORM_URL)
        ?: BuildConfig.OPEN_PLATFORM_URL
