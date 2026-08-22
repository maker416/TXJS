/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.map

import io.github.rwpp.replayDir
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 磁盘上回放文件（仅 `.replay`）。
 *
 * [name] 含扩展名，与原版 `getGameSaves` 条目一致，供引擎 `loadReplay` / `bY.b` 使用。
 */
data class ReplayFile(
    val file: File,
) {
    val name: String get() = file.name

    fun displayName(): String = file.nameWithoutExtension

    fun toReplay(id: Int): Replay = object : Replay {
        override val id: Int = id
        override val name: String = this@ReplayFile.name
        override fun displayName(): String = this@ReplayFile.displayName()
        override val lastModifiedMillis: Long = file.lastModified()
        override val fileSizeBytes: Long = file.length()
    }
}

/**
 * 原版回放文件名拆出的展示字段。
 * 例：Crossing Large (10p) v1.15，录制于 2026-08-21 21:50:37。
 */
data class ReplayLabel(
    val title: String,
    val playerCount: Int? = null,
    val version: String? = null,
    val recordedAt: String? = null,
    val recordedAtMillis: Long? = null,
)

private val replayNameRegex =
    Regex("""^(.+?)\s+\((\d+)p\)\s+\[([^]]+)]\s+\((.+)\)$""")
private val replayDateRegex =
    Regex("""^(\d{1,2})\s+(\S+)\s+(\d{4})\s+(\d{1,2})[.:](\d{2})[.:](\d{2})$""")

private val englishMonths = mapOf(
    "january" to 1, "jan" to 1,
    "february" to 2, "feb" to 2,
    "march" to 3, "mar" to 3,
    "april" to 4, "apr" to 4,
    "may" to 5,
    "june" to 6, "jun" to 6,
    "july" to 7, "jul" to 7,
    "august" to 8, "aug" to 8,
    "september" to 9, "sep" to 9, "sept" to 9,
    "october" to 10, "oct" to 10,
    "november" to 11, "nov" to 11,
    "december" to 12, "dec" to 12,
)

/**
 * 把原版回放显示名拆成标题 / 人数 / 版本 / 时间，解析失败时 [ReplayLabel.title] 保留原文。
 */
fun parseReplayLabel(displayName: String): ReplayLabel {
    val trimmed = displayName.trim().removePrefix("[replay]").trim()
    val match = replayNameRegex.matchEntire(trimmed)
        ?: return ReplayLabel(title = displayName)
    val title = match.groupValues[1].trim()
    val players = match.groupValues[2].toIntOrNull()
    val version = match.groupValues[3].trim()
    val rawTime = match.groupValues[4].trim()
    val formatted = formatReplayTimestamp(rawTime)
    val recordedAt = formatted ?: rawTime
    return ReplayLabel(
        title = title.ifBlank { displayName },
        playerCount = players,
        version = version.ifBlank { null },
        recordedAt = recordedAt.ifBlank { null },
        recordedAtMillis = formatted?.let(::parseIsoReplayMillis),
    )
}

fun formatReplayFileTime(millis: Long): String? {
    if (millis <= 0L) return null
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
}

fun replayDayKey(millis: Long): String? {
    if (millis <= 0L) return null
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}

internal fun parseIsoReplayMillis(raw: String): Long? {
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(raw)?.time
    }.getOrNull()
}

internal fun formatReplayTimestamp(raw: String): String? {
    val match = replayDateRegex.matchEntire(raw.trim()) ?: return null
    val day = match.groupValues[1].toIntOrNull() ?: return null
    val month = parseReplayMonth(match.groupValues[2]) ?: return null
    val year = match.groupValues[3].toIntOrNull() ?: return null
    val hour = match.groupValues[4].toIntOrNull() ?: return null
    val minute = match.groupValues[5].toIntOrNull() ?: return null
    val second = match.groupValues[6].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
        return null
    }
    return "%04d-%02d-%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second)
}

private fun parseReplayMonth(token: String): Int? {
    val trimmed = token.trim()
    val chinese = Regex("""^(\d{1,2})月$""").matchEntire(trimmed)
    if (chinese != null) return chinese.groupValues[1].toIntOrNull()
    return englishMonths[trimmed.lowercase()]
}

/**
 * 扫描 [replayDir] 下的回放文件；只收 `.replay`，与原版列表一样不递归子目录。
 * 默认按文件修改时间新到旧，便于打开页先看到最近一局。
 */
fun scanReplayFiles(root: File = File(replayDir)): List<ReplayFile> {
    if (!root.exists() || !root.isDirectory) return emptyList()
    return root.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && it.extension.equals("replay", ignoreCase = true) }
        ?.map { ReplayFile(it) }
        ?.sortedWith(
            compareByDescending<ReplayFile> { it.file.lastModified() }
                .thenBy { it.displayName().lowercase() },
        )
        ?.toList()
        ?: emptyList()
}

fun isReplayImportFile(file: File): Boolean {
    val ext = file.extension
    return ext.equals("replay", ignoreCase = true) || ext.equals("reply", ignoreCase = true)
}

/**
 * 导入落盘文件名：`.reply` 误后缀改成 `.replay`，其余保持原名。
 */
fun replayImportTargetName(fileName: String): String {
    val trimmed = fileName.trim()
    return if (trimmed.endsWith(".reply", ignoreCase = true)) {
        trimmed.dropLast(6) + ".replay"
    } else {
        trimmed
    }
}

/**
 * 回放导入目标路径；扩展名不是 `.replay` / `.reply` 时返回 null。
 */
fun replayImportDestination(source: File, destDir: File = File(replayDir)): File? {
    if (!isReplayImportFile(source)) return null
    destDir.mkdirs()
    return File(destDir, replayImportTargetName(source.name))
}
