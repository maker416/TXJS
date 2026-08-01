/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.map.MapType
import io.github.rwpp.game.save.GameSave
import io.github.rwpp.game.save.deleteGameSaveSafely
import io.github.rwpp.game.save.scanGameSaves
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.ExpandedCard
import io.github.rwpp.widget.v2.LazyColumnScrollbar
import io.github.rwpp.widget.v2.RWIconButton
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun formatSaveBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "${kb.roundToInt()} KB"
    val mb = kb / 1024.0
    return "${(mb * 10).roundToInt() / 10.0} MB"
}

private fun formatSaveTime(timeMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))

@Composable
private fun SaveMetadataPill(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = .14f),
        border = BorderStroke(1.dp, color.copy(alpha = .35f)),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * 存档管理界面：列出 `.rwsave` 存档，支持从存档开始单机、以存档开房与删除存档。
 */
@Composable
fun SavesViewDialog(
    onExit: () -> Unit,
    onOpenRoom: () -> Unit,
) {
    BackHandler(true, onExit)
    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("saves").broadcastIn()
        }
    }

    val game = koinInject<Game>()
    val saves = remember {
        SnapshotStateList<GameSave>().apply { addAll(scanGameSaves()) }
    }
    var filter by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<GameSave?>(null) }
    var pendingHost by remember { mutableStateOf<GameSave?>(null) }

    val filtered = saves.filter { it.displayName().contains(filter, ignoreCase = true) }

    fun refreshSaves() {
        saves.clear()
        saves.addAll(scanGameSaves())
    }

    fun deleteSave(save: GameSave) {
        if (deleteGameSaveSafely(save.file)) {
            saves.removeAll { it.file.absolutePath == save.file.absolutePath }
        } else {
            UI.showWarning(readI18n("saves.deleteFailed"))
        }
    }

    fun hostSave(save: GameSave, isPublic: Boolean, password: String?, useMods: Boolean) {
        // 先把存档解析为引擎地图项（MapType.SavedGame），失败则不要开房
        val saveMap = game.getAllMaps(true).firstOrNull {
            it.mapType == MapType.SavedGame && it.mapName == save.saveName
        }
        if (saveMap == null) {
            UI.showWarning(readI18n("saves.hostFailed"))
            return
        }
        game.hostStartWithPasswordAndMods(isPublic, password, useMods) {
            // 房间建立后再设置地图（Android 开房是异步的）
            game.gameRoom.selectedMap = saveMap
            onOpenRoom()
        }
    }

    pendingDelete?.let { save ->
        AnimatedAlertDialog(
            visible = true,
            onDismissRequest = { pendingDelete = null },
        ) { dismiss ->
            BorderCard(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(10.dp)
                    .widthIn(max = 480.dp),
                backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        readI18n("saves.deleteConfirmTitle"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        readI18n("saves.deleteConfirmMessage", I18nType.RWPP, save.displayName()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RWTextButton(readI18n("mod.cancel"), onClick = dismiss)
                        RWTextButton(
                            readI18n("mod.delete"),
                            leadingIcon = {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.size(24.dp))
                            },
                        ) {
                            deleteSave(save)
                            dismiss()
                        }
                    }
                }
            }
        }
    }

    pendingHost?.let { save ->
        HostSaveDialog(
            save = save,
            onDismissRequest = { pendingHost = null },
            onHost = { isPublic, password, useMods ->
                pendingHost = null
                hostSave(save, isPublic, password, useMods)
            },
        )
    }

    ExpandedCard {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏：标题 + 数量
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 14.dp, end = 46.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        readI18n("saves.title"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Box(modifier = Modifier.weight(1f))
                    SaveMetadataPill(
                        text = readI18n("saves.count", I18nType.RWPP, filtered.size.toString()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 搜索 + 刷新
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 46.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RWSingleOutlinedTextField(
                        readI18n("saves.search"),
                        filter,
                        modifier = Modifier.weight(1f),
                        leadingIcon = {
                            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.surfaceTint)
                        },
                        trailingIcon = if (filter.isNotBlank()) {
                            {
                                IconButton(
                                    onClick = { filter = "" },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.surfaceTint)
                                }
                            }
                        } else {
                            null
                        },
                    ) { filter = it }

                    RWIconButton(
                        Icons.Default.Refresh,
                        modifier = Modifier.offset(y = 4.dp),
                        size = 42.dp,
                    ) { refreshSaves() }
                }

                LargeDividingLine { 0.dp }

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (filter.isNotBlank()) {
                                readI18n("saves.emptyFiltered")
                            } else {
                                readI18n("saves.empty")
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val listState = rememberLazyListState()
                    LazyColumnScrollbar(
                        listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 4.dp),
                        thickness = 4.dp,
                        padding = 2.dp,
                        thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = .78f),
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 16.dp, end = 42.dp),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 12.dp),
                        ) {
                            items(
                                items = filtered,
                                key = { it.file.absolutePath },
                            ) { save ->
                                SaveItem(
                                    save = save,
                                    onPlay = { game.loadSaveGame(save.saveName) },
                                    onHost = { pendingHost = save },
                                    onDelete = { pendingDelete = save },
                                )
                            }
                        }
                    }
                }
            }

            ExitButton(onExit)
        }
    }
}

@Composable
private fun SaveItem(
    save: GameSave,
    onPlay: () -> Unit,
    onHost: () -> Unit,
    onDelete: () -> Unit,
) {
    val file = save.file
    val sizeText = remember(file.absolutePath, file.lastModified()) {
        formatSaveBytes(file.length().coerceAtLeast(0L))
    }
    val timeText = remember(file.absolutePath, file.lastModified()) {
        formatSaveTime(file.lastModified())
    }

    BorderCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(.7f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    save.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SaveMetadataPill(text = sizeText, color = MaterialTheme.colorScheme.secondary)
                    SaveMetadataPill(text = timeText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            RWTextButton(
                readI18n("saves.play"),
                leadingIcon = {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(22.dp))
                },
                onClick = onPlay,
            )
            RWTextButton(
                readI18n("saves.host"),
                leadingIcon = {
                    Icon(Icons.Default.Send, null, modifier = Modifier.size(22.dp))
                },
                onClick = onHost,
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = readI18n("mod.delete"),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * 「以存档开房」选项对话框：公开性、密码、是否启用 Mod。
 */
@Composable
private fun HostSaveDialog(
    save: GameSave,
    onDismissRequest: () -> Unit,
    onHost: (isPublic: Boolean, password: String?, useMods: Boolean) -> Unit,
) {
    var isPublic by remember { mutableStateOf(false) }
    var useMods by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    AnimatedAlertDialog(
        visible = true,
        onDismissRequest = onDismissRequest,
    ) { dismiss ->
        BorderCard(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(10.dp)
                .widthIn(max = 480.dp),
            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    readI18n("saves.hostTitle"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    save.displayName(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RWCheckbox(checked = isPublic, onCheckedChange = { isPublic = it })
                    Text(
                        readI18n("saves.hostPublic"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RWCheckbox(checked = useMods, onCheckedChange = { useMods = it })
                    Text(
                        readI18n("saves.hostUseMods"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                RWSingleOutlinedTextField(
                    readI18n("saves.hostPassword"),
                    password,
                    modifier = Modifier.fillMaxWidth(),
                ) { password = it }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RWTextButton(readI18n("mod.cancel"), onClick = dismiss)
                    RWTextButton(readI18n("saves.hostStart")) {
                        dismiss()
                        onHost(isPublic, password.ifBlank { null }, useMods)
                    }
                }
            }
        }
    }
}
