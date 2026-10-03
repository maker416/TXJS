/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.browser

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** HTML accept 是逗号分隔的扩展名/MIME；图片选择不能被模组默认来源接管。 */
class BrowserUploadAccept(types: List<String>) {
    private val filters = types.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    fun accepts(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return filters.isEmpty() || filters.any { filter ->
            when {
                filter == "*/*" || filter == "*" -> true
                filter.startsWith('.') -> fileName.lowercase().endsWith(filter)
                filter == "application/octet-stream" || filter == "application/*" -> true
                filter in setOf("application/zip", "application/x-zip-compressed", "application/x-rwmod") -> extension in setOf("rwmod", "zip")
                filter == "text/plain" -> extension in setOf("ini", "txt")
                else -> false
            }
        }
    }
    val supportsMods: Boolean get() = accepts("mod.rwmod") || accepts("mod.zip") || accepts("unit.ini")
}

/** 上传使用私有快照，整个浏览器会话保留文件，避免网页尚在读文件就删掉临时包。 */
class BrowserModUploadCache(private val parent: File) : AutoCloseable {
    @Volatile private var closed = false
    private var root: File? = null

    private fun checkOpen() { if (closed) throw IOException("Browser upload closed") }

    fun prepare(source: File, uploadName: String = if (source.isDirectory) source.name + ".rwmod" else source.name,
        checkCancelled: () -> Unit = {},
    ): File {
        require(uploadName.isNotBlank() && File(uploadName).name == uploadName && '/' !in uploadName && '\\' !in uploadName)
        val directory = synchronized(this) {
            checkOpen()
            val cache = root ?: run {
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create upload cache")
                File.createTempFile("rwjs-browser-upload-", "", parent).apply {
                    if (!delete() || !mkdir()) throw IOException("Cannot create upload cache")
                }.also { root = it }
            }
            File.createTempFile("mod-", "", cache).apply {
                if (!delete() || !mkdir()) throw IOException("Cannot create upload snapshot")
            }
        }
        try {
            val input = source.canonicalFile
            val target = File(directory, uploadName)
            if (input.isDirectory) {
                val visited = mutableSetOf<String>()
                ZipOutputStream(target.outputStream().buffered()).use { zip ->
                    zip.setLevel(1)
                    fun append(file: File) {
                        checkOpen()
                        checkCancelled()
                        val canonical = file.canonicalFile
                        if (canonical != input && !canonical.path.startsWith(input.path + File.separator)) {
                            throw IOException("Mod folder contains a link outside its root")
                        }
                        if (file.isDirectory) {
                            if (!visited.add(canonical.path)) throw IOException("Mod folder contains a directory link cycle")
                            val children = file.listFiles() ?: throw IOException("Cannot read mod folder")
                            children.sortedBy { it.name }.forEach(::append)
                        } else {
                            if (!file.isFile) throw IOException("Cannot read mod file")
                            val nameInZip = input.toPath().relativize(file.absoluteFile.toPath()).toString().replace('\\', '/')
                            zip.putNextEntry(ZipEntry(nameInZip).apply { time = 0L })
                            copy(file, zip, checkCancelled)
                            zip.closeEntry()
                        }
                    }
                    append(input)
                }
            } else {
                if (!input.isFile) throw IOException("Mod file no longer exists")
                target.outputStream().use { copy(input, it, checkCancelled) }
            }
            checkOpen()
            checkCancelled()
            return target
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    private fun copy(source: File, output: java.io.OutputStream, checkCancelled: () -> Unit) {
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkOpen()
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
    }

    override fun close() {
        val cache = synchronized(this) { closed = true; root }
        if (cache != null) {
            // Chromium 在 Windows 上异步关闭读取句柄；此方法由平台后台清理线程执行。
            repeat(20) {
                if (!cache.exists() || cache.deleteRecursively()) return
                Thread.sleep(100)
            }
        }
    }
}
