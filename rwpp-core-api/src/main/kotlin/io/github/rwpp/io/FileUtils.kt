/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.io

import java.io.File
import java.util.zip.ZipFile

fun File.calculateSize(): Long {
    var size = 0L
    if(this.isFile) {
        return this.length()
    } else {
        this.listFiles()?.forEach {
            size += if(it.isFile) it.length() else it.calculateSize()
        }
    }

    return size
}

suspend fun File.copyToWithProgress(
    target: File,
    overwrite: Boolean = false,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
    onProgress: suspend (copiedBytes: Long, totalBytes: Long) -> Unit
): File {
    if (!exists()) {
        throw kotlin.io.NoSuchFileException(this, reason = "The source file does not exist.")
    }
    if (target.exists()) {
        if (!overwrite) {
            throw kotlin.io.FileAlreadyExistsException(this, target, "The destination file already exists.")
        } else if (!target.delete()) {
            throw kotlin.io.FileAlreadyExistsException(this, target, "Tried to overwrite the destination, but could not delete it.")
        }
    }

    target.parentFile?.mkdirs()

    val totalBytes = length()
    var copiedBytes = 0L
    onProgress(copiedBytes, totalBytes)

    inputStream().use { input ->
        target.outputStream().use { output ->
            val buffer = ByteArray(bufferSize)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                copiedBytes += read
                onProgress(copiedBytes, totalBytes)
            }
        }
    }

    return target
}

fun File.unzipTo(targetFile: File, from: String = "") {
    val zipFile = ZipFile(this)
    for(entry in zipFile.entries()) {
        if (entry.name.startsWith(from)) {
            if(entry.isDirectory) {
                File(targetFile, entry.name).mkdirs()
            } else {
                val file = File(targetFile, entry.name)
                if(!file.exists()) {
                    if(!file.parentFile.exists()) file.parentFile.mkdirs()
                    file.createNewFile()
                }
                file.writeBytes(zipFile.getInputStream(entry).readBytes())
            }
        }
    }
}
