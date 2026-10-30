/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import io.github.rwpp.i18n.readI18n

private val connectTimeoutMessage = Regex(
    """(?:Connection failed: )?Failed to connect to host - failed to connect to /?(\S+) \(port (\d+)\)(?: from /?\S+ \(port \d+\))? after (\d+)ms\.?""",
    RegexOption.IGNORE_CASE,
)

fun joinFailureMessage(message: String): String {
    val match = connectTimeoutMessage.matchEntire(message) ?: return message
    val (host, port, timeoutMs) = match.destructured
    return readI18n("multiplayer.joinConnectTimeout", arg = arrayOf(host, port, timeoutMs))
}
