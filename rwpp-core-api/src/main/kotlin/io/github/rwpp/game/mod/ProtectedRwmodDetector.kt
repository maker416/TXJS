/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 识别 rwTool / ZipParallel 一类「加固」`.rwmod`：中央目录里大量条目写成
 * 压缩大小 `0xFFFFFFFF`、未压缩大小 `0`、假 CRC，且没有 ZIP64 extra。
 *
 * 只读中央目录，不解压内容。
 */
object ProtectedRwmodDetector {
    private const val ZIP64_SIZE_SENTINEL = 0xFFFFFFFFL
    private const val MIN_ENTRIES = 8
    private const val MIN_BROKEN_RATIO = 0.9

    fun isProtectedRwmod(file: File): Boolean {
        if (!file.isFile || !file.extension.equals("rwmod", ignoreCase = true)) return false
        if (file.length() < 64L) return false
        return runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.size < MIN_ENTRIES) return@use false
                val broken = entries.count { isBrokenProtectedEntry(it) }
                broken.toDouble() / entries.size >= MIN_BROKEN_RATIO
            }
        }.getOrDefault(false)
    }

    /**
     * 即将加载（启用）的加固模组显示名，供重载进度框提示。
     *
     * [enabledByFileName] 非空时以该表为准，并扫描 [searchDirs] 以覆盖尚未进入引擎列表的 `.rwmod`；
     * 为 null 时检查 [engineMods] 里当前启用的项，以及 [searchDirs] 中引擎尚未认识的新文件。
     */
    fun displayNamesOfEnabled(
        engineMods: List<Mod>,
        enabledByFileName: Map<String, Boolean>?,
        searchDirs: List<File>,
    ): List<String> {
        val enabledKeys = enabledByFileName
            ?.filterValues { it }
            ?.keys
            ?.map { it.lowercase(Locale.ROOT) }
            ?.toSet()
        val engineFileNames = engineMods
            .map { File(it.path).name.lowercase(Locale.ROOT) }
            .toSet()
        val seen = mutableSetOf<String>()
        val names = mutableListOf<String>()

        fun consider(file: File, displayName: String?) {
            val key = file.name.lowercase(Locale.ROOT)
            if (!seen.add(key)) return
            if (!isProtectedRwmod(file)) return
            val name = displayName?.takeIf { it.isNotBlank() }
                ?: ModInfoParser.parseFromRwmod(file).name
            names += name
        }

        for (mod in engineMods) {
            val file = File(mod.path)
            val enabled = enabledKeys?.contains(file.name.lowercase(Locale.ROOT)) ?: mod.isEnabled
            if (enabled) consider(file, mod.name)
        }

        for (dir in searchDirs) {
            dir.listFiles()?.forEach { file ->
                if (!file.isFile || !file.extension.equals("rwmod", ignoreCase = true)) return@forEach
                val key = file.name.lowercase(Locale.ROOT)
                val enabled = when {
                    enabledKeys != null -> key in enabledKeys
                    // 引擎已认识的文件以 isEnabled 为准，避免把已禁用项再扫进来
                    key in engineFileNames -> false
                    // 尚未进入引擎列表的新文件（进房同步落盘后的常见情况）
                    else -> true
                }
                if (enabled) consider(file, null)
            }
        }

        return names
    }

    private fun isBrokenProtectedEntry(entry: ZipEntry): Boolean {
        val compressedSize = entry.compressedSize
        if (compressedSize != ZIP64_SIZE_SENTINEL && compressedSize != -1L) return false
        if (entry.size != 0L) return false
        val crc = entry.crc
        if (crc !in 1L..255L) return false
        val extra = entry.extra ?: return true
        return !hasZip64Extra(extra)
    }

    private fun hasZip64Extra(extra: ByteArray): Boolean {
        var i = 0
        while (i + 4 <= extra.size) {
            val id = (extra[i].toInt() and 0xff) or ((extra[i + 1].toInt() and 0xff) shl 8)
            val size = (extra[i + 2].toInt() and 0xff) or ((extra[i + 3].toInt() and 0xff) shl 8)
            if (id == 1) return true
            if (size < 0) break
            i += 4 + size
        }
        return false
    }
}
