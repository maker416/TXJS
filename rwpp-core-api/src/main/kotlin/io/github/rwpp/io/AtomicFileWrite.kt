/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.io

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 先在同目录完整写入临时文件，再替换目标；暂存失败不会截断旧配置。 */
object AtomicFileWrite {
    fun text(file: File, content: String) = write(file) { it.write(content.toByteArray(Charsets.UTF_8)) }

    fun write(file: File, writer: (OutputStream) -> Unit) {
        val target = file.absoluteFile.toPath()
        val parent = target.parent ?: throw IOException("Missing target directory")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${target.fileName}.", ".tmp")
        try {
            Files.newOutputStream(temporary).use(writer)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
