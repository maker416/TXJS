/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.app

import io.github.rwpp.net.UpdateDownloadPlan
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipInputStream

/** 两端共用的下载/校验/解包路径，不落合并后的大 ZIP，不在游戏线程运行。 */
class UpdatePackageDownloader(private val client: OkHttpClient) {
    fun download(
        plan: UpdateDownloadPlan,
        session: UpdateDownloadSession,
        workDirectory: File,
        output: File,
        extension: String,
        onStatus: (UpdateProgress) -> Unit,
    ): File {
        require(plan.partUrls.isNotEmpty()) { "Missing update assets" }
        require(plan.isArchive || plan.partUrls.size == 1) { "Multiple raw installers are invalid" }
        workDirectory.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val parts = mutableListOf<File>()
        var downloaded = 0L
        var total = plan.partSizes.takeIf { it.size == plan.partUrls.size && it.all { size -> size > 0 } }?.sum() ?: 0L
        val started = System.nanoTime()
        var lastReport = 0L
        fun report(index: Int, partBytes: Long, partLength: Long, force: Boolean = false) {
            val now = System.nanoTime()
            if (!force && now - lastReport < 100_000_000L) return
            lastReport = now
            val fraction = if (total > 0) (downloaded + partBytes).toFloat() / total
                else (index + if (partLength > 0) partBytes.toFloat() / partLength else 0f) / plan.partUrls.size
            val elapsed = (now - started).coerceAtLeast(1)
            onStatus(UpdateProgress(UpdateStage.DOWNLOADING, fraction.coerceIn(0f, 1f) * 0.9f,
                downloaded + partBytes, total, ((downloaded + partBytes) * 1_000_000_000.0 / elapsed).toLong(),
                index + 1, plan.partUrls.size))
        }
        val buffer = ByteArray(64 * 1024)
        plan.partUrls.forEachIndexed { index, url ->
            session.checkActive()
            val partFile = if (plan.isArchive) File(workDirectory, "part-$index") else output
            val request = Request.Builder().url(url).build()
            session.execute(client.newCall(request)) { response ->
                if (!response.isSuccessful) throw IOException("Update download returned HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty update response")
                val length = body.contentLength()
                if (plan.partUrls.size == 1 && total == 0L && length > 0) total = length
                var partBytes = 0L
                body.byteStream().use { input ->
                    partFile.outputStream().buffered().use { target ->
                        while (true) {
                            session.checkActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            session.checkActive()
                            target.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            partBytes += read
                            report(index, partBytes, length)
                        }
                    }
                }
                if (partBytes == 0L || (length >= 0 && partBytes != length) ||
                    (plan.partSizes.getOrNull(index)?.let { it > 0 && it != partBytes } == true)) {
                    throw IOException("Incomplete update package")
                }
                report(index, partBytes, length, true)
                downloaded += partBytes
            }
            parts += partFile
        }
        session.checkActive()
        onStatus(UpdateProgress(UpdateStage.VERIFYING, 0.9f, downloaded, total, part = parts.size, parts = parts.size))
        plan.sha256Url?.let { url ->
            val expected = session.execute(client.newCall(Request.Builder().url(url).build())) { response ->
                if (!response.isSuccessful) throw IOException("Cannot fetch update SHA-256")
                response.body?.string()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                    ?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
                    ?: throw IOException("Invalid update SHA-256")
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, true)) throw IOException("Update SHA-256 mismatch; please retry")
        }
        if (plan.isArchive) {
            onStatus(UpdateProgress(UpdateStage.EXTRACTING, 0.94f, downloaded, total, part = parts.size, parts = parts.size))
            var found = false
            ZipInputStream(SequenceInputStream(Collections.enumeration(parts.map { it.inputStream().buffered() }))).use { zip ->
                while (true) {
                    session.checkActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.endsWith(extension, true)) continue
                    if (found) throw IOException("Ambiguous update archive")
                    found = true
                    var extracted = 0L
                    output.outputStream().buffered().use { target ->
                        while (true) {
                            session.checkActive()
                            val read = zip.read(buffer)
                            if (read == -1) break
                            session.checkActive()
                            target.write(buffer, 0, read)
                            extracted += read
                        }
                    }
                    if (extracted == 0L) throw IOException("Empty update installer")
                    zip.closeEntry() // ZipInputStream 校验 entry CRC；即使旧发布没有 SHA-256 也检测损坏。
                }
            }
            if (!found) throw IOException("No $extension installer in update archive")
        }
        session.checkActive()
        return output
    }
}
