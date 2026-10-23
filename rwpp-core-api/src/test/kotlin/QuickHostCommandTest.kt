/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class QuickHostCommandTest {
    private fun netWithLegacyOverride(legacyResult: String? = null): Net = Proxy.newProxyInstance(
        Net::class.java.classLoader,
        arrayOf(Net::class.java),
    ) { proxy, method, arguments ->
        if (method.name == "buildQuickHostCommand" && method.parameterTypes.firstOrNull() == Boolean::class.javaPrimitiveType &&
            legacyResult != null
        ) {
            legacyResult
        } else {
            InvocationHandler.invokeDefault(proxy, method, *(arguments ?: emptyArray()))
        }
    } as Net

    @Test
    fun optionsOverloadUsesExistingNetImplementationWithoutRequiringANewOverride() {
        val net = netWithLegacyOverride("custom-host-protocol")
        assertEquals("custom-host-protocol", net.buildQuickHostCommand(QuickHostRoomOptions()))
    }

    @Test
    fun bothNetOverloadsGenerateTheSameCommandByDefault() {
        val net = netWithLegacyOverride()
        val options = QuickHostRoomOptions(HostCommandPrefix.Q, false, "6666", QuickHostLimits(20, 500, 5000, 5))
        assertEquals("QC6666P20U500C5000Z5", net.buildQuickHostCommand(options))
        assertEquals(net.buildQuickHostCommand(options), net.buildQuickHostCommand(false, "6666", 20, 500, 5000, 5))
    }

    @Test
    fun everyGeneratedCommandUsesTheSelectedPrefixAndModClearPolicy() {
        for (prefix in HostCommandPrefix.entries) {
            for (enableMods in listOf(false, true)) {
                for (roomId in listOf(null, "6666", "0", "000123")) {
                    for (limits in listOf(QuickHostLimits(), QuickHostLimits(20, 500, 5000, 5))) {
                        val command = buildQuickHostCommand(QuickHostRoomOptions(prefix, enableMods, roomId, limits))
                        assertEquals(prefix, parseQuickHostCommandPrefix(command), command)
                        assertEquals(
                            quickHostSkipsClientVanillaModClear(prefix),
                            quickHostAddressSkipsClientVanillaModClear(command),
                            command,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun joiningCodesAndAddressesNeverUseTheHostingModClearPolicy() {
        for (address in listOf("Q77182", "R123", "q77182:5123", "T123", "t.mxy.wang/123", "127.0.0.1:5123")) {
            assertNull(parseQuickHostCommandPrefix(address), address)
            assertFalse(quickHostAddressSkipsClientVanillaModClear(address), address)
        }
    }

    @Test
    fun parsingHandlesCaseAndWhitespaceWithoutAcceptingInvalidBodies() {
        assertEquals(HostCommandPrefix.Q, parseQuickHostCommandPrefix(" qnewsP20C5000 "))
        assertEquals(HostCommandPrefix.R, parseQuickHostCommandPrefix("rc6666p20c5000"))
        assertEquals(HostCommandPrefix.T, parseQuickHostCommandPrefix("T.MXY.WANG/CM6666Z5"))
        for (address in listOf("QC", "QCM", "QCnot-a-room", "QnewsBAD", "QnewsP", "Rmods:5123")) {
            assertNull(parseQuickHostCommandPrefix(address), address)
        }
    }
}
