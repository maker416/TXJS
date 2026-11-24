/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.io.AtomicFileWrite
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files

internal const val MAX_DIRECT_RESUME_BYTES = 1024 * 1024

/** Another read cannot recover these bytes; a newly issued grant may replace them. */
internal class DirectResumeCorruptException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Blocking operations. A missing record returns null. Storage or decryption failures propagate. */
internal interface DirectResumeVault {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun delete()
}

internal expect fun platformDirectResumeVault(): DirectResumeVault

internal abstract class EncryptedDirectResumeFile(private val file: File) : DirectResumeVault {
    protected abstract fun encrypt(bytes: ByteArray): ByteArray
    protected abstract fun decrypt(bytes: ByteArray): ByteArray

    override fun read(): ByteArray? {
        if (Files.notExists(file.toPath())) return null
        val ciphertext = FileInputStream(file).use { stream ->
            val size = stream.channel.size()
            if (size !in 1L..(MAX_DIRECT_RESUME_BYTES + 4096L)) throw DirectResumeCorruptException("Invalid reconnect file size")
            ByteArray(size.toInt()).also { DataInputStream(stream).readFully(it) }
        }
        return decrypt(ciphertext).also {
            if (it.size > MAX_DIRECT_RESUME_BYTES) throw DirectResumeCorruptException("Invalid reconnect record size")
        }
    }

    override fun write(bytes: ByteArray) {
        require(bytes.size <= MAX_DIRECT_RESUME_BYTES)
        val ciphertext = encrypt(bytes)
        AtomicFileWrite.write(file) { it.write(ciphertext) }
    }

    override fun delete() {
        Files.deleteIfExists(file.toPath())
    }
}
