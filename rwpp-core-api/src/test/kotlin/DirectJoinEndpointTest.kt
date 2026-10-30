/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync.direct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectJoinEndpointTest {
    @Test
    fun parsesHostDomainAndBracketedIpv6() {
        assertEquals(DirectJoinEndpoint("127.0.0.1", 5123), DirectJoinEndpoint.parseOrNull("127.0.0.1"))
        assertEquals(DirectJoinEndpoint("example.com", 6000), DirectJoinEndpoint.parseOrNull(" Example.COM:6000 "))
        assertEquals(DirectJoinEndpoint("::1", 5123), DirectJoinEndpoint.parseOrNull("[::1]"))
        assertEquals(DirectJoinEndpoint("2001:db8::1", 6000), DirectJoinEndpoint.parseOrNull("[2001:db8::1]:6000"))
    }

    @Test
    fun rejectsCommandsShortCodesAndMalformedAddresses() {
        listOf("Q77182", "rABC", "T1234", "Qnews", "get|foo", "host:", "host:0", "host:65536", "::1", "[::1", "a b:5123", "http://host").forEach {
            assertNull(DirectJoinEndpoint.parseOrNull(it), it)
        }
    }
}
