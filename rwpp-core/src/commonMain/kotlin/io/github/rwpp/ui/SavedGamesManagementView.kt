/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rwpp.appKoin
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.game.map.*
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.logger
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.file_open
import io.github.rwpp.rwpp_core.generated.resources.history_rotate_ccw_clock_30
import io.github.rwpp.saveDir
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.RWOutlinedTextColors
import io.github.rwpp.widget.v2.LazyColumnScrollbar
import io.github.rwpp.widget.v2.ListIndicatorSettings
import io.github.rwpp.widget.v2.ScrollbarSelectionActionable
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

/** 文件大小与时间在 IO 扫描时捕获，组合和搜索不访问磁盘或引擎。 */
internal data class ManagedSavedGame(
    val save: SavedGameFile,
    val modifiedAt: Long,
    val formattedTime: String,
    val formattedSize: String,
    val isInternalStorage: Boolean,
)

@Composable
internal fun RowScope.SavesHeaderTools(
    filter: String,
    onFilterChange: (String) -> Unit,
    oldestFirst: Boolean,
    onToggleSort: () -> Unit,
    count: Int?,
    ready: Boolean,
    busy: Boolean,
    wide: Boolean,
    showCountBadge: Boolean,
) {
    val sortLabel = readI18n(if (oldestFirst) "saves.oldestFirst" else "saves.newestFirst")
    if (wide) {
        if (showCountBadge && count != null) {
            LocalContentCountBadge(
                text = readI18n("maps.count", I18nType.RWPP, count.toString()),
            )
        }
        LocalContentToggleChip(
            label = sortLabel,
            selected = oldestFirst,
            enabled = ready && !busy,
            onClick = onToggleSort,
            leadingPainter = painterResource(Res.drawable.history_rotate_ccw_clock_30),
            modifier = Modifier.testTag("saveSort"),
        )
        LocalContentSearchField(
            value = filter,
            onValueChange = onFilterChange,
            placeholder = readI18n("saves.search"),
            enabled = !busy,
            modifier = Modifier.widthIn(min = 148.dp, max = 240.dp),
            fieldModifier = Modifier.testTag("saveSearch"),
        )
    } else {
        LocalContentSearchField(
            value = filter,
            onValueChange = onFilterChange,
            placeholder = readI18n("saves.search"),
            enabled = !busy,
            modifier = Modifier.weight(1f),
            fieldModifier = Modifier.testTag("saveSearch"),
        )
        LocalContentToggleChip(
            label = sortLabel,
            selected = oldestFirst,
            enabled = ready && !busy,
            onClick = onToggleSort,
            leadingPainter = painterResource(Res.drawable.history_rotate_ccw_clock_30),
            modifier = Modifier.testTag("saveSort"),
        )
    }
}

