/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.packets

import io.github.rwpp.io.GameInputStream
import io.github.rwpp.io.GameOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModPacketTest {

    private fun roundTrip(packet: ModPacket.ManifestProgressPacket): ModPacket.ManifestProgressPacket {
        val bytes = ByteArrayOutputStream().also { baos ->
            packet.writePacket(GameOutputStream(DataOutputStream(baos)))
        }.toByteArray()
        return ModPacket.ManifestProgressPacket().apply {
            readPacket(GameInputStream(DataInputStream(ByteArrayInputStream(bytes))))
        }
    }

    @Test
    fun manifestProgressPacketRoundTrip() {
        val decoded = roundTrip(ModPacket.ManifestProgressPacket().apply {
            requestId = 42L
            preparedBytes = 123_456_789L
        })
        assertEquals(42L, decoded.requestId)
        assertEquals(123_456_789L, decoded.preparedBytes)
    }

    @Test
    fun manifestProgressPacketZeroValues() {
        val decoded = roundTrip(ModPacket.ManifestProgressPacket())
        assertEquals(0L, decoded.requestId)
        assertEquals(0L, decoded.preparedBytes)
    }

    @Test
    fun manifestProgressPacketRejectsNegativePreparedBytes() {
        val bytes = ByteArrayOutputStream().also { baos ->
            DataOutputStream(baos).apply {
                writeLong(1L)
                writeLong(-1L)
            }
        }.toByteArray()
        assertFailsWith<IllegalArgumentException> {
            ModPacket.ManifestProgressPacket().readPacket(
                GameInputStream(DataInputStream(ByteArrayInputStream(bytes)))
            )
        }
    }

    @Test
    fun manifestProgressPacketTypeIsRegisteredConstant() {
        assertEquals(ModPacket.MOD_MANIFEST_PROGRESS, ModPacket.ManifestProgressPacket().type)
        assertEquals(507, ModPacket.MOD_MANIFEST_PROGRESS)
    }
}
