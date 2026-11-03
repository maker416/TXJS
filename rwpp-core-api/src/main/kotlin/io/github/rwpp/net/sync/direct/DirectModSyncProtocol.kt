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
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

const val DIRECT_MOD_SYNC_PROTOCOL_VERSION = 1
const val DIRECT_MOD_SYNC_RESERVATION_PROTOCOL_VERSION = 2
const val DIRECT_MOD_SYNC_MAX_MODS = 256
const val DIRECT_MOD_SYNC_MAX_FILE_BYTES = 512L * 1024L * 1024L
const val DIRECT_MOD_SYNC_MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
private const val MAX_MANIFEST_PAYLOAD = 1024 * 1024
private const val MAX_ERROR_PAYLOAD = 16 * 1024
private const val MAX_STRING_BYTES = 64 * 1024
private const val MAX_LEASE_PAYLOAD = MAX_STRING_BYTES + 64

/** 独立 TCP 控制连接协议；与原版游戏 Socket 和 relaymod HTTP 完全隔离。 */
object DirectModSyncProtocol {
    val MAGIC = byteArrayOf('T'.code.toByte(), 'X'.code.toByte(), 'J'.code.toByte(), 'S'.code.toByte(),
        'M'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte(), '\n'.code.toByte())
    val MAGIC_V2 = "TXJSMD2\n".toByteArray(StandardCharsets.US_ASCII)
    const val GET_MANIFEST = 1
    const val MANIFEST = 2
    const val GET_FILE = 3
    const val ERROR = 4
    const val FILE = 5
    const val RESERVE = 6
    const val LEASE = 7
    const val RENEW = 8
    const val RELEASE = 9
    const val RELEASED = 10

    @JvmOverloads
    fun writeGetManifest(output: OutputStream, protocolVersion: Int = DIRECT_MOD_SYNC_PROTOCOL_VERSION) {
        output.write(magic(protocolVersion))
        DataOutputStream(output).apply { writeInt(0); writeInt(GET_MANIFEST); flush() }
    }

    @JvmOverloads
    fun writeGetFile(output: OutputStream, descriptor: NetworkModDescriptor, offset: Long, protocolVersion: Int = DIRECT_MOD_SYNC_PROTOCOL_VERSION) {
        require(offset in 0..descriptor.payloadSize)
        val bytes = ByteArrayOutputStream(48)
        DataOutputStream(bytes).use { data ->
            data.writeInt(40)
            data.writeInt(GET_FILE)
            data.write(decodeSha256(descriptor.normalizedSha256))
            data.writeLong(offset)
        }
        output.write(magic(protocolVersion))
        output.write(bytes.toByteArray())
        output.flush()
    }