@Composable
internal fun SavedGamesManagementView(
    onLoadSavedGame: suspend (GameMap) -> Unit,
    onTabChange: (ModsMapsTab) -> Unit,
    root: File? = null,
    onBusyChange: (Boolean) -> Unit = {},
    onExit: (() -> Unit)? = null,
) {
    val shellCache = LocalLocalContentSessionCache.current
    val scope = rememberCoroutineScope()
    val useCache = shellCache != null && root == null
    val fileLoad = rememberPageFileLoadState(initialValue = if (useCache) shellCache?.savedGames else null)
    var localFilter by remember { mutableStateOf("") }
    var localOldestFirst by remember { mutableStateOf(false) }
    val filter = shellCache?.savesFilter ?: localFilter
    val oldestFirst = shellCache?.savesOldestFirst ?: localOldestFirst
    fun setFilter(value: String) {
        if (shellCache != null) shellCache.savesFilter = value else localFilter = value
    }
    fun toggleSort() {
        if (shellCache != null) shellCache.savesOldestFirst = !shellCache.savesOldestFirst
        else localOldestFirst = !localOldestFirst
    }

    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<SavedGameFile?>(null) }
    var newName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SavedGameFile?>(null) }
    val listState = rememberLazyListState()

    suspend fun refresh() {
        val loaded = fileLoad.load {
            (if (root == null) scanSavedGameFiles() else scanSavedGameFiles(root)).map { save ->
                val time = save.lastModifiedMillis
                ManagedSavedGame(
                    save = save,
                    modifiedAt = time,
                    formattedTime = formatReplayFileTime(time).orEmpty(),
                    formattedSize = formatSaveBytes(save.fileSizeBytes),
                    isInternalStorage = save.engineName.startsWith("[INTERNAL-PATH]/"),
                )
            }
        }
        if (useCache && loaded != null) {
            shellCache?.savedGames = loaded
        }
    }

    LaunchedEffect(root) {
        if (fileLoad.value == null) refresh()
    }
    // 开局或文件写入中不能切换标签、返回，避免销毁操作所属的协程。
    BackHandler(busy) {}
    SideEffect {
        onBusyChange(busy)
        shellCache?.savesBusy = busy
        shellCache?.savesReady = fileLoad.isReady
    }
    DisposableEffect(Unit) {
        onDispose {
            onBusyChange(false)
            shellCache?.savesBusy = false
        }
    }

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
        val query = filter.trim()
        val matching = fileLoad.value.orEmpty().filter {
            query.isEmpty() || it.save.displayName().contains(query, ignoreCase = true)
        }
        if (oldestFirst) matching.sortedBy { it.modifiedAt } else matching.sortedByDescending { it.modifiedAt }
    }

    SideEffect {
        shellCache?.savesVisibleCount = if (fileLoad.isReady) saves.size else null
    }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("savedGamesPage")) {
        val availableWidth = maxWidth
        val wide = availableWidth >= LocalContentWideBreakpoint
        val shortViewport = maxHeight < LocalContentShortHeightBreakpoint
        val wideCard = availableWidth >= 520.dp
        val showFooterHint = availableWidth >= 740.dp

        Column(Modifier.fillMaxSize()) {
            // 嵌入 ModsAndMapsView 时顶栏由共享外壳常驻渲染，独立渲染（如单页测试）时复用同一套顶栏。
            if (shellCache == null) {
                LocalContentTopChrome(
                    wide = wide,
                    shortViewport = shortViewport,
                    navigation = { navModifier ->
                        ModsMapsSegmentedControl(
                            selected = ModsMapsTab.Saves,
                            onSelect = { if (!busy) onTabChange(it) },
                            enabled = !busy,
                            compact = shortViewport,
                            modifier = if (wide) {
                                navModifier.width(if (availableWidth < 700.dp) 224.dp else 264.dp)
                            } else {
                                navModifier
                            },
                        )
                    },
                    wideTools = {
                        SavesHeaderTools(
                            filter = filter,
                            onFilterChange = ::setFilter,
                            oldestFirst = oldestFirst,
                            onToggleSort = ::toggleSort,
                            count = if (fileLoad.isReady) saves.size else null,
                            ready = fileLoad.isReady,
                            busy = busy,
                            wide = true,
                            showCountBadge = availableWidth >= 780.dp,
                        )
                    },
                    narrowTools = {
                        SavesHeaderTools(
                            filter = filter,
                            onFilterChange = ::setFilter,
                            oldestFirst = oldestFirst,
                            onToggleSort = ::toggleSort,
                            count = if (fileLoad.isReady) saves.size else null,
                            ready = fileLoad.isReady,
                            busy = busy,
                            wide = false,
                            showCountBadge = false,
                        )
                    },
                    onClose = onExit?.let { exit -> { if (!busy) exit() } },
                    closeEnabled = !busy,
                    busy = busy,
                )
            }

            (failure ?: fileLoad.failure?.message)?.let { message ->
                SavedGamesErrorBanner(
                    text = readI18n("common.failed") + ": " + message,
                    onDismiss = if (failure != null) ({ failure = null }) else null,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            when {
                !fileLoad.isReady -> PageFileLoading(
                    state = fileLoad,
                    pageTitle = readI18n("saves.title"),
                    onRetry = { scope.launch { refresh() } },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                saves.isEmpty() -> {
                    val isFiltered = filter.isNotBlank()
                    LocalContentEmptyState(
                        message = readI18n(if (isFiltered) "saves.emptyFiltered" else "saves.empty"),
                        hint = if (isFiltered) null else readI18n("saves.hint"),
                        iconPainter = painterResource(Res.drawable.file_open),
                        actionLabel = if (isFiltered) readI18n("replays.resetFilters") else null,
                        onAction = if (isFiltered) ({ setFilter("") }) else null,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                else -> {
                    LazyColumnScrollbar(
                        listState = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        thickness = 4.dp,
                        padding = 3.dp,
                        alwaysShowScrollBar = false,
                        selectionActionable = ScrollbarSelectionActionable.WhenVisible,
                        showItemIndicator = ListIndicatorSettings.Disabled,
                    ) {
                        val horizontalPad = if (wide) 16.dp else 12.dp
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag("saveList"),
                            contentPadding = PaddingValues(
                                start = horizontalPad,
                                end = horizontalPad,
                                top = if (shortViewport) 8.dp else 12.dp,
                                bottom = 14.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(if (shortViewport) 8.dp else 10.dp),
                        ) {
                            items(saves, key = { it.save.file.absolutePath }) { entry ->
                                SavedGameCard(
                                    entry = entry,
                                    wideCard = wideCard,
                                    busy = busy,
                                    onLoad = {
                                        val message = readI18n("saves.loadFailed")
                                        runOperation(message) {
                                            onLoadSavedGame(entry.save.toGameMap(saves.indexOf(entry)))
                                        }
                                    },
                                    onRename = {
                                        newName = entry.save.displayName()
                                        renameTarget = entry.save
                                    },
                                    onDelete = { deleteTarget = entry.save },
                                )
                            }
                        }
                    }
                }
            }

            LocalContentBottomBar(shortViewport = shortViewport) {
                val buttonHeight = if (shortViewport) 40.dp else 44.dp
                LocalContentSecondaryButton(
                    text = readI18n("maps.refresh"),
                    leadingIcon = Icons.Default.Refresh,
                    enabled = !fileLoad.isLoading && !busy,
                    minHeight = buttonHeight,
                    onClick = { scope.launch { refresh() } },
                    modifier = Modifier.testTag("saveRefresh"),
                )
                if (showFooterHint) {
                    Text(
                        text = readI18n("saves.hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                LocalContentPrimaryButton(
                    text = readI18n("saves.import"),
                    leadingPainter = painterResource(Res.drawable.file_open),
                    enabled = fileLoad.isReady && !busy,
                    minHeight = buttonHeight,
                    onClick = {
                        appKoin.get<ExternalHandler>().openFileChooser { source ->
                            runOperation {
                                runInterruptible(Dispatchers.IO) {
                                    importSavedGameFile(source, root ?: File(saveDir))
                                }
                                refresh()
                            }
                        }
                    },
                    modifier = Modifier.testTag("saveImport"),
                )
            }
        }
    }

    renameTarget?.let { save ->
        SavedGameRenameDialog(
            value = newName,
            onValueChange = { newName = it },
            onDismiss = { renameTarget = null },
            onConfirm = {
                renameTarget = null
                runOperation {
                    withContext(Dispatchers.IO) {
                        renameSavedGameFile(save, newName, root ?: save.file.parentFile)
                    }
                    refresh()
                }
            },
        )
    }

    deleteTarget?.let { save ->
        SavedGameDeleteDialog(
            saveName = save.displayName(),
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                runOperation {
                    withContext(Dispatchers.IO) {
                        deleteSavedGameFile(save, root ?: save.file.parentFile)
                    }
                    refresh()
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedGameCard(
    entry: ManagedSavedGame,
    wideCard: Boolean,
    busy: Boolean,
    onLoad: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val engineName = entry.save.engineName
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = localContentCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, localContentCardBorderColor()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (wideCard) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SaveFileIconBadge(size = 42.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = entry.save.displayName(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = entry.formattedTime,
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        LocalContentMetaPill(text = entry.formattedSize)
                        if (entry.isInternalStorage) {
                            LocalContentMetaPill(
                                text = readI18n("saves.internalStorage"),
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LocalContentPrimaryButton(
                        text = readI18n("saves.load"),
                        leadingIcon = Icons.Default.PlayArrow,
                        enabled = !busy,
                        minHeight = 40.dp,
                        onClick = onLoad,
                        modifier = Modifier.testTag("saveLoad:$engineName"),
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .width(1.dp)
                            .height(22.dp)
                            .background(localContentHairlineColor()),
                    )
                    SaveCardIconAction(
                        icon = Icons.Default.Edit,
                        contentDescription = readI18n("saves.rename"),
                        destructive = false,
                        enabled = !busy,
                        size = 40.dp,
                        onClick = onRename,
                        modifier = Modifier.testTag("saveRename:$engineName"),
                    )
                    SaveCardIconAction(
                        icon = Icons.Default.Delete,
                        contentDescription = readI18n("saves.delete"),
                        destructive = true,
                        enabled = !busy,
                        size = 40.dp,
                        onClick = onDelete,
                        modifier = Modifier.testTag("saveDelete:$engineName"),
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SaveFileIconBadge(size = 40.dp)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = entry.save.displayName(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.formattedTime,
                                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            LocalContentMetaPill(text = entry.formattedSize)
                            if (entry.isInternalStorage) {
                                LocalContentMetaPill(
                                    text = readI18n("saves.internalStorage"),
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = localContentHairlineColor())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LocalContentPrimaryButton(
                        text = readI18n("saves.load"),
                        leadingIcon = Icons.Default.PlayArrow,
                        enabled = !busy,
                        minHeight = 42.dp,
                        onClick = onLoad,
                        modifier = Modifier.weight(1f).testTag("saveLoad:$engineName"),
                    )
                    SaveCardIconAction(
                        icon = Icons.Default.Edit,
                        contentDescription = readI18n("saves.rename"),
                        destructive = false,
                        enabled = !busy,
                        size = 42.dp,
                        onClick = onRename,
                        modifier = Modifier.testTag("saveRename:$engineName"),
                    )
                    SaveCardIconAction(
                        icon = Icons.Default.Delete,
                        contentDescription = readI18n("saves.delete"),
                        destructive = true,
                        enabled = !busy,
                        size = 42.dp,
                        onClick = onDelete,
                        modifier = Modifier.testTag("saveDelete:$engineName"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveFileIconBadge(size: Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(Res.drawable.file_open),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

@Composable
private fun SaveCardIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    destructive: Boolean,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (destructive) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f)
    }
    val border = if (destructive) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    }
    val tint = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) bg else bg.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, if (enabled) border else border.copy(alpha = 0.4f)),
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) tint else tint.copy(alpha = 0.4f),
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun SavedGamesErrorBanner(
    text: String,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = if (onDismiss != null) 4.dp else 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f).testTag("saveFailure"),
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedGameRenameDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AnimatedAlertDialog(visible = true, onDismissRequest = onDismiss) { dismiss ->
        BorderCard(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 440.dp)
                .padding(10.dp),
            shape = RoundedCornerShape(18.dp),
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = readI18n("saves.rename"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = RWOutlinedTextColors,
                    label = { Text(readI18n("saves.name")) },
                    modifier = Modifier.fillMaxWidth().testTag("saveName"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 42.dp)) {
                        Text(readI18n("mod.cancel"))
                    }
                    LocalContentPrimaryButton(
                        text = readI18n("saves.rename"),
                        enabled = value.isNotBlank(),
                        minHeight = 42.dp,
                        onClick = onConfirm,
                        modifier = Modifier.testTag("saveRenameConfirm"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedGameDeleteDialog(
    saveName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AnimatedAlertDialog(visible = true, onDismissRequest = onDismiss) { dismiss ->
        BorderCard(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 440.dp)
                .padding(10.dp),
            shape = RoundedCornerShape(18.dp),
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = readI18n("saves.delete"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = readI18n("saves.deleteConfirm", I18nType.RWPP, saveName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 42.dp)) {
                        Text(readI18n("mod.cancel"))
                    }
                    LocalContentPrimaryButton(
                        text = readI18n("saves.delete"),
                        leadingIcon = Icons.Default.Delete,
                        minHeight = 42.dp,
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        onClick = onConfirm,
                        modifier = Modifier.testTag("saveDeleteConfirm"),
                    )
                }
            }
        }
    }
}

private fun formatSaveBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${(bytes * 10 / (1024 * 1024)).toDouble() / 10} MB"
}
