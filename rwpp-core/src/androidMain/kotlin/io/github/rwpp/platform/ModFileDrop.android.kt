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

actual fun Modifier.modFileDropTarget(
    enabled: Boolean,
    onDragStarted: () -> Unit,
    onDragEnded: () -> Unit,
    onDragPointerInRoot: (Offset) -> Unit,
    onFilesDropped: (List<File>) -> Unit,
): Modifier = this
