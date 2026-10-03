/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.browser

import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.zip.ZipFile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

interface BrowserModTransfer {
    suspend fun download(target: File, onProgress: (Long, Long?) -> Unit)
    fun cancel()
}

data class BrowserModDownload(
    val fileName: String,
    val totalBytes: Long?,
    val transfer: BrowserModTransfer,
)

/** 响应提供的名称优先于 URL；查询参数和 MIME 类型不会改变 rwmod 后缀。 */
fun browserModFileName(url: String, contentDisposition: String? = null, suggestedName: String? = null): String? {
    val extended = Regex("(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
        .find(contentDisposition.orEmpty())?.groupValues?.get(1)?.trim()?.trim('"')
    val encodedName = extended?.split('\'', limit = 3)?.takeIf { it.size == 3 && it[0].equals("UTF-8", true) }
        ?.let { runCatching { URLDecoder.decode(it[2].replace("+", "%2B"), "UTF-8") }.getOrNull() }
    val ordinary = Regex("(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", RegexOption.IGNORE_CASE)
        .find(contentDisposition.orEmpty())?.let { it.groupValues[1].ifEmpty { it.groupValues[2].trim() } }
    val name = sequenceOf(encodedName, ordinary, suggestedName, url.toHttpUrlOrNull()?.pathSegments?.lastOrNull())
        .firstOrNull { !it.isNullOrBlank() } ?: return null
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    if (!base.endsWith(".rwmod", ignoreCase = true)) return null
    var stem = base.dropLast(6).map { if (it.isISOControl() || it in "<>:\"/\\|?*") '_' else it }.joinToString("")
        .trim().trimEnd('.')
    while (stem.toByteArray(Charsets.UTF_8).size > 180) {
        stem = stem.dropLast(if (stem.last().isLowSurrogate() && stem.length > 1) 2 else 1)
    }
    if (stem.isBlank()) stem = "mod"
    if (stem.substringBefore('.').uppercase() in setOf("CON", "PRN", "AUX", "NUL", *(1..9).map { "COM$it" }.toTypedArray(), *(1..9).map { "LPT$it" }.toTypedArray())) {
        stem = "_$stem"
    }
    return "$stem.rwmod"
}

/** 下载先写 .part，完整 ZIP 才进入模组列表。已有模组始终保留，同名新文件另存。 */
object BrowserModFiles {
    fun createPartial(directory: File): File {
        Files.createDirectories(directory.toPath())
        return File.createTempFile(".browser-mod-", ".part", directory)
    }

    @Synchronized
    fun install(partial: File, directory: File, fileName: String): File {
        require(browserModFileName("", suggestedName = fileName) == fileName) { "Invalid mod filename" }
        require(partial.canonicalFile.parentFile == directory.canonicalFile) { "Download outside mod directory" }
        ZipFile(partial).use { if (!it.entries().hasMoreElements()) throw IOException("Empty mod archive") }
        val root = directory.canonicalFile
        val stem = fileName.removeSuffix(".rwmod")
        for (index in 1..10000) {
            val name = if (index == 1) fileName else "$stem ($index).rwmod"
            if (root.list()?.any { it.equals(name, ignoreCase = true) } == true) continue
            val target = File(root, name)
            try {
                // 不指定 REPLACE_EXISTING，避免覆盖其他下载或用户已有的文件。
                Files.move(partial.toPath(), target.toPath())
                return target
            } catch (_: FileAlreadyExistsException) { }
        }
        throw IOException("Too many mods with the same filename")
    }
}
