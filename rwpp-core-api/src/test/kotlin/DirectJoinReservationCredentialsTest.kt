/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync.direct

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class DirectJoinReservationCredentialsTest {
    @Test
    fun matchesOnlyTheExactRemoteEndpointAndNeverPrintsCredentials() = runBlocking {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            Socket("127.0.0.1", server.localPort).use { socket ->
                server.accept().use {
                    val token = "a".repeat(64)
                    val holder = DirectJoinReservationCredentials.open(DirectJoinEndpoint("127.0.0.1", server.localPort), token)
                    try {
                        assertEquals("txjsmd2_$token", DirectJoinReservationCredentials.queryFor(socket))
                        assertFalse(holder.toString().contains(token))
                    } finally { holder.close() }
                    assertNull(DirectJoinReservationCredentials.queryFor(socket))
                    val other = DirectJoinReservationCredentials.open(DirectJoinEndpoint("127.0.0.2", server.localPort), token)
                    try { assertNull(DirectJoinReservationCredentials.queryFor(socket)) } finally { other.close() }
                    val port = if (server.localPort == 65535) 65534 else server.localPort + 1
                    val wrongPort = DirectJoinReservationCredentials.open(DirectJoinEndpoint("127.0.0.1", port), token)
                    try { assertNull(DirectJoinReservationCredentials.queryFor(socket)) } finally { wrongPort.close() }
                }
            }
        }
    }

    @Test
    fun oldFinallyCannotClearANewerConnectionAttempt() = runBlocking {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            Socket("127.0.0.1", server.localPort).use { socket ->
                server.accept().use {
                    val endpoint = DirectJoinEndpoint("127.0.0.1", server.localPort)
                    val old = DirectJoinReservationCredentials.open(endpoint, ByteArray(32) { 1 })
                    val newer = DirectJoinReservationCredentials.open(endpoint, ByteArray(32) { 2 })
                    try {
                        old.close()
                        assertEquals("txjsmd2_" + "02".repeat(32), DirectJoinReservationCredentials.queryFor(socket))
                    } finally { old.close(); newer.close() }
                    assertNull(DirectJoinReservationCredentials.queryFor(socket))
                }
            }
        }
    }
}
