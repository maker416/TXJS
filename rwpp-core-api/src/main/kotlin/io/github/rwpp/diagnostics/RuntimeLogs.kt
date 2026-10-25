/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Calendar days in the device's timezone, including today. Never truncate a log on restart. */
class DailyLogOutputStream(
    val directory: File,
    private val today: () -> LocalDate = { LocalDate.now() },
) : OutputStream() {
    private var day: LocalDate? = null
    private var output: FileOutputStream? = null

    init {
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create log directory" }
        migrateLegacy(directory.listFiles().orEmpty().filter { it.name.matches(Regex("rwjs-\\d{8}-\\d{6}-.*\\.log")) })
        directory.listFiles().orEmpty().filter {
            it.name.matches(Regex("feedback-.*\\.zip")) && it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        }.forEach { it.delete() }
        rotate()
    }

    /** Preserve the old per-run/Android log on the first upgrade, using its last-modified day. */
    @Synchronized fun migrateLegacy(files: List<File>) {
        files.filter { it.isFile }.forEach { source ->
            val date = java.time.Instant.ofEpochMilli(source.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate()
            if (date.isBefore(today().minusDays(6))) { source.delete(); return@forEach }
            val target = File(directory, "rwjs-$date.log")
            if (source.canonicalFile == target.canonicalFile) return@forEach
            runCatching {
                FileOutputStream(target, true).buffered().use { sink ->
                    sink.write("\n[LEGACY LOG] ${source.name}\n".toByteArray(Charsets.UTF_8))
                    source.inputStream().use { it.copyTo(sink) }
                }
                source.delete()
            }
        }
    }

    @Synchronized private fun rotate() {
        val current = today()
        if (current == day && output != null) return
        output?.close()
        output = null
        prune(current)
        output = FileOutputStream(File(directory, "rwjs-$current.log"), true)
        day = current
    }

    private fun prune(current: LocalDate) {
        directory.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
            val date = logDate(file.name) ?: return@forEach
            if (date.isBefore(current.minusDays(6))) file.delete()
        }
    }

    @Synchronized override fun write(value: Int) { rotate(); output!!.write(value) }
    @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
        rotate(); output!!.write(bytes, offset, length)
    }
    @Synchronized override fun flush() { output?.flush() }
    @Synchronized override fun close() { output?.close(); output = null }

    /** Briefly lock the writer to capture lengths; compression never holds up the game/logging thread. */
    @Synchronized fun recentFiles(): List<LogFileSnapshot> {
        rotate()
        flush()
        val current = today()
        return directory.listFiles().orEmpty().filter { file ->
            val date = logDate(file.name)
            file.isFile && date != null && !date.isBefore(current.minusDays(6)) && !date.isAfter(current)
        }.sortedBy { it.name }.map { LogFileSnapshot(it, it.length()) }
    }

    companion object {
        internal fun logDate(name: String): LocalDate? = if (name.matches(Regex("rwjs-\\d{4}-\\d{2}-\\d{2}\\.log")))
            runCatching { LocalDate.parse(name.removePrefix("rwjs-").removeSuffix(".log")) }.getOrNull() else null
    }
}

data class LogFileSnapshot(val file: File, val length: Long)
data class LogArchive(val file: File, val days: List<String>) : AutoCloseable {
    override fun close() { file.delete() }
}

object RuntimeLogs {
    const val MAX_RAW_BYTES = 128L * 1024 * 1024
    const val MAX_ZIP_BYTES = 64L * 1024 * 1024
    @Volatile private var stream: DailyLogOutputStream? = null

    @Synchronized fun initialize(directory: File): DailyLogOutputStream {
        return stream ?: DailyLogOutputStream(directory).also { stream = it }
    }

    fun recentFiles(): List<LogFileSnapshot> = stream?.recentFiles().orEmpty()

    /** A fixed-length, streaming snapshot: active logs may continue growing while this is compressed. */
    fun createArchive(): LogArchive = archive(recentFiles())

    internal fun archive(files: List<LogFileSnapshot>): LogArchive {
        check(files.isNotEmpty()) { "暂无可提交的日志，请重启应用后再试。" }
        check(files.sumOf { it.length } <= MAX_RAW_BYTES) { "最近七天日志超过 128 MiB，无法一次提交，请联系开发者。" }
        val target = File.createTempFile("feedback-", ".zip", files.first().file.parentFile)
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                files.forEach { snapshot ->
                    zip.putNextEntry(ZipEntry(snapshot.file.name))
                    snapshot.file.inputStream().buffered().use { source ->
                        var remaining = snapshot.length
                        val buffer = ByteArray(32 * 1024)
                        while (remaining > 0) {
                            val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            check(count >= 0) { "日志已变化，请重新提交。" }
                            zip.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                    zip.closeEntry()
                }
            }
            check(target.length() <= MAX_ZIP_BYTES) { "日志压缩包超过 64 MiB，请联系开发者。" }
            return LogArchive(target, files.map { it.file.name.removePrefix("rwjs-").removeSuffix(".log") })
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }
}
