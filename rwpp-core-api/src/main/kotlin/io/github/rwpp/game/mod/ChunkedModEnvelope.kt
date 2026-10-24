/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.game.mod

import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** v2: bounded AEAD chunks. Header, chunk order and exact plaintext length are authenticated. */
object ChunkedModEnvelope {
    private const val MAGIC = 0x52574d43
    private const val VERSION = 2
    private const val CHUNK_SIZE = 1024 * 1024
    private const val TAG_BYTES = 16
    private val random = SecureRandom()

    data class Metadata(val descriptor: NetworkModDescriptor, val createdAtMillis: Long)
    private data class Header(val metadata: Metadata, val noncePrefix: ByteArray)

    /** Unauthenticated ordering hint only. Never index or activate this until read() verifies all chunks. */
    fun peekMetadata(file: File): Metadata = DataInputStream(file.inputStream().buffered()).use { input ->
        val size = input.readInt()
        require(size in 1..4096) { "Invalid encrypted mod header size" }
        decode(ByteArray(size).also(input::readFully)).metadata
    }

    fun write(file: File, descriptor: NetworkModDescriptor, createdAtMillis: Long, key: ByteArray, output: OutputStream) {
        require(descriptor.matches(file)) { "Payload changed before encryption" }
        val header = Header(Metadata(descriptor, createdAtMillis), ByteArray(8).also(random::nextBytes))
        val encoded = encode(header)
        output.write(encoded)
        val buffer = ByteArray(CHUNK_SIZE)
        var remaining = descriptor.payloadSize
        file.inputStream().buffered().use { source ->
            val input = DataInputStream(source)
            repeat(chunkCount(remaining)) { index ->
                val length = minOf(remaining, CHUNK_SIZE.toLong()).toInt()
                input.readFully(buffer, 0, length)
                val cipher = cipher(Cipher.ENCRYPT_MODE, key, header, encoded, index, length)
                output.write(cipher.doFinal(buffer, 0, length))
                remaining -= length
            }
            require(input.read() == -1) { "Payload changed during encryption" }
        }
    }

    /** Output must be unpublished staging storage until this method returns successfully. */
    fun read(file: File, key: ByteArray, output: OutputStream? = null, expected: NetworkModDescriptor? = null): Metadata =
        DataInputStream(file.inputStream().buffered(64 * 1024)).use { input ->
            val size = input.readInt()
            require(size in 1..4096) { "Invalid encrypted mod header size" }
            val encoded = ByteArray(size).also(input::readFully)
            val header = decode(encoded)
            if (expected != null) require(header.metadata.descriptor == expected) { "Cache descriptor mismatch" }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(CHUNK_SIZE + TAG_BYTES)
            var remaining = header.metadata.descriptor.payloadSize
            repeat(chunkCount(remaining)) { index ->
                val length = minOf(remaining, CHUNK_SIZE.toLong()).toInt()
                input.readFully(buffer, 0, length + TAG_BYTES)
                val plain = cipher(Cipher.DECRYPT_MODE, key, header, encodedWithLength(encoded), index, length)
                    .doFinal(buffer, 0, length + TAG_BYTES)
                digest.update(plain)
                output?.write(plain)
                remaining -= length
            }
            require(input.read() == -1) { "Trailing data in encrypted mod" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(hash == header.metadata.descriptor.normalizedSha256) { "Cache payload hash mismatch" }
            header.metadata
        }

    private fun chunkCount(size: Long): Int {
        val count = if (size == 0L) 1L else (size - 1) / CHUNK_SIZE + 1
        require(count <= Int.MAX_VALUE) { "Mod exceeds cache format limit" }
        return count.toInt()
    }

    private fun cipher(mode: Int, key: ByteArray, header: Header, encoded: ByteArray, index: Int, length: Int): Cipher {
        val nonce = ByteBuffer.allocate(12).put(header.noncePrefix).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(encoded)
            updateAAD(ByteBuffer.allocate(8).putInt(index).putInt(length).array())
        }
    }

    private fun encodedWithLength(bytes: ByteArray) = ByteBuffer.allocate(bytes.size + 4).putInt(bytes.size).put(bytes).array()

    private fun encode(header: Header): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(VERSION)
            output.write(header.noncePrefix)
            output.writeLong(header.metadata.createdAtMillis)
            output.writeUTF(header.metadata.descriptor.name)
            output.writeLong(header.metadata.descriptor.payloadSize)
            output.writeUTF(header.metadata.descriptor.normalizedSha256)
            output.writeInt(CHUNK_SIZE)
        }
        return encodedWithLength(bytes.toByteArray())
    }

    private fun decode(bytes: ByteArray): Header = DataInputStream(bytes.inputStream()).use { input ->
        require(input.readInt() == MAGIC && input.readUnsignedByte() == VERSION) { "Unsupported encrypted mod version" }
        val nonce = ByteArray(8).also(input::readFully)
        val createdAt = input.readLong()
        val name = input.readUTF()
        val payloadSize = input.readLong()
        val hash = input.readUTF()
        require(input.readInt() == CHUNK_SIZE && input.read() == -1) { "Invalid encrypted mod header" }
        Header(Metadata(NetworkModDescriptor(name, payloadSize, hash), createdAt), nonce)
    }
}
