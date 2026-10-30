/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync.direct

import io.github.rwpp.game.mod.NetworkModDescriptor
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

const val DIRECT_MOD_SYNC_PROTOCOL_VERSION = 1
const val DIRECT_MOD_SYNC_MAX_MODS = 256
const val DIRECT_MOD_SYNC_MAX_FILE_BYTES = 512L * 1024L * 1024L
const val DIRECT_MOD_SYNC_MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
private const val MAX_MANIFEST_PAYLOAD = 1024 * 1024
private const val MAX_ERROR_PAYLOAD = 16 * 1024
private const val MAX_STRING_BYTES = 64 * 1024

/** 独立 TCP 控制连接协议；与原版游戏 Socket 和 relaymod HTTP 完全隔离。 */
object DirectModSyncProtocol {
    val MAGIC = byteArrayOf('T'.code.toByte(), 'X'.code.toByte(), 'J'.code.toByte(), 'S'.code.toByte(),
        'M'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte(), '\n'.code.toByte())
    const val GET_MANIFEST = 1
    const val MANIFEST = 2
    const val GET_FILE = 3
    const val ERROR = 4
    const val FILE = 5

    fun writeGetManifest(output: OutputStream) {
        output.write(MAGIC)
        DataOutputStream(output).apply { writeInt(0); writeInt(GET_MANIFEST); flush() }
    }

    fun writeGetFile(output: OutputStream, descriptor: NetworkModDescriptor, offset: Long) {
        require(offset in 0..descriptor.payloadSize)
        val bytes = ByteArrayOutputStream(48)
        DataOutputStream(bytes).use { data ->
            data.writeInt(40)
            data.writeInt(GET_FILE)
            data.write(decodeSha256(descriptor.normalizedSha256))
            data.writeLong(offset)
        }
        output.write(MAGIC)
        output.write(bytes.toByteArray())
        output.flush()
    }

