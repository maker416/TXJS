/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.io

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

enum class ExternalFileType { Extension, Mod, Save, Replay, Map, Unknown }

fun externalFileType(fileName: String): ExternalFileType = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "rwres", "rwext", "jar" -> ExternalFileType.Extension
    "rwmod" -> ExternalFileType.Mod
    "rwsave" -> ExternalFileType.Save
    "replay", "reply" -> ExternalFileType.Replay
    "tmx" -> ExternalFileType.Map
    else -> ExternalFileType.Unknown
}

/** Stream a document into a sibling temp file, leaving an existing destination intact on copy failure. */
fun copyImportedFile(input: InputStream, directory: File, name: String) {
    require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')) {
        "Invalid document name"
    }
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create import directory" }
    val target = File(directory, name)
    val temp = File.createTempFile("import-", ".tmp", directory)
    try {
        temp.outputStream().buffered(64 * 1024).use { input.copyTo(it, 64 * 1024) }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally { temp.delete() }
}
