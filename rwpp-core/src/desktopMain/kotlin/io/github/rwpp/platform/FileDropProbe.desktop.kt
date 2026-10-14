/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.ExperimentalComposeUiApi
import io.github.rwpp.logger
import java.awt.datatransfer.DataFlavor
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.fileDropProbeTarget(
    enabled: Boolean,
    onReport: (FileDropProbeReport) -> Unit,
): Modifier {
    if (!enabled) return this
    return composed {
        val callback = remember(onReport) { onReport }
        val target = remember(callback) {
            object : DragAndDropTarget {
                override fun onStarted(event: DragAndDropEvent) {
                    val report = probeDragEvent(FileDropProbePhase.Enter, event)
                    logger.info("[FileDropProbe] enter\n${report.formatForDisplay()}")
                    callback(report)
                }

                override fun onEnded(event: DragAndDropEvent) {
                    val report = probeDragEvent(FileDropProbePhase.Exit, event)
                    logger.info("[FileDropProbe] exit\n${report.formatForDisplay()}")
                    callback(report)
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val report = probeDragEvent(FileDropProbePhase.Drop, event)
                    logger.info("[FileDropProbe] drop accepted=${report.files.isNotEmpty()}\n${report.formatForDisplay()}")
                    callback(report)
                    // 探测阶段：声明已处理，避免系统继续找别的 target
                    return report.files.isNotEmpty() || report.flavors.any { it.supported }
                }
            }
        }
        dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                val transferable = event.awtTransferable
                transferable.transferDataFlavors.isNotEmpty()
            },
            target = target,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun probeDragEvent(phase: FileDropProbePhase, event: DragAndDropEvent): FileDropProbeReport {
    val transferable = event.awtTransferable
    val flavors = transferable.transferDataFlavors.map { flavor ->
        val supported = transferable.isDataFlavorSupported(flavor)
        val preview = if (supported) readFlavorPreview(transferable, flavor) else null
        FileDropFlavorInfo(
            mimeType = flavor.mimeType,
            humanName = flavor.humanPresentableName,
            supported = supported,
            preview = preview,
        )
    }
    val files = readDroppedFiles(transferable)
    val notes = buildString {
        append("action=${event.action}")
        if (files.isEmpty() && flavors.none { it.supported }) {
            append("; no supported flavor with readable preview")
        }
    }
    return FileDropProbeReport(
        phase = phase,
        flavors = flavors,
        files = files,
        notes = notes,
    )
}

private fun readDroppedFiles(transferable: java.awt.datatransfer.Transferable): List<String> {
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    return runCatching {
        @Suppress("UNCHECKED_CAST")
        val list = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
        list.map { file ->
            buildString {
                append(file.absolutePath)
                append(" exists=").append(file.exists())
                append(" isFile=").append(file.isFile)
                append(" size=").append(if (file.isFile) file.length() else -1)
            }
        }
    }.getOrElse { listOf("javaFileListFlavor error: ${it.message}") }
}

private fun readFlavorPreview(
    transferable: java.awt.datatransfer.Transferable,
    flavor: DataFlavor,
): String? {
    return runCatching {
        when {
            flavor == DataFlavor.javaFileListFlavor -> {
                @Suppress("UNCHECKED_CAST")
                val list = transferable.getTransferData(flavor) as List<File>
                list.take(3).joinToString { it.absolutePath } +
                    if (list.size > 3) " …+${list.size - 3}" else ""
            }
            flavor.isFlavorTextType -> {
                val text = transferable.getTransferData(flavor)?.toString() ?: ""
                text.take(200).let { if (text.length > 200) "$it…" else it }
            }
            else -> {
                val data = transferable.getTransferData(flavor)
                when (data) {
                    null -> "null"
                    is ByteArray -> "bytes[${data.size}]"
                    else -> data.toString().take(120)
                }
            }
        }
    }.getOrElse { "read error: ${it::class.simpleName}: ${it.message}" }
}