    fun writeLeaseRequest(output: OutputStream, kind: Int, scope: String, token: ByteArray) {
        require(kind == RESERVE || kind == RENEW || kind == RELEASE) { "Invalid direct lease request kind" }
        require(scope.isNotBlank()) { "Missing direct room scope" }
        require(token.size == 32) { "Direct lease token must contain 32 bytes" }
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out -> out.writeProtocolString(scope); out.write(token) }
        output.write(MAGIC_V2)
        output.write(frame(kind, payload.toByteArray()))
        output.flush()
    }

    fun encodeLease(lease: DirectModLease): ByteArray = frame(LEASE, ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeProtocolString(lease.scope)
            out.writeLong(lease.ttlMillis)
            out.writeLong(lease.renewAfterMillis)
            out.writeProtocolString(lease.state)
            out.writeInt(lease.occupiedSlots)
            out.writeInt(lease.slotLimit)
            out.writeInt(lease.reservedSlots)
        }
    }.toByteArray())

    fun encodeReleased(): ByteArray = frame(RELEASED, byteArrayOf())

    fun readLease(input: InputStream, expectedScope: String): DirectModLease {
        val response = readBoundedFrame(input, MAX_LEASE_PAYLOAD)
        if (response.kind == ERROR) throw DirectModSyncServerException(readErrorPayload(response.payload))
        if (response.kind != LEASE) throw IOException("Unexpected direct lease response kind: ${response.kind}")
        val data = DataInputStream(response.payload.inputStream())
        val lease = DirectModLease(data.readProtocolString(), data.readLong(), data.readLong(),
            data.readProtocolString(), data.readInt(), data.readInt(), data.readInt())
        if (data.available() != 0) throw IOException("Trailing direct lease bytes")
        validateLease(lease, expectedScope)
        return lease
    }

    fun readReleased(input: InputStream) {
        val response = readBoundedFrame(input, MAX_ERROR_PAYLOAD)
        if (response.kind == ERROR) throw DirectModSyncServerException(readErrorPayload(response.payload))
        if (response.kind != RELEASED || response.payload.isNotEmpty()) throw IOException("Invalid direct release response")
    }

    private fun magic(protocolVersion: Int): ByteArray = when (protocolVersion) {
        DIRECT_MOD_SYNC_PROTOCOL_VERSION -> MAGIC
        DIRECT_MOD_SYNC_RESERVATION_PROTOCOL_VERSION -> MAGIC_V2
        else -> throw IllegalArgumentException("Unsupported direct protocol version")
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
            lobby = when (val flag = data.readUnsignedByte()) {
                0 -> false
                1 -> true
                else -> throw IOException("Invalid direct lobby flag: $flag")
            },
            hostUnitsChecksum = data.readLong(),
            mods = mutableListOf<NetworkModDescriptor>().also { mods ->
                val count = data.readInt()
                if (count !in 0..DIRECT_MOD_SYNC_MAX_MODS) throw IOException("Invalid direct mod count: $count")
                repeat(count) {
                    val name = data.readProtocolString()
                    val size = data.readLong()
                    val hash = encodeSha256(data.readNBytesStrict(32))
                    mods += try {
                        NetworkModDescriptor(name, size, hash)
                    } catch (error: IllegalArgumentException) {
                        throw IOException("Invalid direct mod descriptor", error)
                    }
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
        protocolVersion == other.protocolVersion && gameVersion == other.gameVersion &&
            scope == other.scope && hostUnitsChecksum == other.hostUnitsChecksum && mods == other.mods
}

data class DirectFileResponseHeader(val bodyBytes: Long)

/** 租约仅用于本轮同步及随后真实连接的准入；不包含可打印的凭证。 */
data class DirectModLease(
    val scope: String,
    val ttlMillis: Long,
    val renewAfterMillis: Long,
    val state: String,
    val occupiedSlots: Int,
    val slotLimit: Int,
    val reservedSlots: Int,
)

enum class DirectModSyncErrorCode {
    NO_ROOM, NOT_LOBBY, PREPARING, NOT_FOUND, BUSY, BAD_REQUEST, INTERNAL_ERROR,
    ROOM_FULL, LEASE_EXPIRED, LEASE_INVALID,
}

open class DirectModSyncException(message: String, cause: Throwable? = null) : IOException(message, cause)
class DirectModSyncUnsupportedException(message: String, cause: Throwable? = null) : DirectModSyncException(message, cause)
class DirectModSyncServerException(val code: DirectModSyncErrorCode) : DirectModSyncException("Direct server error: $code")

fun validateManifest(manifest: DirectModManifest) {
    if (manifest.protocolVersion != DIRECT_MOD_SYNC_PROTOCOL_VERSION &&
        manifest.protocolVersion != DIRECT_MOD_SYNC_RESERVATION_PROTOCOL_VERSION) throw IOException("Unsupported direct protocol version")
    if (manifest.gameVersion.isBlank()) throw IOException("Missing direct game version")
    if (manifest.scope.isBlank()) throw IOException("Missing direct room scope")
    if (manifest.status.isBlank()) throw IOException("Missing direct manifest status")
    if (manifest.mods.size > DIRECT_MOD_SYNC_MAX_MODS) throw IOException("Too many direct mods")
    var total = 0L
    val identities = mutableSetOf<String>()
    val names = mutableSetOf<String>()
    val sizesByHash = mutableMapOf<String, Long>()
    manifest.mods.forEach { mod ->
        if (mod.payloadSize > DIRECT_MOD_SYNC_MAX_FILE_BYTES) throw IOException("Direct mod exceeds size limit")
        total = Math.addExact(total, mod.payloadSize)
        if (!identities.add(mod.cacheKey()) || !names.add(mod.name)) throw IOException("Duplicate direct mod descriptor")
        val previousSize = sizesByHash.putIfAbsent(mod.normalizedSha256, mod.payloadSize)
        if (previousSize != null && previousSize != mod.payloadSize) throw IOException("Conflicting direct mod sizes for SHA-256")
    }
    if (total > DIRECT_MOD_SYNC_MAX_TOTAL_BYTES) throw IOException("Direct manifest exceeds total size limit")
}

fun validateLease(lease: DirectModLease, expectedScope: String) {
    if (lease.scope.isBlank() || lease.scope != expectedScope) throw IOException("Direct lease room scope mismatch")
    if (lease.ttlMillis !in 1_000L..300_000L || lease.renewAfterMillis <= 0 || lease.renewAfterMillis >= lease.ttlMillis) {
        throw IOException("Invalid direct lease lifetime")
    }
    if (lease.state !in setOf("reserved", "claimed", "admitted")) throw IOException("Invalid direct lease state")
    if (lease.slotLimit < 0 || lease.occupiedSlots !in 0..lease.slotLimit || lease.reservedSlots !in 0..lease.occupiedSlots) {
        throw IOException("Invalid direct lease capacity")
    }
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
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(readNBytesStrict(length)))
            .toString()
    } catch (error: CharacterCodingException) {
        throw IOException("Invalid UTF-8 in direct protocol string", error)
    }
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
