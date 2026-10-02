/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * rwmod/ini：原文件 SHA-256，与网络缓存/传输一致。
 * 目录：同 zipFolderToByte 的规范化 ZIP（递归名称排序、/ 分隔、时间=0、level=1）SHA-256。
 * 流式处理，不读取整个模组到内存；目录名与 mtime 不参与身份。
 */
object ModPlaytimeHash {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (file.isDirectory) {
            val root = file.canonicalFile
            val sink = object : OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {}
            }
            ZipOutputStream(DigestOutputStream(sink, digest)).use { zip ->
                zip.setLevel(1)
                val visitedDirectories = mutableSetOf<String>()
                fun append(source: File) {
                    val canonical = source.canonicalFile
                    if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
                        throw IOException("Mod folder contains a link outside its root")
                    }
                    if (source.isDirectory) {
                        if (!visitedDirectories.add(canonical.path)) throw IOException("Mod folder contains a directory link cycle")
                        val children = source.listFiles() ?: throw IOException("Cannot read mod folder")
                        children.sortedBy { it.name }.forEach(::append)
                    } else if (source.isFile) {
                        val relative = root.toPath().relativize(source.absoluteFile.toPath()).toString().replace('\\', '/')
                        zip.putNextEntry(ZipEntry(relative).apply { time = 0L })
                        source.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    } else throw IOException("Cannot read mod file")
                }
                append(root)
            }
        } else {
            if (!file.isFile) throw IOException("Mod source does not exist")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
