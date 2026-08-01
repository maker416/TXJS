/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.appKoin
import io.github.rwpp.customMapDir
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.external.FileChooseProgress
import io.github.rwpp.game.Game
import io.github.rwpp.game.map.CustomMapFile
import io.github.rwpp.game.map.deleteCustomMapFilesSafely
import io.github.rwpp.game.map.scanCustomMapFiles
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.io.copyToWithProgress
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.error_missingmap
import io.github.rwpp.rwpp_core.generated.resources.file_open
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.ExpandedCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import java.io.File
import kotlin.math.roundToInt

private enum class MapImportStage {
    Preparing,
    Importing,
}

private data class MapImportProgress(
    val stage: MapImportStage,
    val fileName: String?,
    val copiedBytes: Long = 0L,
    val totalBytes: Long? = null,
)

private fun formatMapBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "${kb.roundToInt()} KB"
    val mb = kb / 1024.0
    return "${(mb * 10).roundToInt() / 10.0} MB"
}

/**
 * 地图列表项 UI 模型：扫描时（IO 协程）预计算磁盘状态，
 * 避免卡片在组合期做 exists()/length() 等磁盘 stat。
 */
private class CustomMapListEntry(
    val map: CustomMapFile,
) {
    val displayName: String = map.displayName()
    val thumbnail: File = map.thumbnailFile()
    val thumbnailExists: Boolean = thumbnail.exists()
    val fileSizeText: String = formatMapBytes(map.file.length().coerceAtLeast(0L))
    /** 列表项身份：路径 + 修改时间（覆盖导入同名文件后视为新项，缩略图等缓存随之失效）。 */
    val key: String = "${map.file.absolutePath}#${map.file.lastModified()}"
}

