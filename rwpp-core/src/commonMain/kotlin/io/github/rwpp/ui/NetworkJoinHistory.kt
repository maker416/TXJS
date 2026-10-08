/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.config.ConfigIO
import io.github.rwpp.logger

/** 多人直连地址历史（最多 [MAX_ENTRIES] 条，新的在前）。 */
internal object NetworkJoinHistory {
    const val MAX_ENTRIES = 12
    private const val CONFIG_GROUP = "io.github.rwpp.networkJoinHistory"
    private const val CONFIG_KEY = "addresses"

    fun load(configIO: ConfigIO): List<String> {
        val raw = runCatching { configIO.readSingleConfig(CONFIG_GROUP, CONFIG_KEY) }.getOrNull()
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .distinct().take(MAX_ENTRIES).toList()
    }

    fun record(configIO: ConfigIO, address: String): List<String> {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return load(configIO)
        val updated = (listOf(trimmed) + load(configIO).filter { it != trimmed }).take(MAX_ENTRIES)
        // 历史不是连接的前置条件：磁盘写入失败仍允许本次直连继续。
        runCatching { configIO.saveSingleConfig(CONFIG_GROUP, CONFIG_KEY, updated.joinToString("\n")) }
            .onFailure { logger.warn("Failed to save direct join history", it) }
        return updated
    }
}
