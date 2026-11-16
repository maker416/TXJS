/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.corrodinggames.rts.gameFramework.j.ad
import io.github.rwpp.desktop.impl.inject.NetworkInject
import io.github.rwpp.net.sync.direct.DirectJoinEndpoint
import io.github.rwpp.net.sync.direct.DirectJoinReservationCredentials
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDirectCredentialInjectionTest {
    @Test
    fun staleDirectQueriesAreClearedForOtherEndpointsWithoutClearingRelayQueries() = runBlocking {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
        val network = unsafe.allocateInstance(ad::class.java) as ad
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            Socket("127.0.0.1", server.localPort).use { socket ->
                server.accept().use {
                    val endpoint = DirectJoinEndpoint("127.0.0.1", server.localPort)
                    DirectJoinReservationCredentials.openResume(endpoint, ByteArray(32) { 9 }).use {
                        with(NetworkInject) { network.applyDirectReservationCredential(socket) }
                        assertEquals("txjsmd3r_" + "09".repeat(32), network.L)
                    }
                    DirectJoinReservationCredentials.openResume(endpoint.copy(host = "127.0.0.2"), ByteArray(32) { 8 }).use {
                        with(NetworkInject) { network.applyDirectReservationCredential(socket) }
                        assertNull(network.L)
                    }
                    network.L = "txjsmd2_" + "01".repeat(32)
                    with(NetworkInject) { network.applyDirectReservationCredential(socket) }
                    assertNull(network.L)
                    network.L = "relay-query"
                    with(NetworkInject) { network.applyDirectReservationCredential(socket) }
                    assertEquals("relay-query", network.L)
                }
            }
        }
    }
}
