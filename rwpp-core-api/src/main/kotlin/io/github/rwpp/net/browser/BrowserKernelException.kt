/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.browser

import io.github.rwpp.app.UpdateDownloadCancelledException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import java.util.concurrent.CancellationException

enum class BrowserKernelFailureKind { NETWORK, VERIFICATION, FILE_ACCESS, INSTALLATION }

data class BrowserKernelFileState(val exists: Boolean?, val directory: Boolean?, val readOnly: Boolean?, val hidden: Boolean?) {
    companion object {
        internal fun capture(file: String): BrowserKernelFileState? = runCatching {
            val path = File(file).toPath()
            val basic = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
            catch (_: NoSuchFileException) { return@runCatching BrowserKernelFileState(false, false, null, null) }
            val dos = runCatching { Files.readAttributes(path, DosFileAttributes::class.java, NOFOLLOW_LINKS) }.getOrNull()
            BrowserKernelFileState(true, basic.isDirectory, dos?.isReadOnly, dos?.isHidden)
        }.getOrElse { BrowserKernelFileState(null, null, null, null) }
    }
}

/** Retain the failing operation and the OS diagnosis without guessing which application denied access. */
class BrowserKernelException(
    val kind: BrowserKernelFailureKind,
    val stage: BrowserKernelStage,
    val path: String?,
    val systemReason: String?,
    message: String,
    cause: Throwable,
    val fileState: BrowserKernelFileState? = null,
) : IOException(message, cause) {
    internal fun isRetryableAccessDenial(): Boolean = kind == BrowserKernelFailureKind.FILE_ACCESS &&
        generateSequence<Throwable>(this) { it.cause }.any { it is AccessDeniedException }

    companion object {
        fun from(error: Throwable, stage: BrowserKernelStage = BrowserKernelStage.CHECKING,
            path: String? = null, fallback: BrowserKernelFailureKind = BrowserKernelFailureKind.INSTALLATION): BrowserKernelException {
            if (error is BrowserKernelException) return error
            val filesystem = generateSequence(error) { it.cause }.filterIsInstance<FileSystemException>().firstOrNull()
            val access = filesystem != null || generateSequence(error) { it.cause }.any { it is SecurityException }
            val actualPath = filesystem?.file ?: path
            val reason = filesystem?.reason ?: filesystem?.javaClass?.simpleName ?: error.message
            val kind = if (access) BrowserKernelFailureKind.FILE_ACCESS else fallback
            val fileState = actualPath?.takeIf { kind == BrowserKernelFailureKind.FILE_ACCESS }?.let(BrowserKernelFileState::capture)
            val message = buildString {
                append("Browser component ").append(stage.name.lowercase()).append(" failed (").append(kind.name.lowercase()).append(')')
                actualPath?.let { append(": ").append(it) }
                reason?.let { append(": ").append(it) }
                fileState?.let { append(" [fileState=").append(it).append(']') }
            }
            return BrowserKernelException(kind, stage, actualPath, reason, message, error, fileState)
        }
    }
}

internal inline fun <T> browserKernelOperation(stage: BrowserKernelStage, path: String? = null,
    kind: BrowserKernelFailureKind = BrowserKernelFailureKind.INSTALLATION, block: () -> T): T = try {
    block()
} catch (error: Exception) {
    if (error is UpdateDownloadCancelledException || error is CancellationException) throw error
    throw BrowserKernelException.from(error, stage, path, kind)
}

/** Keep output flush/close failures out of the surrounding network or archive error category. */
internal fun browserKernelOutput(path: String, stage: BrowserKernelStage, open: () -> OutputStream): OutputStream {
    val output = browserKernelOperation(stage, path, BrowserKernelFailureKind.FILE_ACCESS, open)
    return object : OutputStream() {
        override fun write(value: Int) = browserKernelOperation(stage, path, BrowserKernelFailureKind.FILE_ACCESS) { output.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) = browserKernelOperation(stage, path, BrowserKernelFailureKind.FILE_ACCESS) { output.write(bytes, offset, length) }
        override fun flush() = browserKernelOperation(stage, path, BrowserKernelFailureKind.FILE_ACCESS) { output.flush() }
        override fun close() = browserKernelOperation(stage, path, BrowserKernelFailureKind.FILE_ACCESS) { output.close() }
    }
}

/** A failed cleanup must never replace a denied write, a checksum failure, or cancellation. */
internal inline fun <T> withBrowserKernelCleanup(cleanup: () -> Unit, block: () -> T): T {
    var failure: Throwable? = null
    try {
        return block()
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try { cleanup() } catch (cleanupError: Throwable) {
            val original = failure
            if (original == null) throw cleanupError
            if (original !== cleanupError) original.addSuppressed(cleanupError)
        }
    }
}
