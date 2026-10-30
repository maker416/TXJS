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
import kotlin.test.assertFalse

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

    @Test
    fun fileResponsePreservesAllServerErrorCodes() {
        DirectModSyncErrorCode.entries.forEach { code ->
            val error = assertFailsWith<DirectModSyncServerException> {
                DirectModSyncProtocol.readFileResponse(
                    DirectModSyncProtocol.encodeError(code).inputStream(), descriptor, 0,
                )
            }
            assertEquals(code, error.code)
        }
    }

    @Test
    fun fileResponseRejectsErrorLengthsBeforeReadingPayload() {
        listOf(-1, 16 * 1024 + 1, Int.MAX_VALUE).forEach { length ->
            val frame = ByteArrayOutputStream()
            DataOutputStream(frame).apply { writeInt(length); writeInt(DirectModSyncProtocol.ERROR) }
            val error = assertFailsWith<IOException> {
                DirectModSyncProtocol.readFileResponse(frame.toByteArray().inputStream(), descriptor, 0)
            }
            assertEquals("Invalid direct error payload length: $length", error.message)
        }
    }

    @Test
    fun fileResponseRejectsMalformedErrors() {
        fun stringPayload(length: Int, bytes: ByteArray = byteArrayOf()): ByteArray =
            ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).apply { writeInt(length); write(bytes) }
            }.toByteArray()

        val malformed = listOf(
            byteArrayOf(),
            byteArrayOf(0, 0, 0),
            stringPayload(-1),
            stringPayload(64 * 1024 + 1),
            stringPayload(7, "NO_ROOM".toByteArray().dropLast(1).toByteArray()),
            stringPayload(0),
            stringPayload(7, "UNKNOWN".toByteArray()),
            stringPayload(7, "NO_ROOM".toByteArray()) + 0,
            stringPayload(16 * 1024 - 4, ByteArray(16 * 1024 - 4)),
        )
        malformed.forEach { payload ->
            val error = assertFailsWith<IOException> {
                DirectModSyncProtocol.readFileResponse(errorFrame(payload).inputStream(), descriptor, 0)
            }
            assertFalse(error is DirectModSyncServerException)
        }
        val truncatedFrame = DirectModSyncProtocol.encodeError(DirectModSyncErrorCode.NO_ROOM).dropLast(1).toByteArray()
        assertFailsWith<java.io.EOFException> {
            DirectModSyncProtocol.readFileResponse(truncatedFrame.inputStream(), descriptor, 0)
        }
    }

    @Test
    fun fileResponseKeepsMaximumFileBodyStreamingAndSupportsResume() {
        val large = descriptor.copy(payloadSize = DIRECT_MOD_SYNC_MAX_FILE_BYTES)
        listOf(0L, 123L, large.payloadSize).forEach { offset ->
            val header = DirectModSyncProtocol.fileResponseHeader(large.payloadSize, offset, large)
            val frame = ByteArrayOutputStream()
            DataOutputStream(frame).apply {
                writeInt((header.size + large.payloadSize - offset).toInt())
                writeInt(DirectModSyncProtocol.FILE)
                write(header)
            }
            // 故意不提供正文，头部解析仍须返回，不能缓冲整个 FILE。
            assertEquals(
                large.payloadSize - offset,
                DirectModSyncProtocol.readFileResponse(frame.toByteArray().inputStream(), large, offset).bodyBytes,
            )
        }
    }

    @Test
    fun fileResponseStillRejectsInvalidLengthsKindsAndIdentity() {
        val header = DirectModSyncProtocol.fileResponseHeader(descriptor.payloadSize, 0, descriptor)
        fun fileFrame(length: Int, kind: Int = DirectModSyncProtocol.FILE, payload: ByteArray = header): ByteArray =
            ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).apply { writeInt(length); writeInt(kind); write(payload) }
            }.toByteArray()
        val invalid = listOf(
            fileFrame(-1), fileFrame(47), fileFrame(50), fileFrame(52), fileFrame(Int.MAX_VALUE),
            fileFrame(51, DirectModSyncProtocol.MANIFEST),
            fileFrame(51, payload = DirectModSyncProtocol.fileResponseHeader(4, 0, descriptor)),
            fileFrame(51, payload = DirectModSyncProtocol.fileResponseHeader(3, 1, descriptor)),
            fileFrame(51, payload = DirectModSyncProtocol.fileResponseHeader(3, 0, descriptor.copy(sha256 = "f".repeat(64)))),
        )
        invalid.forEach { frame ->
            assertFailsWith<IOException> { DirectModSyncProtocol.readFileResponse(frame.inputStream(), descriptor, 0) }
        }
    }

    private fun errorFrame(payload: ByteArray): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).apply { writeInt(payload.size); writeInt(DirectModSyncProtocol.ERROR); write(payload) }
    }.toByteArray()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
