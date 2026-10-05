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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import io.github.rwpp.customMapDir
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.net.browser.BrowserModDownload
import io.github.rwpp.net.browser.BrowserResourceFiles
import io.github.rwpp.net.browser.BrowserResourceInstallKind
import io.github.rwpp.net.browser.BrowserResourceInstallError
import io.github.rwpp.net.browser.BrowserResourceInstallException
import io.github.rwpp.net.browser.BrowserResourceType
import io.github.rwpp.net.browser.browserResourceType
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

private enum class DownloadStage { ChooseZipType, ConfirmZipMod, Confirm, Downloading, Installing, Complete, Failed, Unsupported }

@Composable
internal fun BrowserModDownloadDialog(
    browser: EmbeddedBrowserState,
    onMapsInstalled: () -> Unit = {},
    mapDirectory: () -> File = { File(customMapDir) },
    directory: () -> File = { File(modDir) },
) {
    browser.modDownload?.let { download -> key(download) {
        ModDownloadDialog(download, directory, mapDirectory, onMapsInstalled) { browser.modDownload = null }
    } }
}

@Composable
private fun ModDownloadDialog(download: BrowserModDownload, modDirectory: () -> File, mapDirectory: () -> File,
    onMapsInstalled: () -> Unit, onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val type = remember(download) { browserResourceType(download.fileName) }
    var kind by remember { mutableStateOf(if (type == BrowserResourceType.Map) BrowserResourceInstallKind.Map else BrowserResourceInstallKind.Mod) }
    var stage by remember { mutableStateOf(if (type == BrowserResourceType.Zip) DownloadStage.ChooseZipType else DownloadStage.Confirm) }
    var received by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(download.totalBytes) }
    var installedName by remember { mutableStateOf(download.fileName) }
    var installedPath by remember { mutableStateOf("") }
    var installedMapCount by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val lastProgress = remember { AtomicLong(0) }
    val scrollState = rememberScrollState()
    LaunchedEffect(stage) { scrollState.scrollTo(0) }

    fun close() {
        if (stage == DownloadStage.Installing) return
        download.transfer.cancel()
        job?.cancel()
        onClose()
    }

    DisposableEffect(download) { onDispose { download.transfer.cancel(); job?.cancel() } }
    BackHandler(true, ::close)

    fun begin() {
        if (stage != DownloadStage.Confirm && stage != DownloadStage.ConfirmZipMod) return
        val installKind = kind
        stage = DownloadStage.Downloading
        job = scope.launch {
            var partial: File? = null
            try {
                val directory = if (installKind == BrowserResourceInstallKind.Mod) modDirectory() else mapDirectory()
                // 即使取消发生在 IO 返回到 Main 的窗口内，也必须保存引用以清理临时文件。
                val stagedFile = withContext(NonCancellable + Dispatchers.IO) {
                    BrowserResourceFiles.createPartial(directory).also { partial = it }
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
                    BrowserResourceFiles.install(stagedFile, directory, download.fileName, installKind)
                }
                installedName = installed.file.name
                installedPath = installed.file.absolutePath
                installedMapCount = installed.mapCount
                stage = DownloadStage.Complete
                if (installKind != BrowserResourceInstallKind.Mod) {
                    runCatching(onMapsInstalled).onFailure { logger.warn("[BROWSER-RESOURCE] Cannot refresh map list", it) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is BrowserResourceInstallException && failure.reason == BrowserResourceInstallError.ModCollection) {
                    stage = DownloadStage.Unsupported
                } else {
                    error = if (failure is BrowserResourceInstallException) readI18n("browser.installError${failure.reason.name}")
                        else failure.message.orEmpty()
                    stage = DownloadStage.Failed
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    partial?.let { file ->
                        // Windows 上 Chromium 取消后可能稍晚才释放文件句柄。
                        for (attempt in 0 until 20) {
                            if (!file.exists() || file.delete()) return@withContext
                            delay(100)
                        }
                        logger.warn("[BROWSER-RESOURCE] Cannot remove partial download: ${file.absolutePath}")
                    }
                }
            }
        }
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BorderCard(Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(readI18n(when (stage) {
                    DownloadStage.ChooseZipType -> "browser.zipTypeTitle"
                    DownloadStage.ConfirmZipMod -> "browser.zipModTitle"
                    DownloadStage.Unsupported -> "browser.modCollectionUnsupportedTitle"
                    DownloadStage.Confirm -> if (kind == BrowserResourceInstallKind.Mod) "browser.modInstallTitle" else "browser.mapInstallTitle"
                    DownloadStage.Downloading -> if (kind == BrowserResourceInstallKind.Mod) "browser.modDownloading" else "browser.mapDownloading"
                    DownloadStage.Installing -> if (kind == BrowserResourceInstallKind.Mod) "browser.modInstalling" else "browser.mapInstalling"
                    DownloadStage.Complete -> if (kind == BrowserResourceInstallKind.Mod) "browser.modInstalled" else "browser.mapInstalled"
                    DownloadStage.Failed -> if (kind == BrowserResourceInstallKind.Mod) "browser.modDownloadFailed" else "browser.mapDownloadFailed"
                }), style = MaterialTheme.typography.titleLarge)
                Text(installedName, style = MaterialTheme.typography.bodyMedium)
                when (stage) {
                    DownloadStage.ChooseZipType -> {
                        Text(readI18n("browser.zipTypeHint"))
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            kind = BrowserResourceInstallKind.MapPack
                            stage = DownloadStage.Confirm
                        }) { Text(readI18n("browser.zipMapPack")) }
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = { stage = DownloadStage.ConfirmZipMod }) {
                            Text(readI18n("browser.zipMod"))
                        }
                    }
                    DownloadStage.ConfirmZipMod -> {
                        Text(readI18n("browser.zipModHint"))
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = ::begin) { Text(readI18n("browser.zipCompleteMod")) }
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            download.transfer.cancel()
                            stage = DownloadStage.Unsupported
                        }) { Text(readI18n("browser.zipModCollection")) }
                    }
                    DownloadStage.Unsupported -> Text(readI18n("browser.modCollectionUnsupportedHint"))
                    DownloadStage.Confirm -> Text(readI18n(when (kind) {
                        BrowserResourceInstallKind.Mod -> "browser.modInstallConfirm"
                        BrowserResourceInstallKind.Map -> "browser.mapInstallConfirm"
                        BrowserResourceInstallKind.MapPack -> "browser.mapPackInstallConfirm"
                    }))
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
                    DownloadStage.Complete -> {
                        Text(readI18n(when (kind) {
                            BrowserResourceInstallKind.Mod -> "browser.modInstalledHint"
                            BrowserResourceInstallKind.Map -> "browser.mapInstalledHint"
                            BrowserResourceInstallKind.MapPack -> "browser.mapPackInstalledHint"
                        }, I18nType.RWPP, installedMapCount.toString()))
                        Text(installedPath, style = MaterialTheme.typography.bodySmall)
                    }
                    DownloadStage.Failed -> Text(error, color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (stage == DownloadStage.Confirm) {
                        TextButton(onClick = ::close) { Text(readI18n("common.cancel")) }
                        TextButton(onClick = ::begin) { Text(readI18n(if (kind == BrowserResourceInstallKind.MapPack)
                            "browser.mapPackDownloadInstall" else "browser.modDownloadInstall")) }
                    } else if (stage in setOf(DownloadStage.Downloading, DownloadStage.ChooseZipType, DownloadStage.ConfirmZipMod)) {
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
