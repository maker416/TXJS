/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.ui.Modifier

/** 单次拖放探测结果（调试 / 分析 QQ 等来源的 DataFlavor）。 */
data class FileDropProbeReport(
    val phase: FileDropProbePhase,
    val flavors: List<FileDropFlavorInfo>,
    val files: List<String> = emptyList(),
    val notes: String? = null,
)

enum class FileDropProbePhase {
    Enter,
    Drop,
    Exit,
}

data class FileDropFlavorInfo(
    val mimeType: String,
    val humanName: String,
    val supported: Boolean,
    val preview: String?,
)

/**
 * 桌面端：注册 OS 文件拖放探测；其他平台为 no-op。
 * 当前仅用于解析与展示测试信息，不执行导入。
 */
expect fun Modifier.fileDropProbeTarget(
    enabled: Boolean,
    onReport: (FileDropProbeReport) -> Unit,
): Modifier

fun FileDropProbeReport.formatForDisplay(): String = buildString {
    appendLine("phase=$phase")
    notes?.let { appendLine("notes=$it") }
    if (files.isNotEmpty()) {
        appendLine("files (${files.size}):")
        files.forEach { appendLine("  • $it") }
    }
    appendLine("flavors (${flavors.size}):")
    flavors.forEach { f ->
        append("  • ${f.mimeType}")
        if (f.humanName.isNotBlank()) append(" (${f.humanName})")
        append(" supported=${f.supported}")
        f.preview?.let { append(" preview=$it") }
        appendLine()
    }
}
