/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

/** Base URL of the online presence service (no trailing path). */
const val DEFAULT_ONLINE_PRESENCE_API_URL = "http://210.16.166.71:11451"

fun normalizeOnlinePresenceBaseUrl(url: String): String =
    url.trim().trimEnd('/')
