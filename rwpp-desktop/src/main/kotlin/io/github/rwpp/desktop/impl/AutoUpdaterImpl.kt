/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import io.github.rwpp.app.AutoUpdater
import io.github.rwpp.app.AutoUpdater.Companion.PROGRESS_FAILED
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import okhttp3.Request
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

@Single
class AutoUpdaterImpl : AutoUpdater, KoinComponent {
    private val net: Net by inject()

    override fun isSupported(): Boolean = true

    override fun downloadAndInstall(downloadUrls: List<String>, sha256Url: String?, onProgress: (Float) -> Unit) {
        if (downloadUrls.isEmpty()) {
            onProgress(PROGRESS_FAILED)
            return
        }

        val tempDir = System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir")
        val outputFile = File(tempDir, "RWJS-Setup-update.exe")

        val installer = if (downloadUrls.size == 1) {
            // 旧的单 exe 安装包
            downloadSingle(downloadUrls[0], outputFile, onProgress)
        } else {
            // zip 分卷：顺序下载 -> sha256 校验 -> 流式拼接解出 exe
            downloadSplitVolumes(downloadUrls, sha256Url, outputFile, onProgress)
        }

        if (installer == null) {
            onProgress(PROGRESS_FAILED)
            return
        }

        logger.info("Download completed: ${installer.absolutePath}")

        val processBuilder = ProcessBuilder(
            installer.absolutePath,
            "RWPP_UPDATE_MODE=1"
        )
        processBuilder.start()
        exitProcess(0)
    }

