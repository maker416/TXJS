/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/**
 * 轻量解析 `mod-info.txt`，对齐引擎 [com.corrodinggames.rts.gameFramework.utility.ae] 的关键能力：
 * UTF-8、`=` / `:` 分隔、`"""` 多行字符串、BOM 剥离。
 *
 * 仅提取 `[mod]` 段的 title / description / minVersion，不加载单位定义。
 */
object ModInfoParser {
    data class Metadata(
        val name: String,
        val description: String,
        val minVersion: String,
        /**
         * `[mod]` 段的 title 缺失或为空白时为 true（含 mod-info.txt 不存在、文件损坏等
         * 一切无法取得模组名的情况）。此时 [name] 回退为文件名。
         */
        val titleMissing: Boolean = false,
    )

    private val MOD_KEYS = setOf("title", "description", "minversion")

    fun parseFromRwmod(file: File): Metadata {
        val fallback = Metadata(file.nameWithoutExtension, "", "", titleMissing = true)
        if (!file.extension.equals("rwmod", ignoreCase = true)) {
            // 非 .rwmod 不由本函数检查，不标记错误
            return Metadata(file.nameWithoutExtension, "", "")
        }
        if (!file.exists() || file.length() == 0L) return fallback

        return runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { zipEntry ->
                    !zipEntry.isDirectory &&
                        zipEntry.name.replace('\\', '/').lowercase().endsWith("mod-info.txt")
                } ?: return@use fallback

                zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    parseIni(reader.readText(), file.nameWithoutExtension)
                }
            }
        }.getOrDefault(fallback)
    }

    /**
     * 按文件类型解析任意模组文件的元数据：
     * `.rwmod` 走 ZIP；文件夹递归查找其中的 `mod-info.txt`；
     * 其余类型（如 `.ini`）返回 null，表示不适用 title 检查。
     */
    fun parseFromModFile(file: File): Metadata? {
        return when {
            file.extension.equals("rwmod", ignoreCase = true) -> parseFromRwmod(file)
            file.isDirectory -> {
                val info = file.walk().firstOrNull {
                    it.isFile && it.name.equals("mod-info.txt", ignoreCase = true)
                } ?: return Metadata(file.name, "", "", titleMissing = true)
                runCatching {
                    parseIni(info.readText(Charsets.UTF_8), file.name)
                }.getOrElse { Metadata(file.name, "", "", titleMissing = true) }
            }
            else -> null
        }
    }

    fun parseIni(content: String, fallbackName: String): Metadata {
        val text = content.removePrefix("\uFEFF")
        var section = ""
        var title: String? = null
        var description: String? = null
        var minVersion: String? = null

        val lines = text.lineSequence().iterator()
        while (lines.hasNext()) {
            var raw = lines.next()
            if (raw.startsWith("?")) {
                raw = raw.substring(1)
            }
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
                continue
            }

            val sep = trimmed.indexOfFirst { it == '=' || it == ':' }
            if (sep < 0) continue

            val key = trimmed.substring(0, sep).trim().lowercase()
            if (section != "mod" || key !in MOD_KEYS) continue

            var value = trimmed.substring(sep + 1).trim()
            if (value.startsWith("\"\"\"")) {
                value = readTripleQuotedValue(value.removePrefix("\"\"\""), lines)
            }
            value = value.replace("\\n", "\n")

            when (key) {
                "title" -> title = value
                "description" -> description = value
                "minversion" -> minVersion = value
            }
        }

        return Metadata(
            name = title?.takeIf { it.isNotBlank() } ?: fallbackName,
            description = description ?: "",
            minVersion = minVersion ?: "",
            titleMissing = title.isNullOrBlank(),
        )
    }

    /**
     * [afterOpen] 是去掉行首 `"""` 后的剩余内容。
     * 支持同行闭合 `"""value"""` 与跨行 `"""\n...\n"""`。
     */
    private fun readTripleQuotedValue(
        afterOpen: String,
        lines: Iterator<String>,
    ): String {
        val closeOnSameLine = afterOpen.indexOf("\"\"\"")
        if (closeOnSameLine >= 0) {
            return afterOpen.substring(0, closeOnSameLine)
        }

        val buffer = StringBuilder()
        if (afterOpen.isNotEmpty()) {
            buffer.append(afterOpen)
        }

        while (lines.hasNext()) {
            val next = lines.next()
            val closeIdx = next.indexOf("\"\"\"")
            if (closeIdx >= 0) {
                val beforeClose = next.substring(0, closeIdx)
                // 单独一行的闭合 """ 不应追加多余换行
                if (beforeClose.isNotEmpty()) {
                    if (buffer.isNotEmpty()) {
                        buffer.append('\n')
                    }
                    buffer.append(beforeClose)
                }
                break
            }
            if (buffer.isNotEmpty()) {
                buffer.append('\n')
            }
            buffer.append(next)
        }
        return buffer.toString()
    }
}
