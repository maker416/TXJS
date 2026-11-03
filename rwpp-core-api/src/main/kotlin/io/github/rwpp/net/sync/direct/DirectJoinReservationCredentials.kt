/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync.direct

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

const val DIRECT_JOIN_RESERVATION_QUERY_PREFIX = "txjsmd2_"

/**
 * 只在本轮真实连接建立期间，把租约凭证提供给原版包 160 的 query 字段。
 * 地址和 relay UUID 始终保留原值，凭证不会进入地址历史、日志或新游戏包。
 */
object DirectJoinReservationCredentials {
    private class Active(val port: Int, val addresses: Set<InetAddress>, val query: String) {
        override fun toString(): String = "DirectJoinReservationCredentials(<redacted>)"
    }

    private val current = AtomicReference<Active?>()

    suspend fun open(endpoint: DirectJoinEndpoint, token: ByteArray): AutoCloseable =
        open(endpoint, DirectModSyncProtocol.encodeSha256(token))

    suspend fun open(endpoint: DirectJoinEndpoint, tokenHex: String): AutoCloseable {
        require(tokenHex.matches(Regex("[0-9a-f]{64}"))) { "Invalid direct reservation credential" }
        // DNS 只能在 IO 上解析，原版连接接点仅比较已经解析好的远端地址。
        val addresses = withContext(Dispatchers.IO) { InetAddress.getAllByName(endpoint.host).toSet() }
        currentCoroutineContext().ensureActive()
        val active = Active(endpoint.port, addresses, DIRECT_JOIN_RESERVATION_QUERY_PREFIX + tokenHex)
        current.set(active)
        return object : AutoCloseable {
            override fun close() {
                // 身份比较相当于代次检查：旧请求的 finally 不能清掉新请求的凭证。
                current.compareAndSet(active, null)
            }

            override fun toString(): String = "DirectJoinReservationScope(<redacted>)"
        }
    }

    fun queryFor(socket: Socket): String? {
        val active = current.get() ?: return null
        return active.query.takeIf { socket.isConnected && !socket.isClosed &&
            socket.port == active.port && socket.inetAddress in active.addresses }
    }
}