    fun encodeManifest(manifest: DirectModManifest): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(manifest.protocolVersion)
            out.writeProtocolString(manifest.gameVersion)
            out.writeProtocolString(manifest.scope)
            out.writeProtocolString(manifest.status)
            out.writeBoolean(manifest.lobby)
            out.writeLong(manifest.hostUnitsChecksum)
            out.writeInt(manifest.mods.size)
            manifest.mods.forEach { mod ->
                out.writeProtocolString(mod.name)
                out.writeLong(mod.payloadSize)
                out.write(decodeSha256(mod.normalizedSha256))
            }
        }
        return frame(MANIFEST, payload.toByteArray())
    }

    fun encodeError(code: DirectModSyncErrorCode): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { it.writeProtocolString(code.name) }
        return frame(ERROR, payload.toByteArray())
    }

    fun fileResponseHeader(totalSize: Long, offset: Long, descriptor: NetworkModDescriptor): ByteArray {
        val payload = ByteArrayOutputStream(48)
        DataOutputStream(payload).use { out ->
            out.writeLong(totalSize)
            out.writeLong(offset)
            out.write(decodeSha256(descriptor.normalizedSha256))
        }
        return payload.toByteArray()
    }

    fun readManifest(input: InputStream): DirectModManifest {
        val frame = readBoundedFrame(input, MAX_MANIFEST_PAYLOAD)
        if (frame.kind == ERROR) throw DirectModSyncServerException(readErrorPayload(frame.payload))
        if (frame.kind != MANIFEST) throw IOException("Unexpected direct manifest response kind: ${frame.kind}")
        val data = DataInputStream(frame.payload.inputStream())
        val protocolVersion = data.readInt()
        val manifest = DirectModManifest(
            protocolVersion = protocolVersion,
            gameVersion = data.readProtocolString(),
            scope = data.readProtocolString(),
            status = data.readProtocolString(),
            lobby = data.readBoolean(),
            hostUnitsChecksum = data.readLong(),
            mods = mutableListOf<NetworkModDescriptor>().also { mods ->
                val count = data.readInt()
                if (count !in 0..DIRECT_MOD_SYNC_MAX_MODS) throw IOException("Invalid direct mod count: $count")
                repeat(count) {
                    mods += NetworkModDescriptor(data.readProtocolString(), data.readLong(), encodeSha256(data.readNBytesStrict(32)))
                }
            },
        )
        if (data.available() != 0) throw IOException("Trailing direct manifest bytes")
        validateManifest(manifest)
        return manifest
    }

    fun readFileResponse(input: InputStream, expected: NetworkModDescriptor, requestedOffset: Long): DirectFileResponseHeader {
        val data = DataInputStream(input)
        val length = data.readInt()
        val kind = data.readInt()
        if (kind == ERROR) {
            // ERROR 使用独立的小帧上限，不能套用 FILE 长度下限，也不能按文件大小分配内存。
            if (length !in 0..MAX_ERROR_PAYLOAD) throw IOException("Invalid direct error payload length: $length")
            throw DirectModSyncServerException(readErrorPayload(data.readNBytesStrict(length)))
        }
        if (kind != FILE) throw IOException("Unexpected direct file response kind: $kind")
        val minimum = 48
        val maximum = (expected.payloadSize - requestedOffset + minimum).coerceAtMost(Int.MAX_VALUE.toLong())
        if (length < minimum || length > maximum) throw IOException("Invalid direct file payload length: $length")
        val totalSize = data.readLong()
        val offset = data.readLong()
        val hash = encodeSha256(data.readNBytesStrict(32))
        if (totalSize != expected.payloadSize || offset != requestedOffset || hash != expected.normalizedSha256) {
            throw IOException("Direct file response identity or offset mismatch")
        }
        if (length.toLong() != minimum + totalSize - offset) throw IOException("Direct file response is truncated or has trailing data")
        return DirectFileResponseHeader((length - minimum).toLong())
    }

    fun decodeSha256(hex: String): ByteArray {
        if (!hex.matches(Regex("[0-9a-f]{64}"))) throw IllegalArgumentException("Invalid SHA-256")
        return ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun encodeSha256(bytes: ByteArray): String {
        if (bytes.size != 32) throw IllegalArgumentException("SHA-256 must contain 32 bytes")
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun readErrorPayload(payload: ByteArray): DirectModSyncErrorCode {
        if (payload.size > MAX_ERROR_PAYLOAD) throw IOException("Direct error payload too large")
        val data = DataInputStream(payload.inputStream())
        val raw = data.readProtocolString()
        if (data.available() != 0) throw IOException("Trailing direct error bytes")
        return DirectModSyncErrorCode.entries.firstOrNull { it.name == raw }
            ?: throw IOException("Unknown direct error code: $raw")
    }

    private fun frame(kind: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        DataOutputStream(out).use { it.writeInt(payload.size); it.writeInt(kind); it.write(payload) }
        return out.toByteArray()
    }

    private fun readBoundedFrame(input: InputStream, maximum: Int): Frame {
        val data = DataInputStream(input)
        val length = data.readInt()
        if (length !in 0..maximum) throw IOException("Invalid direct payload length: $length")
        return Frame(data.readInt(), data.readNBytesStrict(length))
    }

    private data class Frame(val kind: Int, val payload: ByteArray)
}

data class DirectModManifest(
    val protocolVersion: Int,
    val gameVersion: String,
    val scope: String,
    val status: String,
    val lobby: Boolean,
    val hostUnitsChecksum: Long,
    val mods: List<NetworkModDescriptor>,
) {
    fun sameRoomContent(other: DirectModManifest): Boolean =
        scope == other.scope && hostUnitsChecksum == other.hostUnitsChecksum && mods == other.mods
}

data class DirectFileResponseHeader(val bodyBytes: Long)

enum class DirectModSyncErrorCode {
    NO_ROOM, NOT_LOBBY, PREPARING, NOT_FOUND, BUSY, BAD_REQUEST, INTERNAL_ERROR,
}

open class DirectModSyncException(message: String, cause: Throwable? = null) : IOException(message, cause)
class DirectModSyncUnsupportedException(message: String, cause: Throwable? = null) : DirectModSyncException(message, cause)
class DirectModSyncServerException(val code: DirectModSyncErrorCode) : DirectModSyncException("Direct server error: $code")

fun validateManifest(manifest: DirectModManifest) {
    if (manifest.protocolVersion != DIRECT_MOD_SYNC_PROTOCOL_VERSION) throw IOException("Unsupported direct protocol version")
    if (manifest.mods.size > DIRECT_MOD_SYNC_MAX_MODS) throw IOException("Too many direct mods")
    var total = 0L
    val identities = mutableSetOf<String>()
    val names = mutableSetOf<String>()
    manifest.mods.forEach { mod ->
        if (mod.payloadSize > DIRECT_MOD_SYNC_MAX_FILE_BYTES) throw IOException("Direct mod exceeds size limit")
        total = Math.addExact(total, mod.payloadSize)
        if (!identities.add(mod.cacheKey()) || !names.add(mod.name)) throw IOException("Duplicate direct mod descriptor")
    }
    if (total > DIRECT_MOD_SYNC_MAX_TOTAL_BYTES) throw IOException("Direct manifest exceeds total size limit")
}

fun sha256Hex(digest: MessageDigest): String = DirectModSyncProtocol.encodeSha256(digest.digest())

internal fun DataOutputStream.writeProtocolString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size > MAX_STRING_BYTES) throw IOException("Direct protocol string too long")
    writeInt(bytes.size)
    write(bytes)
}

internal fun DataInputStream.readProtocolString(): String {
    val length = readInt()
    if (length !in 0..MAX_STRING_BYTES) throw IOException("Invalid direct string length: $length")
    return String(readNBytesStrict(length), StandardCharsets.UTF_8)
}

internal fun InputStream.readNBytesStrict(length: Int): ByteArray {
    if (length < 0) throw IOException("Negative byte count")
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(result, offset, length - offset)
        if (read < 0) throw EOFException("Truncated direct protocol payload")
        if (read == 0) continue
        offset += read
    }
    return result
}