    /** 下载单文件安装包，成功返回安装包文件 */
    private fun downloadSingle(downloadUrl: String, outputFile: File, onProgress: (Float) -> Unit): File? {
        val request = Request.Builder().url(downloadUrl).build()
        return runCatching {
            net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val body = response.body ?: return null
                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded: Long = 0
                        var read: Int

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (contentLength > 0) {
                                onProgress(downloaded.toFloat() / contentLength.toFloat())
                            }
                        }
                    }
                }
                outputFile
            }
        }.onFailure {
            logger.error("Failed to download update: ${it.stackTraceToString()}")
        }.getOrNull()
    }

    /**
     * 下载 zip 分卷并流式合并解出安装包。
     *
     * 分卷是「一个 zip 按字节切段」，按序号拼接即为合法 zip；因此下载后用
     * [SequenceInputStream] 串起所有分卷直接喂给 [ZipInputStream]，
     * 无需在磁盘上生成合并后的完整 zip。zip 条目自带 CRC，配合可选的 sha256 强校验。
     *
     * 进度映射：下载 0.0~0.9，解压 0.9~1.0。
     */
    private fun downloadSplitVolumes(
        downloadUrls: List<String>,
        sha256Url: String?,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): File? {
        val tempDir = System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir")
        val workDir = File(tempDir, "RWJS-update-${System.currentTimeMillis()}")
        val partFiles = mutableListOf<File>()

        try {
            // 1) 预取各分卷大小以计算总进度（HEAD 失败则退化为按分卷个数估算）
            var totalBytes = 0L
            var totalKnown = true
            for (url in downloadUrls) {
                val length = headContentLength(url)
                if (length <= 0) {
                    totalKnown = false
                    break
                }
                totalBytes += length
            }

            // 2) 顺序下载所有分卷，同时累积 SHA-256
            workDir.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var downloadedTotal = 0L

            for ((index, url) in downloadUrls.withIndex()) {
                val partFile = File(workDir, "part-$index.zip")
                val ok = downloadPart(url, partFile, digest, buffer) { partDownloaded, partLength ->
                    val progress = when {
                        totalKnown && totalBytes > 0 ->
                            (downloadedTotal + partDownloaded).toFloat() / totalBytes.toFloat()
                        partLength > 0 ->
                            (index + partDownloaded.toFloat() / partLength.toFloat()) / downloadUrls.size
                        else -> index.toFloat() / downloadUrls.size
                    }
                    onProgress(progress * DOWNLOAD_PROGRESS_WEIGHT)
                }
                if (!ok) return null
                downloadedTotal += partFile.length()
                partFiles += partFile
            }

            // 3) sha256 强校验（发布侧提供了校验文件时）
            val expectedSha256 = fetchExpectedSha256(sha256Url)
            if (sha256Url != null) {
                if (expectedSha256 == null) {
                    logger.error("Failed to fetch update sha256 file: $sha256Url")
                    return null
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    logger.error("Update sha256 mismatch: expected=$expectedSha256 actual=$actual")
                    return null
                }
            }

            // 4) 流式拼接所有分卷，解出其中的 exe
            extractExeFromVolumes(partFiles, outputFile, buffer) { extracted, total ->
                val fraction = if (total > 0) extracted.toFloat() / total.toFloat() else 0f
                onProgress(DOWNLOAD_PROGRESS_WEIGHT + fraction * (1f - DOWNLOAD_PROGRESS_WEIGHT))
            } ?: return null

            return outputFile
        } catch (e: Exception) {
            logger.error("Failed to download split update: ${e.stackTraceToString()}")
            return null
        } finally {
            // 分卷只在合并解压期间需要，结束后清理；失败时也尽力清理，避免残留大文件
            partFiles.forEach { it.delete() }
            workDir.delete()
        }
    }

    private fun headContentLength(url: String): Long {
        return runCatching {
            val request = Request.Builder().url(url).head().build()
            net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return -1L
                response.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        }.getOrDefault(-1L)
    }

    private fun downloadPart(
        url: String,
        outputFile: File,
        digest: MessageDigest,
        buffer: ByteArray,
        onProgress: (downloaded: Long, contentLength: Long) -> Unit,
    ): Boolean {
        val request = Request.Builder().url(url).build()
        return runCatching {
            net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        var downloaded: Long = 0
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, contentLength)
                        }
                    }
                }
                true
            }
        }.onFailure {
            logger.error("Failed to download update part $url: ${it.stackTraceToString()}")
        }.getOrDefault(false)
    }

    /** 读取 sha256 校验文件内容（格式：`"<hash>  <文件名>"` 或纯 hash），取首个 token */
    private fun fetchExpectedSha256(sha256Url: String?): String? {
        if (sha256Url == null) return null
        val request = Request.Builder().url(sha256Url).build()
        return runCatching {
            net.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
                    ?.lineSequence()?.firstOrNull()
                    ?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                    ?.takeIf { it.length == 64 }
            }
        }.getOrNull()
    }

    /**
     * 将分卷按顺序拼接为 zip 流，解出其中第一个 `.exe` 条目（无 exe 条目时取首个文件条目）。
     * 返回 exe 文件；找不到条目或流损坏（CRC 校验失败抛异常）时返回 null。
     */
    private fun extractExeFromVolumes(
        partFiles: List<File>,
        outputFile: File,
        buffer: ByteArray,
        onProgress: (extracted: Long, entrySize: Long) -> Unit,
    ): File? {
        return runCatching {
            val streams = partFiles.map { BufferedInputStream(it.inputStream()) }
            ZipInputStream(SequenceInputStream(Collections.enumeration(streams))).use { zipIn ->
                var exeEntry: java.util.zip.ZipEntry? = null
                var firstFileEntry: java.util.zip.ZipEntry? = null
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (firstFileEntry == null) firstFileEntry = entry
                    if (entry.name.endsWith(".exe", ignoreCase = true)) {
                        exeEntry = entry
                        break
                    }
                }
                val target = exeEntry ?: firstFileEntry
                    ?: error("No file entry found in update zip volumes")

                FileOutputStream(outputFile).use { output ->
                    var extracted: Long = 0
                    var read: Int
                    while (zipIn.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        extracted += read
                        onProgress(extracted, target.size)
                    }
                }
                logger.info("Extracted installer '${target.name}' from ${partFiles.size} zip volumes")
                outputFile
            }
        }.onFailure {
            logger.error("Failed to extract installer from zip volumes: ${it.stackTraceToString()}")
        }.getOrNull()
    }

    private companion object {
        /** 下载阶段占总进度的权重，剩余部分为解压阶段 */
        const val DOWNLOAD_PROGRESS_WEIGHT = 0.9f
    }
}
