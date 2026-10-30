/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync.direct

import io.github.rwpp.game.mod.NetworkModDescriptor
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DirectModSyncProtocolTest {
    private val descriptor = NetworkModDescriptor("m", 3, "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    private val manifest = DirectModManifest(1, "1.15", "abc", "ready", true, 0x0102030405060708, listOf(descriptor))

    @Test
    fun manifestMatchesGoldenBytes() {
        val expected = "00000056000000020000000100000004312e31350000000361626300000005726561647901010203040506070800000001000000016d0000000000000003000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val encoded = DirectModSyncProtocol.encodeManifest(manifest)
        assertEquals(expected, encoded.toHex())
        assertEquals(manifest, DirectModSyncProtocol.readManifest(encoded.inputStream()))
    }

    @Test
    fun requestAndErrorMatchGoldenBytes() {
        val getManifest = ByteArrayOutputStream()
        DirectModSyncProtocol.writeGetManifest(getManifest)
        assertEquals("54584a534d44310a0000000000000001", getManifest.toByteArray().toHex())
        val getFile = ByteArrayOutputStream()
        DirectModSyncProtocol.writeGetFile(getFile, descriptor.copy(payloadSize = 8), 7)
        assertEquals("54584a534d44310a0000002800000003000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000000000007", getFile.toByteArray().toHex())
        assertEquals("0000000b00000004000000074e4f5f524f4f4d", DirectModSyncProtocol.encodeError(DirectModSyncErrorCode.NO_ROOM).toHex())
        val error = assertFailsWith<DirectModSyncServerException> {
            DirectModSyncProtocol.readManifest(DirectModSyncProtocol.encodeError(DirectModSyncErrorCode.NO_ROOM).inputStream())
        }
        assertEquals(DirectModSyncErrorCode.NO_ROOM, error.code)
    }

    @Test
    fun rejectsInvalidLengthsCountsAndTrailingManifestBytes() {
        val negative = ByteArrayOutputStream()
        DataOutputStream(negative).writeInt(-1)
        assertFailsWith<IOException> { DirectModSyncProtocol.readManifest(negative.toByteArray().inputStream()) }
        val truncated = DirectModSyncProtocol.encodeManifest(manifest).dropLast(1).toByteArray()
        assertFailsWith<IOException> { DirectModSyncProtocol.readManifest(truncated.inputStream()) }
        val trailing = DirectModSyncProtocol.encodeManifest(manifest) + 1
        java.nio.ByteBuffer.wrap(trailing).putInt(trailing.size - 8)
        assertFailsWith<IOException> { DirectModSyncProtocol.readManifest(trailing.inputStream()) }
    }

    @Test
    fun rejectsFileCountAndTotalSizeLimitViolations() {
        assertFailsWith<IOException> {
            validateManifest(manifest.copy(mods = listOf(descriptor.copy(payloadSize = DIRECT_MOD_SYNC_MAX_FILE_BYTES + 1))))
        }
        val tooMany = (0 until DIRECT_MOD_SYNC_MAX_MODS + 1).map { descriptor.copy(name = "m$it") }
        assertFailsWith<IOException> { validateManifest(manifest.copy(mods = tooMany)) }
        val oversizedTotal = (0..4).map { descriptor.copy(name = "large$it", payloadSize = DIRECT_MOD_SYNC_MAX_FILE_BYTES) }
        assertFailsWith<IOException> { validateManifest(manifest.copy(mods = oversizedTotal)) }
    }

    @Test
    fun validatesFileIdentityOffsetAndBodyLength() {
        val body = byteArrayOf(1, 2, 3)
        val header = DirectModSyncProtocol.fileResponseHeader(3, 0, descriptor)
        val frame = ByteArrayOutputStream()
        DataOutputStream(frame).apply { writeInt(header.size + body.size); writeInt(DirectModSyncProtocol.FILE); write(header); write(body) }
        val input = frame.toByteArray().inputStream()
        assertEquals(3, DirectModSyncProtocol.readFileResponse(input, descriptor, 0).bodyBytes)
        assertContentEquals(body, input.readBytes())
        assertFailsWith<IOException> {
            DirectModSyncProtocol.readFileResponse(frame.toByteArray().inputStream(), descriptor, 1)
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
