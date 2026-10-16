/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import java.io.File

data class RwmodDropSelection(
    /** 应交给 importModFile 的文件；无 .rwmod 时为列表首项（触发格式提示）。 */
    val importCandidate: File?,
    val rwmodCount: Int,
    val totalFileCount: Int,
)

fun analyzeRwmodFileDrop(files: List<File>): RwmodDropSelection {
    if (files.isEmpty()) {
        return RwmodDropSelection(importCandidate = null, rwmodCount = 0, totalFileCount = 0)
    }
    val rwmods = files.filter { it.extension.equals("rwmod", ignoreCase = true) }
    return RwmodDropSelection(
        importCandidate = rwmods.firstOrNull() ?: files.first(),
        rwmodCount = rwmods.size,
        totalFileCount = files.size,
    )
}

/** 桌面端 OS 文件拖放；其他平台 no-op。 */
expect fun Modifier.modFileDropTarget(
    enabled: Boolean,
    onDragStarted: () -> Unit,
    onDragEnded: () -> Unit,
    onDragPointerInRoot: (Offset) -> Unit,
    onFilesDropped: (List<File>) -> Unit,
): Modifier
