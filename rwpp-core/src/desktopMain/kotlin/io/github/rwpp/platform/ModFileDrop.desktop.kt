@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draganddrop.positionInRoot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import java.awt.datatransfer.DataFlavor
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.modFileDropTarget(
    enabled: Boolean,
    onDragStarted: () -> Unit,
    onDragEnded: () -> Unit,
    onDragPointerInRoot: (Offset) -> Unit,
    onFilesDropped: (List<File>) -> Unit,
): Modifier {
    return composed {
        val accepting = rememberUpdatedState(enabled)
        val started = rememberUpdatedState(onDragStarted)
        val ended = rememberUpdatedState(onDragEnded)
        val pointer = rememberUpdatedState(onDragPointerInRoot)
        val dropped = rememberUpdatedState(onFilesDropped)
        var dragActive by remember { mutableStateOf(false) }

        fun endDrag() {
            if (dragActive) {
                dragActive = false
                ended.value()
            }
        }

        // 原生拖放不经过 pointerInput；禁用或移除目标时必须主动结束会话。
        DisposableEffect(enabled) {
            onDispose { endDrag() }
        }

        fun emitPointer(event: DragAndDropEvent) {
            if (!accepting.value || !dragActive) return
            val pos = dragEventPositionInRoot(event)
            if (pos != null) {
                pointer.value(pos)
            }
        }

        val target = remember {
            object : DragAndDropTarget {
                override fun onStarted(event: DragAndDropEvent) {
                    if (!accepting.value) return
                    dragActive = true
                    started.value()
                    emitPointer(event)
                }

                override fun onEntered(event: DragAndDropEvent) {
                    emitPointer(event)
                }

                override fun onMoved(event: DragAndDropEvent) {
                    emitPointer(event)
                }

                override fun onEnded(event: DragAndDropEvent) {
                    endDrag()
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    // 会话开始后页面仍可能退出/被覆盖，迟到的 drop 不能导入文件。
                    if (!accepting.value || !dragActive) return false
                    try {
                        val files = readDroppedFiles(event.awtTransferable)
                        if (files.isNotEmpty()) {
                            dropped.value(files)
                        }
                        return files.isNotEmpty()
                    } finally {
                        endDrag()
                    }
                }
            }
        }
        dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                accepting.value && event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            },
            target = target,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun dragEventPositionInRoot(event: DragAndDropEvent): Offset? {
    return runCatching { event.positionInRoot }.getOrNull()
}

private fun readDroppedFiles(transferable: java.awt.datatransfer.Transferable): List<File> {
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    return runCatching {
        @Suppress("UNCHECKED_CAST")
        transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
    }.getOrElse { emptyList() }
}
