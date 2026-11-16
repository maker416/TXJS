/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.appKoin
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.game.map.*
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.logger
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.saveDir
import io.github.rwpp.widget.RWOutlinedTextColors
import io.github.rwpp.widget.UiLinearProgressIndicator
import io.github.rwpp.widget.v2.LazyColumnScrollbar
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** 文件大小与时间在 IO 扫描时捕获，组合和搜索不访问磁盘或引擎。 */
private data class ManagedSavedGame(
    val save: SavedGameFile,
    val modifiedAt: Long,
    val details: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SavedGamesManagementView(
    onLoadSavedGame: suspend (GameMap) -> Unit,
    onTabChange: (ModsMapsTab) -> Unit,
    root: File? = null,
    onBusyChange: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val fileLoad = rememberPageFileLoadState<List<ManagedSavedGame>>()
    var filter by remember { mutableStateOf("") }
    var oldestFirst by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<SavedGameFile?>(null) }
    var newName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SavedGameFile?>(null) }
    val listState = rememberLazyListState()

    suspend fun refresh() {
        fileLoad.load {
            (if (root == null) scanSavedGameFiles() else scanSavedGameFiles(root)).map { save ->
                val time = save.lastModifiedMillis
                ManagedSavedGame(save, time, "${formatReplayFileTime(time)} · ${formatSaveBytes(save.fileSizeBytes)}")
            }
        }
    }

    LaunchedEffect(root) { refresh() }
    // 开局或文件写入中不能切换标签、返回，避免销毁操作所属的协程。
    BackHandler(busy) {}
    SideEffect { onBusyChange(busy) }
    DisposableEffect(Unit) { onDispose { onBusyChange(false) } }

    fun runOperation(failureMessage: String? = null, operation: suspend () -> Unit) {
        if (busy || !fileLoad.isReady) return
        busy = true
        failure = null
        scope.launch {
            try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Saved game operation failed", e)
                failure = failureMessage ?: e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    val saves = remember(fileLoad.value, filter, oldestFirst) {
        val matching = fileLoad.value.orEmpty().filter {
            it.save.displayName().contains(filter.trim(), ignoreCase = true)
        }
        if (oldestFirst) matching.sortedBy { it.modifiedAt } else matching.sortedByDescending { it.modifiedAt }
    }

    Column(Modifier.fillMaxSize().testTag("savedGamesPage")) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 52.dp, top = 12.dp, bottom = 8.dp)) {
            ModsMapsSegmentedControl(
                selected = ModsMapsTab.Saves,
                onSelect = { if (!busy) onTabChange(it) },
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
            )
            Text(readI18n("saves.title"), style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp))
            Text(readI18n("saves.hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("saveSearch"),
                label = { Text(readI18n("saves.search")) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                colors = RWOutlinedTextColors,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                TextButton(
                    enabled = fileLoad.isReady && !busy,
                    onClick = { oldestFirst = !oldestFirst },
                    modifier = Modifier.testTag("saveSort"),
                ) { Text(readI18n(if (oldestFirst) "saves.oldestFirst" else "saves.newestFirst")) }
                TextButton(
                    enabled = !fileLoad.isLoading && !busy,
                    onClick = { scope.launch { refresh() } },
                    modifier = Modifier.testTag("saveRefresh"),
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Text(readI18n("maps.refresh"))
                }
                TextButton(
                    enabled = fileLoad.isReady && !busy,
                    onClick = {
                        appKoin.get<ExternalHandler>().openFileChooser { source ->
                            runOperation {
                                runInterruptible(Dispatchers.IO) { importSavedGameFile(source, root ?: File(saveDir)) }
                                refresh()
                            }
                        }
                    },
                    modifier = Modifier.testTag("saveImport"),
                ) { Text(readI18n("saves.import")) }
            }
        }
        if (busy) UiLinearProgressIndicator(Modifier.fillMaxWidth())
        (failure ?: fileLoad.failure?.message)?.let {
            Text(
                readI18n("common.failed") + ": " + it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("saveFailure"),
            )
        }
        when {
            !fileLoad.isReady -> PageFileLoading(
                state = fileLoad,
                pageTitle = readI18n("saves.title"),
                onRetry = { scope.launch { refresh() } },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            saves.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(readI18n(if (filter.isBlank()) "saves.empty" else "saves.emptyFiltered"), color = MaterialTheme.colorScheme.onSurface)
            }
            else -> LazyColumnScrollbar(listState, Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("saveList"),
                    contentPadding = PaddingValues(start = 16.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(saves, key = { it.save.file.absolutePath }) { entry ->
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .7f),
                            contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(entry.save.displayName(), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(entry.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (entry.save.engineName.startsWith("[INTERNAL-PATH]/")) {
                                    Text(readI18n("saves.internalStorage"), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            val message = readI18n("saves.loadFailed")
                                            runOperation(message) { onLoadSavedGame(entry.save.toGameMap(saves.indexOf(entry))) }
                                        },
                                        modifier = Modifier.testTag("saveLoad:${entry.save.engineName}"),
                                    ) { Text(readI18n("saves.load")) }
                                    IconButton(
                                        enabled = !busy,
                                        onClick = { newName = entry.save.displayName(); renameTarget = entry.save },
                                        modifier = Modifier.testTag("saveRename:${entry.save.engineName}"),
                                    ) { Icon(Icons.Default.Edit, readI18n("saves.rename")) }
                                    IconButton(
                                        enabled = !busy,
                                        onClick = { deleteTarget = entry.save },
                                        modifier = Modifier.testTag("saveDelete:${entry.save.engineName}"),
                                    ) { Icon(Icons.Default.Delete, readI18n("saves.delete")) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { save ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(readI18n("saves.rename")) },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                    label = { Text(readI18n("saves.name")) }, modifier = Modifier.testTag("saveName"),
                )
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(readI18n("mod.cancel")) } },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    renameTarget = null
                    runOperation {
                        withContext(Dispatchers.IO) { renameSavedGameFile(save, newName, root ?: save.file.parentFile) }
                        refresh()
                    }
                }, modifier = Modifier.testTag("saveRenameConfirm")) { Text(readI18n("saves.rename")) }
            },
        )
    }
    deleteTarget?.let { save ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(readI18n("saves.delete")) },
            text = { Text(readI18n("saves.deleteConfirm", I18nType.RWPP, save.displayName())) },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(readI18n("mod.cancel")) } },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    runOperation {
                        withContext(Dispatchers.IO) { deleteSavedGameFile(save, root ?: save.file.parentFile) }
                        refresh()
                    }
                }, modifier = Modifier.testTag("saveDeleteConfirm")) { Text(readI18n("saves.delete")) }
            },
        )
    }
}

private fun formatSaveBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${(bytes * 10 / (1024 * 1024)).toDouble() / 10} MB"
}
