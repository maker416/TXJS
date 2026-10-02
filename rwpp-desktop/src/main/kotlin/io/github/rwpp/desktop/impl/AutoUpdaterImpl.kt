/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import io.github.rwpp.app.AutoUpdater
import io.github.rwpp.app.AutoUpdater.Companion.PROGRESS_FAILED
import io.github.rwpp.app.UpdateDownloadSession
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import kotlinx.coroutines.CancellationException
import okhttp3.Request
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.SequenceInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

@Single
class AutoUpdaterImpl : AutoUpdater, KoinComponent {
    private val net: Net by inject()
    private val updateLock = Any()
    private var activeUpdate: UpdateDownloadSession? = null

    override fun isSupported(): Boolean = true

    override fun cancelPendingUpdate() {
        synchronized(updateLock) { activeUpdate?.cancel() }
    }

    override fun downloadAndInstall(downloadUrls: List<String>, sha256Url: String?, onProgress: (Float) -> Unit) {
        if (downloadUrls.isEmpty()) {
            onProgress(PROGRESS_FAILED)
            return
        }
        val session = synchronized(updateLock) {
            if (activeUpdate != null) null else UpdateDownloadSession().also { activeUpdate = it }
        } ?: run {
            onProgress(PROGRESS_FAILED)
            return
        }

        var workDir: File? = null
        var installerStarted = false
        try {
            onProgress(0f)
            session.checkActive()
            // 每轮独立目录：旧下载取消与新请求不会写同一个 exe 或分卷文件。
            val tempRoot = File(System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir"))
            val directory = Files.createTempDirectory(tempRoot.toPath(), "RWJS-update-").toFile()
            workDir = directory
            val outputFile = File(directory, "RWJS-Setup-update.exe")
            val progress: (Float) -> Unit = { value ->
                session.checkActive()
                onProgress(value)
            }
            val installer = if (downloadUrls.size == 1) {
                downloadSingle(session, downloadUrls[0], outputFile, progress)
            } else {
                downloadSplitVolumes(session, downloadUrls, sha256Url, outputFile, directory, progress)
            }
            session.checkActive()
            logger.info("Download completed: ${installer.absolutePath}")

            // 与 cancelPendingUpdate 线性化：取消先取得锁时不再启动安装/退出游戏。
            installerStarted = session.startInstallation {
                ProcessBuilder(installer.absolutePath, "RWPP_UPDATE_MODE=1").start()
            }
        } catch (e: CancellationException) {
            session.cancel()
            logger.info("Update download cancelled")
        } catch (e: Exception) {
            if (session.isCancelled) {
                logger.info("Update download cancelled")
            } else {
                logger.error("Failed to download update: ${e.stackTraceToString()}")
                onProgress(PROGRESS_FAILED)
            }
        } finally {
            // 清理失败只留下临时文件，绝不能改变取消结果或触发安装。
            val directory = workDir
            if (directory != null) {
                runCatching {
                    directory.walkBottomUp().forEach { file ->
                        if (!installerStarted || file.name != "RWJS-Setup-update.exe") file.delete()
                    }
                }.onFailure { logger.warn("Failed to clean update temporary files", it) }
            }
            synchronized(updateLock) { if (activeUpdate === session) activeUpdate = null }
        }
        if (installerStarted) exitProcess(0)
    }

    private fun downloadSingle(
        session: UpdateDownloadSession,
        downloadUrl: String,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): File {
        val request = Request.Builder().url(downloadUrl).build()
        return session.execute(net.client.newCall(request)) { response ->
            if (!response.isSuccessful) throw IOException("Update download returned HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty update response")
            val contentLength = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        session.checkActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        session.checkActive()
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (contentLength > 0) onProgress(downloaded.toFloat() / contentLength)
                    }
                }
            }
            outputFile
        }
    }

    /** zip 按字节分卷：顺序下载并校验后流式拼接解压，不生成合并后的大 zip。 */
    private fun downloadSplitVolumes(
        session: UpdateDownloadSession,
        downloadUrls: List<String>,
        sha256Url: String?,
        outputFile: File,
        workDir: File,
        onProgress: (Float) -> Unit,
    ): File {
        val partFiles = mutableListOf<File>()
        var totalBytes = 0L
        var totalKnown = true
        for (url in downloadUrls) {
            session.checkActive()
            val length = headContentLength(session, url)
            if (length <= 0) {
                totalKnown = false
                break
            }
            totalBytes += length
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var downloadedTotal = 0L
        for ((index, url) in downloadUrls.withIndex()) {
            session.checkActive()
            val partFile = File(workDir, "part-$index.zip")
            downloadPart(session, url, partFile, digest, buffer) { partDownloaded, partLength ->
                val progress = when {
                    totalKnown && totalBytes > 0 ->
                        (downloadedTotal + partDownloaded).toFloat() / totalBytes
                    partLength > 0 ->
                        (index + partDownloaded.toFloat() / partLength) / downloadUrls.size
                    else -> index.toFloat() / downloadUrls.size
                }
                onProgress(progress * DOWNLOAD_PROGRESS_WEIGHT)
            }
            downloadedTotal += partFile.length()
            partFiles += partFile
        }

        session.checkActive()
        if (sha256Url != null) {
            val expected = fetchExpectedSha256(session, sha256Url)
                ?: throw IOException("Failed to fetch update sha256 file")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) throw IOException("Update sha256 mismatch")
        }
        return extractExeFromVolumes(session, partFiles, outputFile, buffer) { extracted, total ->
            val fraction = if (total > 0) extracted.toFloat() / total else 0f
            onProgress(DOWNLOAD_PROGRESS_WEIGHT + fraction * (1f - DOWNLOAD_PROGRESS_WEIGHT))
        }
    }

    private fun headContentLength(session: UpdateDownloadSession, url: String): Long {
        return try {
            val request = Request.Builder().url(url).head().build()
            session.execute(net.client.newCall(request)) { response ->
                if (!response.isSuccessful) -1L else response.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        } catch (e: IOException) {
            session.checkActive()
            -1L
        }
    }

    private fun downloadPart(
        session: UpdateDownloadSession,
        url: String,
        outputFile: File,
        digest: MessageDigest,
        buffer: ByteArray,
        onProgress: (downloaded: Long, contentLength: Long) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        session.execute(net.client.newCall(request)) { response ->
            if (!response.isSuccessful) throw IOException("Update part returned HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty update part")
            val contentLength = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    var downloaded = 0L
                    while (true) {
                        session.checkActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        session.checkActive()
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, contentLength)
                    }
                }
            }
        }
    }

    /** 校验文件首个 token 为 SHA-256，可后跟文件名。 */
    private fun fetchExpectedSha256(session: UpdateDownloadSession, sha256Url: String): String? {
        val request = Request.Builder().url(sha256Url).build()
        return session.execute(net.client.newCall(request)) { response ->
            if (!response.isSuccessful) throw IOException("Update checksum returned HTTP ${response.code}")
            response.body?.string()
                ?.lineSequence()?.firstOrNull()
                ?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                ?.takeIf { it.length == 64 }
        }
    }

    private fun extractExeFromVolumes(
        session: UpdateDownloadSession,
        partFiles: List<File>,
        outputFile: File,
        buffer: ByteArray,
        onProgress: (extracted: Long, entrySize: Long) -> Unit,
    ): File {
        session.checkActive()
        val streams = partFiles.map { BufferedInputStream(it.inputStream()) }
        ZipInputStream(SequenceInputStream(Collections.enumeration(streams))).use { zipIn ->
            var exeEntry: java.util.zip.ZipEntry? = null
            var firstFileEntry: java.util.zip.ZipEntry? = null
            while (true) {
                session.checkActive()
                val entry = zipIn.nextEntry ?: break
                if (entry.isDirectory) continue
                if (firstFileEntry == null) firstFileEntry = entry
                if (entry.name.endsWith(".exe", ignoreCase = true)) {
                    exeEntry = entry
                    break
                }
            }
            val target = exeEntry ?: firstFileEntry
                ?: throw IOException("No file entry found in update zip volumes")
            FileOutputStream(outputFile).use { output ->
                var extracted = 0L
                while (true) {
                    session.checkActive()
                    val read = zipIn.read(buffer)
                    if (read == -1) break
                    session.checkActive()
                    output.write(buffer, 0, read)
                    extracted += read
                    onProgress(extracted, target.size)
                }
            }
            logger.info("Extracted installer '${target.name}' from ${partFiles.size} zip volumes")
            return outputFile
        }
    }

    private companion object {
        const val DOWNLOAD_PROGRESS_WEIGHT = 0.9f
    }
}
