/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync.direct

/** 仅表示真实主机地址；短码与快速建房指令不属于 Direct 地址。 */
data class DirectJoinEndpoint(val host: String, val port: Int = DEFAULT_PORT) {
    init {
        require(host.isNotBlank()) { "Direct host must not be blank" }
        require(port in 1..65535) { "Invalid direct port: $port" }
    }

    override fun toString(): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    companion object {
        const val DEFAULT_PORT = 5123
        private val HOST = Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?")
        private val IPV6 = Regex("[0-9A-Fa-f:.]+")

        fun parseOrNull(rawAddress: String): DirectJoinEndpoint? {
            val address = rawAddress.trim()
            if (address.isEmpty() || address.length > 512 || address.any { it.isWhitespace() || it == '\r' || it == '\n' }) return null
            if (address.startsWith("get|", ignoreCase = true) || address.startsWith("Qnews", ignoreCase = true)) return null
            if (address.matches(Regex("[QRT][A-Za-z0-9]+", RegexOption.IGNORE_CASE))) return null
            if (address.startsWith("[")) {
                val closing = address.indexOf(']')
                if (closing < 0) return null
                val host = address.substring(1, closing)
                if (!host.matches(IPV6) || !host.contains(':')) return null
                val suffix = address.substring(closing + 1)
                val port = if (suffix.isEmpty()) DEFAULT_PORT else {
                    if (!suffix.startsWith(":")) return null
                    suffix.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                }
                return DirectJoinEndpoint(host, port)
            }
            if (address.count { it == ':' } > 1) return null
            val colon = address.lastIndexOf(':')
            val host = if (colon >= 0) address.substring(0, colon) else address
            val port = if (colon >= 0) address.substring(colon + 1).toIntOrNull() else DEFAULT_PORT
            if (!host.matches(HOST) || host.length > 253 || port !in 1..65535) return null
            return DirectJoinEndpoint(host.lowercase(), port!!)
        }
    }
}
