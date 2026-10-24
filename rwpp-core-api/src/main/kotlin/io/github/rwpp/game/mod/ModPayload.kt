/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.game.mod

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/** Immutable on-disk upload snapshot; close releases only this operation's temporary file. */
class ModPayload(val descriptor: NetworkModDescriptor, val file: File) : Closeable {
    override fun close() { file.delete() }
}

private class PayloadDigestOutput(
    private val target: OutputStream,
    private val checkCancelled: () -> Unit,
) : OutputStream() {
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set
    override fun write(value: Int) {
        checkCancelled()
        target.write(value)
        digest.update(value.toByte())
        count++
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkCancelled()
        target.write(bytes, offset, length)
        digest.update(bytes, offset, length)
        count += length
    }
    override fun flush() = target.flush()
    fun descriptor(name: String) = NetworkModDescriptor(name, count, digest.digest().joinToString("") { "%02x".format(it) })
}

fun Mod.payloadDescriptor(checkCancelled: () -> Unit = {}): NetworkModDescriptor {
    val output = PayloadDigestOutput(object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }, checkCancelled)
    writePayloadTo(output)
    return output.descriptor(name)
}

fun Mod.preparePayload(directory: File, checkCancelled: () -> Unit = {}): ModPayload {
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create payload directory" }
    val file = File.createTempFile("mod-upload-", ".tmp", directory)
    try {
        val descriptor = file.outputStream().buffered(64 * 1024).use { target ->
            val output = PayloadDigestOutput(target, checkCancelled)
            writePayloadTo(output)
            output.flush()
            output.descriptor(name)
        }
        return ModPayload(descriptor, file)
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}
