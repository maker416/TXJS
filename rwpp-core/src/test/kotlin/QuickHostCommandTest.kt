/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.impl.BaseNetImpl
import io.github.rwpp.net.HostCommandPrefix
import io.github.rwpp.net.Packet
import io.github.rwpp.net.QuickHostRoomOptions
import io.github.rwpp.net.buildQuickHostCommand
import io.github.rwpp.net.parseQuickHostCommandPrefix
import io.github.rwpp.net.quickHostAddressSkipsClientVanillaModClear
import io.github.rwpp.net.quickHostSkipsClientVanillaModClear
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickHostCommandTest {
    private val net = object : BaseNetImpl() {
        override fun sendPacketToServer(packet: Packet) = Unit
        override fun sendPacketToClients(packet: Packet) = Unit
        override fun openUriInBrowser(uri: String) = Unit
    }

    @Test
    fun tHostingUsesTheDomainWithAllRoomOptions() {
        assertEquals("t.mxy.wang/news", net.buildQuickHostCommand(false, prefix = HostCommandPrefix.T))
        assertEquals("t.mxy.wang/modsP20U500C3000Z2", net.buildQuickHostCommand(
            true, maxPlayer = 20, unitLimit = 500, credits = 3000, speedMultiplier = 2, prefix = HostCommandPrefix.T,
        ))
        assertEquals("t.mxy.wang/C6666", net.buildQuickHostCommand(false, roomId = "6666", prefix = HostCommandPrefix.T))
        assertEquals("t.mxy.wang/CM6666P10", net.buildQuickHostCommand(true, roomId = "6666", maxPlayer = 10, prefix = HostCommandPrefix.T))
    }

    @Test
    fun qAndRHostingKeepTheirExistingCommands() {
        assertEquals("Qnews", net.buildQuickHostCommand(false))
        assertEquals("Rnews", net.buildQuickHostCommand(false, prefix = HostCommandPrefix.R))
        assertEquals("QCM6666P10", net.buildQuickHostCommand(true, roomId = "6666", maxPlayer = 10))
        assertEquals("RmodsP20U500", net.buildQuickHostCommand(true, maxPlayer = 20, unitLimit = 500, prefix = HostCommandPrefix.R))
    }

    @Test
    fun parseQuickHostPrefixAndVanillaModClearPolicy() {
        assertEquals(HostCommandPrefix.Q, parseQuickHostCommandPrefix("QnewsP20C5000"))
        assertEquals(HostCommandPrefix.R, parseQuickHostCommandPrefix("Rmods"))
        assertEquals(HostCommandPrefix.T, parseQuickHostCommandPrefix("t.mxy.wang/news"))
        assertNull(parseQuickHostCommandPrefix("Q77182"))
        assertNull(parseQuickHostCommandPrefix("not-a-host"))

        assertTrue(quickHostSkipsClientVanillaModClear(HostCommandPrefix.Q))
        assertTrue(quickHostSkipsClientVanillaModClear(HostCommandPrefix.R))
        assertFalse(quickHostSkipsClientVanillaModClear(HostCommandPrefix.T))

        assertTrue(quickHostAddressSkipsClientVanillaModClear("Qnews"))
        assertFalse(quickHostAddressSkipsClientVanillaModClear("t.mxy.wang/news"))
        assertFalse(quickHostAddressSkipsClientVanillaModClear("Q77182"))
    }

    @Test
    fun buildThenParseQuickHostPrefix() {
        HostCommandPrefix.entries.forEach { prefix ->
            val wire = buildQuickHostCommand(QuickHostRoomOptions(prefix = prefix, enableMods = false))
            assertEquals(prefix, parseQuickHostCommandPrefix(wire))
        }
    }
}