@Composable
private fun MapImportProgressDialog(progress: MapImportProgress?) {
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
                    MapImportStage.Preparing -> readI18n("maps.importPreparing")
                    MapImportStage.Importing -> readI18n("maps.importing")
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
                        "${formatMapBytes(copied)} / ${formatMapBytes(total)}",
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

@Composable
private fun MapMetadataPill(text: String, color: Color) {
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

@Composable
private fun MapCountPill(count: Int) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .14f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f)),
    ) {
        Text(
            readI18n("maps.count", I18nType.RWPP, count.toString()),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun CustomMapsManagementView(
    onExit: () -> Unit,
    selectedTab: ModsMapsTab = ModsMapsTab.Maps,
    onTabChange: ((ModsMapsTab) -> Unit)? = null,
    /** 嵌入 [ModsAndMapsView] 共用外壳时为 true：不渲染 ExpandedCard / 分段 / 关闭按钮。 */
    embedded: Boolean = false,
) {
    val game = koinInject<Game>()
    val scope = rememberCoroutineScope()
    val windowManager = LocalWindowManager.current
    val compact = windowManager == WindowManager.Small

    val maps = remember { SnapshotStateList<CustomMapListEntry>() }
    var filter by remember { mutableStateOf("") }
    var importProgress by remember { mutableStateOf<MapImportProgress?>(null) }
    var pendingDelete by remember { mutableStateOf<CustomMapListEntry?>(null) }
    var pendingOverwrite by remember { mutableStateOf<File?>(null) }
    /** 文件选择已结束（进入导入/覆盖确认），忽略迟到的 Preparing 进度回调，避免叠层卡住。 */
    var suppressPrepareProgress by remember { mutableStateOf(false) }

    // derivedStateOf 缓存过滤结果：仅在列表或关键字变化时重算，避免每次重组全量 filter
    val filtered by remember {
        derivedStateOf { maps.filter { it.displayName.contains(filter, ignoreCase = true) } }
    }

    // 初始地图列表扫描移入 IO 协程，避免组合期磁盘 IO；加载期间先显示空列表占位
    LaunchedEffect(Unit) {
        val scanned = withContext(Dispatchers.IO) { scanCustomMapFiles().map { CustomMapListEntry(it) } }
        maps.clear()
        maps.addAll(scanned)
    }

    fun refreshMaps() {
        scope.launch {
            // 目录扫描在 IO 线程执行，结果回主线程写 state
            val scanned = withContext(Dispatchers.IO) { scanCustomMapFiles().map { CustomMapListEntry(it) } }
            maps.clear()
            maps.addAll(scanned)
            game.getAllMaps(true)
        }
    }

    fun updateFileChooseProgress(progress: FileChooseProgress) {
        if (suppressPrepareProgress || pendingOverwrite != null) return
        if (importProgress?.stage == MapImportStage.Importing) return
        importProgress = MapImportProgress(
            stage = MapImportStage.Preparing,
            fileName = progress.fileName,
            copiedBytes = progress.copiedBytes,
            totalBytes = progress.totalBytes,
        )
    }

    fun importMapFile(file: File, overwrite: Boolean = false) {
        if (importProgress?.stage == MapImportStage.Importing) return

        // 立刻收起「准备导入」，并屏蔽迟到的选文件进度，防止与覆盖确认叠层。
        suppressPrepareProgress = true
        importProgress = null

        if (!file.extension.equals("tmx", ignoreCase = true)) {
            suppressPrepareProgress = false
            UI.showWarning(readI18n("maps.importInvalid"))
            return
        }

        val root = File(customMapDir)
        if (!root.exists()) root.mkdirs()
        val target = File(root, file.name)

        // 同名存在时同步弹出覆盖确认，不要先进入 Importing 进度。
        if (!overwrite && target.exists()) {
            pendingOverwrite = file
            return
        }

        scope.launch {
            try {
                importProgress = MapImportProgress(
                    stage = MapImportStage.Importing,
                    fileName = file.name,
                    totalBytes = file.length().takeIf { it > 0 },
                )

                var lastProgressUpdate = 0L
                withContext(Dispatchers.IO) {
                    file.copyToWithProgress(target, overwrite = overwrite) { copiedBytes, totalBytes ->
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate >= 100L || copiedBytes == totalBytes) {
                            lastProgressUpdate = now
                            withContext(Dispatchers.Main) {
                                importProgress = MapImportProgress(
                                    stage = MapImportStage.Importing,
                                    fileName = file.name,
                                    copiedBytes = copiedBytes,
                                    totalBytes = totalBytes.takeIf { it > 0 },
                                )
                            }
                        }
                    }
                }

                refreshMaps()
                UI.showWarning(readI18n("maps.importSuccess", I18nType.RWPP, CustomMapFile(file).displayName()))
            } catch (e: CancellationException) {
                throw e
            } catch (_: kotlin.io.FileAlreadyExistsException) {
                if (!overwrite) {
                    pendingOverwrite = file
                } else {
                    UI.showWarning(readI18n("maps.importFailed"))
                }
            } catch (_: Throwable) {
                UI.showWarning(readI18n("maps.importFailed"))
            } finally {
                importProgress = null
                if (pendingOverwrite == null) {
                    suppressPrepareProgress = false
                }
            }
        }
    }

    fun deleteMap(entry: CustomMapListEntry) {
        if (deleteCustomMapFilesSafely(entry.map.file)) {
            maps.removeAll { it.map.file.absolutePath == entry.map.file.absolutePath }
            game.getAllMaps(true)
        } else {
            UI.showWarning(readI18n("maps.deleteFailed"))
        }
    }

    if (!embedded) {
        BackHandler(true) { onExit() }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 嵌入模式下切换 tab 会 dispose，勿广播关闭；由外壳统一处理。
            if (!embedded) {
                CloseUIPanelEvent("maps").broadcastIn()
            }
        }
    }

    // 覆盖确认优先；有确认框时绝不显示进度，避免两层 Dialog 叠死。
    MapImportProgressDialog(if (pendingOverwrite == null) importProgress else null)

    pendingOverwrite?.let { file ->
        val displayName = CustomMapFile(file).displayName()
        AnimatedAlertDialog(
            visible = true,
            onDismissRequest = {
                pendingOverwrite = null
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
                        readI18n("maps.importExistsTitle"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        readI18n("maps.importExistsMessage", I18nType.RWPP, displayName),
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
                            suppressPrepareProgress = false
                            dismiss()
                        }
                        RWTextButton(readI18n("maps.importOverwrite")) {
                            pendingOverwrite = null
                            dismiss()
                            importMapFile(file, overwrite = true)
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        readI18n("maps.deleteConfirmTitle"),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        readI18n("maps.deleteConfirmMessage", I18nType.RWPP, entry.displayName),
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
                            deleteMap(entry)
                            dismiss()
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SearchField(modifier: Modifier = Modifier) {
        RWSingleOutlinedTextField(
            readI18n("maps.search"),
            filter,
            modifier = modifier,
            leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.surfaceTint) },
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
    }

    @Composable
    fun MapsTopBar() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    top = if (embedded) 6.dp else 14.dp,
                    end = if (embedded || compact) 10.dp else 46.dp,
                    bottom = 10.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 嵌入模式下分段由外壳提供。
            if (!embedded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (onTabChange != null) {
                        ModsMapsSegmentedControl(
                            selected = selectedTab,
                            onSelect = onTabChange,
                            modifier = if (compact) Modifier.weight(1f) else Modifier,
                        )
                    } else {
                        Text(
                            readI18n("maps.title"),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (!compact) {
                        Box(modifier = Modifier.weight(1f))
                    }
                    MapCountPill(filtered.size)
                }
            } else {
                MapCountPill(filtered.size)
            }
            SearchField(Modifier.fillMaxWidth())
        }
    }

    @Composable
    fun MapsBody(modifier: Modifier = Modifier) {
        Column(modifier = modifier.fillMaxSize()) {
            MapsTopBar()

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Image(
                            painter = painterResource(Res.drawable.error_missingmap),
                            contentDescription = null,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop,
                            alpha = 0.55f,
                        )
                        Text(
                            if (filter.isNotBlank()) {
                                readI18n("maps.emptyFiltered")
                            } else {
                                readI18n("maps.empty")
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                val gridState = rememberLazyGridState()
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(180.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, end = if (embedded || compact) 10.dp else 46.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = filtered,
                        key = { it.key },
                    ) { entry ->
                        CustomMapCard(
                            entry = entry,
                            onDelete = { pendingDelete = entry },
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RWTextButton(
                    readI18n("maps.refresh"),
                    leadingIcon = {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(28.dp))
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) { refreshMaps() }

                RWTextButton(
                    readI18n("maps.import"),
                    leadingIcon = {
                        Icon(
                            painterResource(Res.drawable.file_open),
                            null,
                            modifier = Modifier.size(28.dp),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    if (importProgress != null || pendingOverwrite != null) return@RWTextButton
                    suppressPrepareProgress = false
                    appKoin.get<ExternalHandler>().openFileChooser(
                        onProgress = { updateFileChooseProgress(it) },
                    ) { file -> importMapFile(file) }
                }
            }
        },
    ) { paddingValues ->
        if (embedded) {
            MapsBody(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        } else {
            ExpandedCard {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    MapsBody(modifier = Modifier.fillMaxSize())
                    ExitButton { onExit() }
                }
            }
        }
    }
}

@Composable
private fun CustomMapCard(
    entry: CustomMapListEntry,
    onDelete: () -> Unit,
) {
    // 缩略图路径/存在性、文件大小均在扫描时预计算（见 CustomMapListEntry），组合期不做磁盘 stat
    val thumbShape = RoundedCornerShape(8.dp)

    BorderCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(.7f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(thumbShape),
            ) {
                if (entry.thumbnailExists) {
                    AsyncImage(
                        model = entry.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Image(
                        painter = painterResource(Res.drawable.error_missingmap),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = readI18n("mod.delete"),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Text(
                entry.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MapMetadataPill(
                    text = readI18n("maps.extensionTmx"),
                    color = MaterialTheme.colorScheme.secondary,
                )
                MapMetadataPill(
                    text = entry.fileSizeText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
