/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
import io.github.rwpp.net.appearance.IdentityChatCodec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdentityChatCodecTest {
    private fun packet(source: Int, sender: String = "RWJS-ID", version: Int = 3): ByteArray =
        ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeUTF(IdentityChatCodec.READY_NOTICE)
            it.writeByte(version)
            it.writeBoolean(true)
            it.writeUTF(sender)
            it.writeInt(source)
            it.writeInt(-1)
        } }.toByteArray()

    @Test fun onlyOriginalServerSourceCanAnnounceCompletedRegistration() {
        assertEquals(IdentityChatCodec.READY_NOTICE, IdentityChatCodec.serverControlText(packet(0)))
        assertNull(IdentityChatCodec.serverControlText(packet(7)), "同名玩家伪装RWJS-ID也有真实非零source")
        assertNull(IdentityChatCodec.serverControlText(packet(0, "Player")))
        assertNull(IdentityChatCodec.serverControlText(packet(0, version = 2)))
    }

    @Test fun malformedOrExtendedFramesCannotUnlockIdentityTraffic() {
        val valid = packet(0)
        assertNull(IdentityChatCodec.serverControlText(valid.copyOf(valid.size - 1)))
        assertNull(IdentityChatCodec.serverControlText(valid + byteArrayOf(1)))
        assertNull(IdentityChatCodec.serverControlText(ByteArray(4097)))
    }
}
