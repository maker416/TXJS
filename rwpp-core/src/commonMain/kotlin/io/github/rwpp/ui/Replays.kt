/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.external.FileChooseProgress
import io.github.rwpp.game.Game
import io.github.rwpp.game.map.ReplayBrowseFacets
import io.github.rwpp.game.map.ReplayBrowseItem
import io.github.rwpp.game.map.ReplayBrowseQuery
import io.github.rwpp.game.map.ReplayDateRange
import io.github.rwpp.game.map.ReplayFile
import io.github.rwpp.game.map.ReplayGroupBy
import io.github.rwpp.game.map.ReplaySectionKey
import io.github.rwpp.game.map.ReplaySort
import io.github.rwpp.game.map.browseReplays
import io.github.rwpp.game.map.formatReplayFileTime
import io.github.rwpp.game.map.replayBrowseFacets
import io.github.rwpp.game.map.replayImportDestination
import io.github.rwpp.game.map.toReplayBrowseItem
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.io.copyToWithProgress
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.file_open
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.LazyColumnScrollbar
import io.github.rwpp.widget.v2.RWIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ReplaysViewDialog(
    onExit: () -> Unit
) {
    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("replays").broadcastIn()
        }
    }

    val game = koinInject<Game>()
    val configIO = koinInject<ConfigIO>()
    val scope = rememberCoroutineScope()
    val compact = LocalWindowManager.current == WindowManager.Small
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ReplaySort.TimeNewest) }
    var groupBy by rememberSaveable { mutableStateOf(ReplayGroupBy.None) }
    var dateRange by rememberSaveable { mutableStateOf(ReplayDateRange.All) }
    var mapTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var playerCount by rememberSaveable { mutableStateOf<Int?>(null) }
    var unknownPlayers by rememberSaveable { mutableStateOf(false) }
    var version by rememberSaveable { mutableStateOf<String?>(null) }
    var unknownVersion by rememberSaveable { mutableStateOf(false) }
    var collapsedGroups by remember { mutableStateOf(setOf<String>()) }
    var allReplays by remember { mutableStateOf(game.getAllReplays()) }
    var importProgress by remember { mutableStateOf<ReplayImportProgress?>(null) }
    var pendingOverwrite by remember { mutableStateOf<File?>(null) }
    var suppressPrepareProgress by remember { mutableStateOf(false) }
    val browseItems = remember(allReplays) { allReplays.map(::toReplayBrowseItem) }
    val facets = remember(browseItems) { replayBrowseFacets(browseItems) }
    val query = ReplayBrowseQuery(
        text = filter,
        sort = sort,
        groupBy = groupBy,
        dateRange = dateRange,
        mapTitle = mapTitle,
        playerCount = playerCount,
        unknownPlayers = unknownPlayers,
        version = version,
        unknownVersion = unknownVersion,
    )
    val sections = remember(browseItems, query) { browseReplays(browseItems, query) }
    val visibleCount = remember(sections) { sections.sumOf { it.items.size } }
    val recordingEnabled = remember {
        runCatching { configIO.getGameConfig<Boolean>("saveMultiplayerReplays") }.getOrDefault(true)
    }

    fun refresh() {
        allReplays = game.getAllReplays()
    }

    fun updateFileChooseProgress(progress: FileChooseProgress) {
        if (suppressPrepareProgress || pendingOverwrite != null) return
        if (importProgress?.stage == ReplayImportStage.Importing) return
        importProgress = ReplayImportProgress(
            stage = ReplayImportStage.Preparing,
            fileName = progress.fileName,
            copiedBytes = progress.copiedBytes,
            totalBytes = progress.totalBytes,
        )
    }

    fun importReplayFile(file: File, overwrite: Boolean = false) {
        if (importProgress?.stage == ReplayImportStage.Importing) return
        // 先不清 importProgress：让 Preparing 进度保持显示，直到协程内切换为 Importing，避免弹窗闪烁；
        // suppress 仅用于挡住文件选择器 onProgress 的迟到回调。
        suppressPrepareProgress = true

        val target = replayImportDestination(file)
        if (target == null) {
            suppressPrepareProgress = false
            importProgress = null
            UI.showWarning(readI18n("replays.importInvalid"))
            return
        }

        if (!overwrite && target.exists() && target.canonicalFile != file.canonicalFile) {
            pendingOverwrite = file
            return
        }

        scope.launch {
            val imported = try {
                importProgress = ReplayImportProgress(
                    stage = ReplayImportStage.Importing,
                    fileName = file.name,
                    totalBytes = file.length().takeIf { it > 0 },
                )
                if (file.canonicalFile != target.canonicalFile) {
                    var lastProgressUpdate = 0L
                    withContext(Dispatchers.IO) {
                        file.copyToWithProgress(target, overwrite = overwrite) { copiedBytes, totalBytes ->
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate >= 100L || copiedBytes == totalBytes) {
                                lastProgressUpdate = now
                                withContext(Dispatchers.Main) {
                                    importProgress = ReplayImportProgress(
                                        stage = ReplayImportStage.Importing,
                                        fileName = file.name,
                                        copiedBytes = copiedBytes,
                                        totalBytes = totalBytes.takeIf { it > 0 },
                                    )
                                }
                            }
                        }
                    }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: kotlin.io.FileAlreadyExistsException) {
                // overwrite=true 时仅当目标删除失败才会抛此异常，按导入失败处理
                if (!overwrite) {
                    pendingOverwrite = file
                } else {
                    UI.showWarning(readI18n("replays.importFailed"))
                }
                false
            } catch (_: IOException) {
                UI.showWarning(readI18n("replays.importFailed"))
                false
            } finally {
                importProgress = null
                if (pendingOverwrite == null) {
                    suppressPrepareProgress = false
                }
            }
            // refresh 与成功提示不放在 try 内，避免其中的异常被误报为「导入失败」
            if (imported) {
                refresh()
                UI.showWarning(
                    readI18n("replays.importSuccess", I18nType.RWPP, ReplayFile(target).displayName()),
                )
            }
        }
    }

    fun resetFilters() {
        filter = ""
        dateRange = ReplayDateRange.All
        mapTitle = null
        playerCount = null
        unknownPlayers = false
        version = null
        unknownVersion = false
    }

    LaunchedEffect(groupBy) {
        collapsedGroups = emptySet()
    }

    BackHandler(true) {
        if (showFilters) {
            showFilters = false
        } else {
            onExit()
        }
    }

    ReplayFilterDialog(
        visible = showFilters,
        compact = compact,
        filter = filter,
        sort = sort,
        groupBy = groupBy,
        dateRange = dateRange,
        mapTitle = mapTitle,
        playerCount = playerCount,
        unknownPlayers = unknownPlayers,
        version = version,
        unknownVersion = unknownVersion,
        facets = facets,
        showReset = query.hasNarrowingFilters(),
        onFilterChange = { filter = it },
        onSortChange = { sort = it },
        onGroupChange = { groupBy = it },
        onDateRangeChange = { dateRange = it },
        onMapChange = { mapTitle = it },
        onPlayersChange = { count, unknown ->
            playerCount = count
            unknownPlayers = unknown
        },
        onVersionChange = { value, unknown ->
            version = value
            unknownVersion = unknown
        },
        onReset = { resetFilters() },
        onDismiss = { showFilters = false },
    )

    ReplayImportProgressDialog(if (pendingOverwrite == null) importProgress else null)

    pendingOverwrite?.let { file ->
        val displayName = ReplayFile(replayImportDestination(file) ?: file).displayName()
        AnimatedAlertDialog(
            visible = true,
            onDismissRequest = {
                pendingOverwrite = null
                importProgress = null
                suppressPrepareProgress = false
            },
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
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(42.dp),
                    )
                    Text(
                        readI18n("replays.importExistsTitle"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        readI18n("replays.importExistsMessage", I18nType.RWPP, displayName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RWTextButton(readI18n("mod.cancel")) {
                            pendingOverwrite = null
                            importProgress = null
                            suppressPrepareProgress = false
                            dismiss()
                        }
                        RWTextButton(readI18n("replays.importOverwrite")) {
                            pendingOverwrite = null
                            dismiss()
                            importReplayFile(file, overwrite = true)
                        }
                    }
                }
            }
        }
    }

    BorderCard(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ExitButton(onExit)
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().scaleFit(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        readI18n("replays.title"),
                        style = MaterialTheme.typography.headlineLarge
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = query.hasNarrowingFilters() || groupBy != ReplayGroupBy.None,
                        onClick = { showFilters = true },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        label = { Text(readI18n("replays.filter")) },
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    ReplayCountPill(visibleCount)

                    RWIconButton(
                        painterResource(Res.drawable.file_open),
                        modifier = Modifier.padding(top = 4.dp),
                        size = 44.dp,
                    ) {
                        if (importProgress != null || pendingOverwrite != null) return@RWIconButton
                        suppressPrepareProgress = false
                        appKoin.get<ExternalHandler>().openFileChooser(
                            onProgress = { updateFileChooseProgress(it) },
                        ) { file -> importReplayFile(file) }
                    }

                    RWIconButton(
                        Icons.Default.Refresh,
                        modifier = Modifier.padding(top = 4.dp),
                        size = 44.dp,
                    ) {
                        refresh()
                    }
                }

                if (!recordingEnabled) {
                    Text(
                        readI18n("replays.recordingOff"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                LargeDividingLine { 0.dp }

                if (sections.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (query.hasNarrowingFilters() || allReplays.isNotEmpty()) {
                                readI18n("replays.emptyFiltered")
                            } else {
                                readI18n("replays.empty")
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    val state = rememberLazyListState()
                    LazyColumnScrollbar(
                        listState = state,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        LazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            sections.forEach { section ->
                                val grouped = groupBy != ReplayGroupBy.None
                                if (grouped) {
                                    stickyHeader(key = "header-${section.id}") {
                                        ReplayGroupHeader(
                                            title = replaySectionTitle(section.key),
                                            count = section.items.size,
                                            expanded = section.id !in collapsedGroups,
                                            onToggle = {
                                                collapsedGroups = if (section.id in collapsedGroups) {
                                                    collapsedGroups - section.id
                                                } else {
                                                    collapsedGroups + section.id
                                                }
                                            },
                                        )
                                    }
                                }
                                if (!grouped || section.id !in collapsedGroups) {
                                    items(
                                        section.items,
                                        key = { "${section.id}-${it.replay.id}" },
                                    ) { item ->
                                        ReplayListItem(
                                            item = item,
                                            selectedMap = mapTitle,
                                            selectedPlayerCount = playerCount,
                                            unknownPlayers = unknownPlayers,
                                            selectedVersion = version,
                                            unknownVersion = unknownVersion,
                                            onMapClick = { title ->
                                                mapTitle = if (mapTitle?.equals(title, ignoreCase = true) == true) {
                                                    null
                                                } else {
                                                    title
                                                }
                                            },
                                            onPlayersClick = { count ->
                                                if (count == null) {
                                                    unknownPlayers = !unknownPlayers
                                                    if (unknownPlayers) playerCount = null
                                                } else if (playerCount == count && !unknownPlayers) {
                                                    playerCount = null
                                                } else {
                                                    playerCount = count
                                                    unknownPlayers = false
                                                }
                                            },
                                            onVersionClick = { value ->
                                                if (value == null) {
                                                    unknownVersion = !unknownVersion
                                                    if (unknownVersion) version = null
                                                } else if (version == value && !unknownVersion) {
                                                    version = null
                                                } else {
                                                    version = value
                                                    unknownVersion = false
                                                }
                                            },
                                            onClick = { game.watchReplay(item.replay) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplayFilterDialog(
    visible: Boolean,
    compact: Boolean,
    filter: String,
    sort: ReplaySort,
    groupBy: ReplayGroupBy,
    dateRange: ReplayDateRange,
    mapTitle: String?,
    playerCount: Int?,
    unknownPlayers: Boolean,
    version: String?,
    unknownVersion: Boolean,
    facets: ReplayBrowseFacets,
    showReset: Boolean,
    onFilterChange: (String) -> Unit,
    onSortChange: (ReplaySort) -> Unit,
    onGroupChange: (ReplayGroupBy) -> Unit,
    onDateRangeChange: (ReplayDateRange) -> Unit,
    onMapChange: (String?) -> Unit,
    onPlayersChange: (Int?, Boolean) -> Unit,
    onVersionChange: (String?, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        enableDismiss = true,
    ) { dismiss ->
        BorderCard(
            modifier = Modifier
                .fillMaxWidth(if (compact) 0.94f else 0.58f)
                .fillMaxHeight(0.88f)
                .widthIn(max = 560.dp)
                .padding(10.dp)
                .autoClearFocus(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ExitButton(dismiss)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                ) {
                    Text(
                        readI18n("replays.filter"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RWSingleOutlinedTextField(
                            readI18n("replays.search"),
                            filter,
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                        ) {
                            onFilterChange(it)
                        }

                        ReplayDateRangeRow(
                            dateRange = dateRange,
                            onDateRangeChange = onDateRangeChange,
                            showReset = false,
                            onReset = onReset,
                        )

                        ReplaySortGroupRow(
                            sort = sort,
                            groupBy = groupBy,
                            onSortChange = onSortChange,
                            onGroupChange = onGroupChange,
                        )

                        ReplayFacetRow(
                            compact = true,
                            facets = facets,
                            mapTitle = mapTitle,
                            playerCount = playerCount,
                            unknownPlayers = unknownPlayers,
                            version = version,
                            unknownVersion = unknownVersion,
                            onMapChange = onMapChange,
                            onPlayersChange = onPlayersChange,
                            onVersionChange = onVersionChange,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showReset) {
                            TextButton(onClick = onReset) {
                                Text(readI18n("replays.resetFilters"))
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        RWTextButton(readI18n("common.done"), onClick = dismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplayCountPill(count: Int) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .14f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f)),
    ) {
        Text(
            readI18n("replays.count", I18nType.RWPP, count.toString()),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplayDateRangeRow(
    dateRange: ReplayDateRange,
    onDateRangeChange: (ReplayDateRange) -> Unit,
    showReset: Boolean,
    onReset: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ReplayDateRange.entries.forEach { range ->
            FilterChip(
                selected = dateRange == range,
                onClick = { onDateRangeChange(range) },
                label = { Text(replayDateRangeLabel(range)) },
            )
        }
        if (showReset) {
            TextButton(onClick = onReset) {
                Text(readI18n("replays.resetFilters"))
            }
        }
    }
}

@Composable
private fun ReplaySortGroupRow(
    sort: ReplaySort,
    groupBy: ReplayGroupBy,
    onSortChange: (ReplaySort) -> Unit,
    onGroupChange: (ReplayGroupBy) -> Unit,
) {
    val sorts = ReplaySort.entries
    val groups = ReplayGroupBy.entries
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            LargeDropdownMenu(
                modifier = Modifier.fillMaxWidth(),
                label = readI18n("replays.sort"),
                items = sorts,
                selectedIndex = sorts.indexOf(sort).coerceAtLeast(0),
                selectedItemToString = { replaySortLabel(it) },
                onItemSelected = { _, item -> onSortChange(item) },
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            LargeDropdownMenu(
                modifier = Modifier.fillMaxWidth(),
                label = readI18n("replays.group"),
                items = groups,
                selectedIndex = groups.indexOf(groupBy).coerceAtLeast(0),
                selectedItemToString = { replayGroupLabel(it) },
                onItemSelected = { _, item -> onGroupChange(item) },
            )
        }
    }
}

@Composable
private fun ReplayFacetRow(
    compact: Boolean,
    facets: ReplayBrowseFacets,
    mapTitle: String?,
    playerCount: Int?,
    unknownPlayers: Boolean,
    version: String?,
    unknownVersion: Boolean,
    onMapChange: (String?) -> Unit,
    onPlayersChange: (Int?, Boolean) -> Unit,
    onVersionChange: (String?, Boolean) -> Unit,
) {
    val mapOptions = buildList {
        add(null)
        addAll(facets.maps)
        if (mapTitle != null && facets.maps.none { it.equals(mapTitle, ignoreCase = true) }) {
            add(mapTitle)
        }
    }
    val playerOptions = buildPlayerOptions(facets, playerCount, unknownPlayers)
    val versionOptions = buildVersionOptions(facets, version, unknownVersion)

    @Composable
    fun MapDropdown(modifier: Modifier) {
        LargeDropdownMenu(
            modifier = modifier,
            label = readI18n("replays.mapFilter"),
            items = mapOptions,
            selectedIndex = mapOptions.indexOfFirst {
                (it == null && mapTitle == null) || (it != null && it.equals(mapTitle, ignoreCase = true))
            }.coerceAtLeast(0),
            selectedItemToString = { it ?: readI18n("replays.mapAll") },
            onItemSelected = { _, item -> onMapChange(item) },
        )
    }

    @Composable
    fun PlayersDropdown(modifier: Modifier) {
        LargeDropdownMenu(
            modifier = modifier,
            label = readI18n("replays.playerFilter"),
            items = playerOptions,
            selectedIndex = playerOptions.indexOf(currentPlayerOption(playerCount, unknownPlayers))
                .coerceAtLeast(0),
            selectedItemToString = { playerOptionLabel(it) },
            onItemSelected = { _, item ->
                when (item) {
                    ReplayPlayerOption.All -> onPlayersChange(null, false)
                    ReplayPlayerOption.Unknown -> onPlayersChange(null, true)
                    is ReplayPlayerOption.Exact -> onPlayersChange(item.count, false)
                }
            },
        )
    }

    @Composable
    fun VersionDropdown(modifier: Modifier) {
        LargeDropdownMenu(
            modifier = modifier,
            label = readI18n("replays.versionFilter"),
            items = versionOptions,
            selectedIndex = versionOptions.indexOf(currentVersionOption(version, unknownVersion))
                .coerceAtLeast(0),
            selectedItemToString = { versionOptionLabel(it) },
            onItemSelected = { _, item ->
                when (item) {
                    ReplayVersionOption.All -> onVersionChange(null, false)
                    ReplayVersionOption.Unknown -> onVersionChange(null, true)
                    is ReplayVersionOption.Exact -> onVersionChange(item.name, false)
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (compact) {
            MapDropdown(Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) { PlayersDropdown(Modifier.fillMaxWidth()) }
                Box(modifier = Modifier.weight(1f)) { VersionDropdown(Modifier.fillMaxWidth()) }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1.2f)) { MapDropdown(Modifier.fillMaxWidth()) }
                Box(modifier = Modifier.weight(1f)) { PlayersDropdown(Modifier.fillMaxWidth()) }
                Box(modifier = Modifier.weight(1f)) { VersionDropdown(Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun ReplayGroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                readI18n("replays.count", I18nType.RWPP, count.toString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = readI18n(if (expanded) "replays.collapseGroup" else "replays.expandGroup"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplayListItem(
    item: ReplayBrowseItem,
    selectedMap: String?,
    selectedPlayerCount: Int?,
    unknownPlayers: Boolean,
    selectedVersion: String?,
    unknownVersion: Boolean,
    onMapClick: (String) -> Unit,
    onPlayersClick: (Int?) -> Unit,
    onVersionClick: (String?) -> Unit,
    onClick: () -> Unit,
) {
    val compact = LocalWindowManager.current == WindowManager.Small
    val label = item.label
    val recordedAt = label.recordedAt ?: formatReplayFileTime(item.sortTimeMillis)
    val iconSize = if (compact) 40.dp else 48.dp
    val mapSelected = selectedMap?.equals(label.title, ignoreCase = true) == true
    val playersSelected = if (label.playerCount == null) {
        unknownPlayers
    } else {
        !unknownPlayers && selectedPlayerCount == label.playerCount
    }
    val versionSelected = if (label.version.isNullOrBlank()) {
        unknownVersion
    } else {
        !unknownVersion && selectedVersion?.equals(label.version, ignoreCase = true) == true
    }

    BorderCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(.7f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (compact) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(iconSize),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .16f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .4f)),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = readI18n("replays.play"),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(if (compact) 6.dp else 8.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    label.title,
                    modifier = Modifier.clickable { onMapClick(label.title) },
                    style = if (compact) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    color = if (mapSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val players = label.playerCount
                    ReplayMetaPill(
                        text = if (players != null) {
                            readI18n("replays.players", I18nType.RWPP, players.toString())
                        } else {
                            readI18n("replays.playersUnknown")
                        },
                        color = MaterialTheme.colorScheme.primary,
                        selected = playersSelected,
                        onClick = { onPlayersClick(players) },
                    )
                    ReplayMetaPill(
                        text = label.version?.takeIf { it.isNotBlank() }
                            ?: readI18n("replays.versionUnknown"),
                        color = MaterialTheme.colorScheme.tertiary,
                        selected = versionSelected,
                        onClick = { onVersionClick(label.version?.takeIf { it.isNotBlank() }) },
                    )
                    if (!recordedAt.isNullOrBlank()) {
                        ReplayMetaPill(recordedAt, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.fileSizeBytes > 0L) {
                        ReplayMetaPill(
                            formatReplayBytes(item.fileSizeBytes),
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplayMetaPill(
    text: String,
    color: Color,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val bg = color.copy(alpha = if (selected) .28f else .14f)
    val border = color.copy(alpha = if (selected) .7f else .35f)
    val content: @Composable () -> Unit = {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            color = bg,
            border = BorderStroke(1.dp, border),
        ) { content() }
    } else {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = bg,
            border = BorderStroke(1.dp, border),
        ) { content() }
    }
}

private sealed class ReplayPlayerOption {
    data object All : ReplayPlayerOption()
    data object Unknown : ReplayPlayerOption()
    data class Exact(val count: Int) : ReplayPlayerOption()
}

private sealed class ReplayVersionOption {
    data object All : ReplayVersionOption()
    data object Unknown : ReplayVersionOption()
    data class Exact(val name: String) : ReplayVersionOption()
}

private fun currentPlayerOption(playerCount: Int?, unknownPlayers: Boolean): ReplayPlayerOption {
    return when {
        unknownPlayers -> ReplayPlayerOption.Unknown
        playerCount != null -> ReplayPlayerOption.Exact(playerCount)
        else -> ReplayPlayerOption.All
    }
}

private fun currentVersionOption(version: String?, unknownVersion: Boolean): ReplayVersionOption {
    return when {
        unknownVersion -> ReplayVersionOption.Unknown
        version != null -> ReplayVersionOption.Exact(version)
        else -> ReplayVersionOption.All
    }
}

private fun buildPlayerOptions(
    facets: ReplayBrowseFacets,
    playerCount: Int?,
    unknownPlayers: Boolean,
): List<ReplayPlayerOption> {
    val counts = facets.playerCounts.toMutableList()
    if (playerCount != null && playerCount !in counts) counts.add(playerCount)
    counts.sort()
    return buildList {
        add(ReplayPlayerOption.All)
        addAll(counts.map { ReplayPlayerOption.Exact(it) })
        if (facets.hasUnknownPlayers || unknownPlayers) add(ReplayPlayerOption.Unknown)
    }
}

private fun buildVersionOptions(
    facets: ReplayBrowseFacets,
    version: String?,
    unknownVersion: Boolean,
): List<ReplayVersionOption> {
    val versions = facets.versions.toMutableList()
    if (version != null && versions.none { it.equals(version, ignoreCase = true) }) {
        versions.add(version)
    }
    return buildList {
        add(ReplayVersionOption.All)
        addAll(versions.distinct().map { ReplayVersionOption.Exact(it) })
        if (facets.hasUnknownVersion || unknownVersion) add(ReplayVersionOption.Unknown)
    }
}

private fun playerOptionLabel(option: ReplayPlayerOption): String {
    return when (option) {
        ReplayPlayerOption.All -> readI18n("replays.playersAll")
        ReplayPlayerOption.Unknown -> readI18n("replays.playersUnknown")
        is ReplayPlayerOption.Exact -> readI18n("replays.players", I18nType.RWPP, option.count.toString())
    }
}

private fun versionOptionLabel(option: ReplayVersionOption): String {
    return when (option) {
        ReplayVersionOption.All -> readI18n("replays.versionAll")
        ReplayVersionOption.Unknown -> readI18n("replays.versionUnknown")
        is ReplayVersionOption.Exact -> option.name
    }
}

private fun replaySortLabel(sort: ReplaySort): String {
    return readI18n(
        when (sort) {
            ReplaySort.TimeNewest -> "replays.sortTimeNewest"
            ReplaySort.TimeOldest -> "replays.sortTimeOldest"
            ReplaySort.MapAsc -> "replays.sortMapAsc"
            ReplaySort.MapDesc -> "replays.sortMapDesc"
            ReplaySort.PlayersDesc -> "replays.sortPlayersDesc"
            ReplaySort.PlayersAsc -> "replays.sortPlayersAsc"
            ReplaySort.SizeDesc -> "replays.sortSizeDesc"
            ReplaySort.SizeAsc -> "replays.sortSizeAsc"
        }
    )
}

private fun replayGroupLabel(groupBy: ReplayGroupBy): String {
    return readI18n(
        when (groupBy) {
            ReplayGroupBy.None -> "replays.groupNone"
            ReplayGroupBy.Map -> "replays.groupMap"
            ReplayGroupBy.Players -> "replays.groupPlayers"
            ReplayGroupBy.Date -> "replays.groupDate"
            ReplayGroupBy.Version -> "replays.groupVersion"
        }
    )
}

private fun replayDateRangeLabel(range: ReplayDateRange): String {
    return readI18n(
        when (range) {
            ReplayDateRange.All -> "replays.dateAll"
            ReplayDateRange.Today -> "replays.dateToday"
            ReplayDateRange.Last7Days -> "replays.dateWeek"
            ReplayDateRange.Last30Days -> "replays.dateMonth"
        }
    )
}

private fun replaySectionTitle(key: ReplaySectionKey): String {
    return when (key) {
        ReplaySectionKey.Flat -> ""
        is ReplaySectionKey.Map -> key.name
        is ReplaySectionKey.Players -> key.count?.let {
            readI18n("replays.players", I18nType.RWPP, it.toString())
        } ?: readI18n("replays.playersUnknown")
        is ReplaySectionKey.Day -> key.ymd ?: readI18n("replays.dateUnknown")
        is ReplaySectionKey.Version -> key.name ?: readI18n("replays.versionUnknown")
    }
}

private fun formatReplayBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "${kb.roundToInt()} KB"
    val mb = kb / 1024.0
    return "${(mb * 10).roundToInt() / 10.0} MB"
}

private enum class ReplayImportStage {
    Preparing,
    Importing,
}

private data class ReplayImportProgress(
    val stage: ReplayImportStage,
    val fileName: String?,
    val copiedBytes: Long = 0L,
    val totalBytes: Long? = null,
)

@Composable
private fun ReplayImportProgressDialog(progress: ReplayImportProgress?) {
    AnimatedAlertDialog(
        visible = progress != null,
        onDismissRequest = {},
        enableDismiss = false,
    ) {
        BorderCard(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(10.dp)
                .widthIn(max = 420.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val title = when (progress?.stage) {
                    ReplayImportStage.Preparing -> readI18n("replays.importPreparing")
                    ReplayImportStage.Importing -> readI18n("replays.importing")
                    null -> ""
                }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                progress?.fileName?.let { name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val total = progress?.totalBytes
                val copied = progress?.copiedBytes ?: 0L
                if (total != null && total > 0L) {
                    LinearProgressIndicator(
                        progress = { (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatReplayBytes(copied)} / ${formatReplayBytes(total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
