/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.net.browser

import java.net.IDN
import java.net.URI

/** Gecko's displaySpec uses Unicode IDN hosts; the configured/login URL uses ASCII.
 * Restore only that configured host, preserving scheme, path, query, fragment and port exactly.
 * This is not a general URL/origin comparison: credentials or another authority never match.
 */
fun browserCanonicalDisplayUrl(url: String, configuredUrl: String): String = runCatching {
    val configured = URI(configuredUrl)
    val host = configured.host ?: return url
    val authority = configured.rawAuthority ?: return url
    if (configured.rawUserInfo != null) return url
    val unicode = IDN.toUnicode(host)
    if (unicode == host) return url
    val displayAuthority = authority.replace(host, unicode)
    val candidate = URI(url)
    if (candidate.rawAuthority != displayAuthority) return url
    val authorityStart = url.indexOf("//") + 2
    url.replaceRange(authorityStart, authorityStart + displayAuthority.length, authority)
}.getOrDefault(url)
