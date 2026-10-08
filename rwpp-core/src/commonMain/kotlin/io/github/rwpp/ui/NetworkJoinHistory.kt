/*
 * Copyright 2023-2025 RWPP contributors
 */

package io.github.rwpp.ui

import io.github.rwpp.config.ConfigIO

/** 多人直连地址历史（最多 [MAX_ENTRIES] 条，新的在前）。 */
internal object NetworkJoinHistory {
    const val MAX_ENTRIES = 12
    private const val CONFIG_KEY = "networkJoinHistory"

    fun load(configIO: ConfigIO): List<String> {
        val raw = runCatching { configIO.getGameConfig<String?>(CONFIG_KEY) }.getOrNull()
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    fun record(configIO: ConfigIO, address: String): List<String> {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return load(configIO)
        val updated = (listOf(trimmed) + load(configIO).filter { it != trimmed }).take(MAX_ENTRIES)
        configIO.setGameConfig(CONFIG_KEY, updated.joinToString("\n"))
        return updated
    }
}
