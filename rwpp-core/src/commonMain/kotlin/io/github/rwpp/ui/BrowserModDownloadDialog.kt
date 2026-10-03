/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.logger
import io.github.rwpp.modDir
import io.github.rwpp.net.browser.BrowserModDownload
import io.github.rwpp.net.browser.BrowserModFiles
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.platform.EmbeddedBrowserState
import io.github.rwpp.widget.BorderCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private enum class DownloadStage { Confirm, Downloading, Installing, Complete, Failed }

@Composable
internal fun BrowserModDownloadDialog(browser: EmbeddedBrowserState, directory: () -> File = { File(modDir) }) {
    browser.modDownload?.let { download -> key(download) { ModDownloadDialog(download, directory) { browser.modDownload = null } } }
}

@Composable
private fun ModDownloadDialog(download: BrowserModDownload, modDirectory: () -> File, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(DownloadStage.Confirm) }
    var received by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(download.totalBytes) }
    var installedName by remember { mutableStateOf(download.fileName) }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val lastProgress = remember { AtomicLong(0) }

    fun close() {
        if (stage == DownloadStage.Installing) return
        download.transfer.cancel()
        job?.cancel()
        onClose()
    }

    DisposableEffect(download) { onDispose { download.transfer.cancel(); job?.cancel() } }
    BackHandler(true, ::close)

    fun begin() {
        if (stage != DownloadStage.Confirm) return
        stage = DownloadStage.Downloading
        job = scope.launch {
            var partial: File? = null
            try {
                val directory = modDirectory()
                // 即使取消发生在 IO 返回到 Main 的窗口内，也必须保存引用以清理临时文件。
                val stagedFile = withContext(NonCancellable + Dispatchers.IO) {
                    BrowserModFiles.createPartial(directory).also { partial = it }
                }
                ensureActive()
                download.transfer.download(stagedFile) { bytes, expected ->
                    val now = System.nanoTime()
                    val previous = lastProgress.get()
                    if (now - previous >= 100_000_000L || expected != null && bytes == expected) {
                        lastProgress.set(now)
                        scope.launch { received = bytes; total = expected?.takeIf { it > 0 } }
                    }
                }
                ensureActive()
                stage = DownloadStage.Installing
                // 不触发引擎重载，用户在模组页启用；提交中禁止取消，避免已安装却被显示为取消。
                val installed = withContext(NonCancellable + Dispatchers.IO) {
                    BrowserModFiles.install(stagedFile, directory, download.fileName)
                }
                installedName = installed.name
                stage = DownloadStage.Complete
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = failure.message.orEmpty()
                stage = DownloadStage.Failed
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    partial?.let { file ->
                        // Windows 上 Chromium 取消后可能稍晚才释放文件句柄。
                        for (attempt in 0 until 20) {
                            if (!file.exists() || file.delete()) return@withContext
                            delay(100)
                        }
                        logger.warn("[BROWSER-MOD] Cannot remove partial download: ${file.absolutePath}")
                    }
                }
            }
        }
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BorderCard(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(readI18n(when (stage) {
                    DownloadStage.Confirm -> "browser.modInstallTitle"
                    DownloadStage.Downloading -> "browser.modDownloading"
                    DownloadStage.Installing -> "browser.modInstalling"
                    DownloadStage.Complete -> "browser.modInstalled"
                    DownloadStage.Failed -> "browser.modDownloadFailed"
                }), style = MaterialTheme.typography.titleLarge)
                Text(installedName, style = MaterialTheme.typography.bodyMedium)
                when (stage) {
                    DownloadStage.Confirm -> Text(readI18n("browser.modInstallConfirm"))
                    DownloadStage.Downloading -> {
                        val expected = total
                        if (expected != null && expected > 0) {
                            val percent = (received.toDouble() / expected).coerceIn(0.0, 1.0)
                            LinearProgressIndicator(progress = { percent.toFloat() }, modifier = Modifier.fillMaxWidth())
                            Text("${(percent * 100).toInt()}% · ${downloadSize(received)} / ${downloadSize(expected)}")
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(downloadSize(received))
                        }
                    }
                    DownloadStage.Installing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    DownloadStage.Complete -> Text(readI18n("browser.modInstalledHint"))
                    DownloadStage.Failed -> Text(error, color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (stage == DownloadStage.Confirm) {
                        TextButton(onClick = ::close) { Text(readI18n("common.cancel")) }
                        TextButton(onClick = ::begin) { Text(readI18n("browser.modDownloadInstall")) }
                    } else if (stage == DownloadStage.Downloading) {
                        TextButton(onClick = ::close) { Text(readI18n("common.cancel")) }
                    } else if (stage != DownloadStage.Installing) {
                        TextButton(onClick = ::close) { Text(readI18n("common.ok")) }
                    }
                }
            }
        }
    }
}

private fun downloadSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
