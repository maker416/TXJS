/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import io.github.rwpp.rwpp_core_api.BuildConfig

/**
 * 在线人数/会话 Presence 服务 base URL（不含路径）。
 *
 * 注意子域是 **`oline`**（不是 `online`）：线上即按此主机名部署，属有意拼写，勿「纠正」为 online。
 */
const val DEFAULT_ONLINE_PRESENCE_API_URL = "http://oline.xn--rhqr8xvr4ahqsgka.com:11451"

/**
 * 安装包来源渠道标识，随注册 Session 上报，用于按渠道统计新增/留存（如广告投放效果）。
 * 打包时通过 Gradle 属性传入：`gradlew -PonlineChannel=tt_ad ...`，默认 "official"。
 */
const val DEFAULT_ONLINE_CHANNEL: String = BuildConfig.ONLINE_CHANNEL

fun normalizeOnlinePresenceBaseUrl(url: String): String =
    url.trim().trimEnd('/')
