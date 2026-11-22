/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
import io.github.rwpp.rwpp_core.generated.resources.launcher_maps
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.ExpandedCard
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject

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

/** Filesystem metadata is captured once by the background scan, never during card composition. */
internal data class ManagedCustomMap(
    val map: CustomMapFile,
    val thumbnail: File?,
    val sizeLabel: String,
)

private fun readManagedCustomMaps(): List<ManagedCustomMap> = scanCustomMapFiles().map { map ->
    ManagedCustomMap(
        map = map,
        thumbnail = map.thumbnailFile().takeIf { it.isFile },
        sizeLabel = formatMapBytes(map.file.length().coerceAtLeast(0L)),
    )
}

private fun formatMapBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "${kb.roundToInt()} KB"
    val mb = kb / 1024.0
    return "${(mb * 10).roundToInt() / 10.0} MB"
}

@Composable
internal fun RowScope.MapsHeaderTools(
    filter: String,
    onFilterChange: (String) -> Unit,
    count: Int?,
    busy: Boolean,
    wide: Boolean,
    showCountBadge: Boolean,
) {
    if (wide) {
        if (showCountBadge && count != null) {
            LocalContentCountBadge(
                text = readI18n("maps.count", I18nType.RWPP, count.toString()),
            )
        }
        LocalContentSearchField(
            value = filter,
            onValueChange = onFilterChange,
            placeholder = readI18n("maps.search"),
            enabled = !busy,
            modifier = Modifier.widthIn(min = 160.dp, max = 260.dp),
            fieldModifier = Modifier.testTag("maps-search-field"),
        )
    } else {
        LocalContentSearchField(
            value = filter,
            onValueChange = onFilterChange,
            placeholder = readI18n("maps.search"),
            enabled = !busy,
            modifier = Modifier.weight(1f),
            fieldModifier = Modifier.testTag("maps-search-field"),
        )
        if (count != null) {
            LocalContentCountBadge(
                text = readI18n("maps.count", I18nType.RWPP, count.toString()),
            )
        }
    }
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
                .fillMaxWidth(0.86f)
                .padding(10.dp)
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(18.dp),
            backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val title = when (progress?.stage) {
                    MapImportStage.Preparing -> readI18n("maps.importPreparing")
                    MapImportStage.Importing -> readI18n("maps.importing")
                    null -> ""
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                progress?.fileName?.let { name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val total = progress?.totalBytes
                val copied = progress?.copiedBytes ?: 0L
                if (total != null && total > 0L) {
                    LinearProgressIndicator(
                        progress = { (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    )
                    Text(
                        "${formatMapBytes(copied)} / ${formatMapBytes(total)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    UiLinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun CustomMapsManagementView(
    onExit: () -> Unit,
    selectedTab: ModsMapsTab = ModsMapsTab.Maps,
    onTabChange: ((ModsMapsTab) -> Unit)? = null,
    /** 嵌入 [ModsAndMapsView] 共用外壳时为 true：不渲染 ExpandedCard / 关闭按钮。 */
    embedded: Boolean = false,
) {
    val shellCache = LocalLocalContentSessionCache.current
    val game = koinInject<Game>()
    val scope = rememberCoroutineScope()

    val fileLoad = rememberPageFileLoadState(initialValue = shellCache?.customMaps)
    val maps = remember {
        SnapshotStateList<ManagedCustomMap>().apply {
            shellCache?.customMaps?.let(::addAll)
        }
    }
    var localFilter by remember { mutableStateOf("") }
    val filter = shellCache?.mapsFilter ?: localFilter
    fun setFilter(value: String) {
        if (shellCache != null) shellCache.mapsFilter = value else localFilter = value
    }

    var importProgress by remember { mutableStateOf<MapImportProgress?>(null) }
    var pendingDelete by remember { mutableStateOf<CustomMapFile?>(null) }
    var pendingOverwrite by remember { mutableStateOf<File?>(null) }
    /** 文件选择已结束（进入导入/覆盖确认），忽略迟到的 Preparing 进度回调，避免叠层卡住。 */
    var suppressPrepareProgress by remember { mutableStateOf(false) }

    val busy = importProgress != null
    val filtered = remember(maps.size, filter, maps.toList()) {
        val keyword = filter.trim()
        if (keyword.isEmpty()) maps.toList()
        else maps.filter { it.map.displayName().contains(keyword, ignoreCase = true) }
    }

    SideEffect {
        shellCache?.mapsBusy = busy
        shellCache?.mapsVisibleCount = if (fileLoad.isReady) filtered.size else null
    }
    DisposableEffect(Unit) {
        onDispose {
            shellCache?.mapsBusy = false
            // 嵌入模式下切换 tab 会 dispose，勿广播关闭；由外壳统一处理。
            if (!embedded) {
                CloseUIPanelEvent("maps").broadcastIn()
            }
        }
    }

    suspend fun refreshMaps(refreshEngine: Boolean = true) {
        val files = fileLoad.load { readManagedCustomMaps() } ?: return
        maps.clear()
        maps.addAll(files)
        shellCache?.customMaps = files
        // getAllMaps may enter the platform engine. Preserve its original UI-thread contract.
        if (refreshEngine) game.getAllMaps(true)
    }

    LaunchedEffect(game) {
        if (fileLoad.value == null) refreshMaps(refreshEngine = false)
    }

    fun updateFileChooseProgress(progress: FileChooseProgress) {
        if (suppressPrepareProgress || pendingOverwrite != null) return
        if (!fileLoad.isReady || importProgress?.stage == MapImportStage.Importing) return
        importProgress = MapImportProgress(
            stage = MapImportStage.Preparing,
            fileName = progress.fileName,
            copiedBytes = progress.copiedBytes,
            totalBytes = progress.totalBytes,
        )
    }

    fun importMapFile(file: File, overwrite: Boolean = false) {
        if (!fileLoad.isReady || importProgress?.stage == MapImportStage.Importing) return

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

    fun deleteMap(map: CustomMapFile) {
        if (!fileLoad.isReady) return
        if (deleteCustomMapFilesSafely(map.file)) {
            maps.removeAll { it.map.file.absolutePath == map.file.absolutePath }
            shellCache?.customMaps = maps.toList()
            game.getAllMaps(true)
        } else {
            UI.showWarning(readI18n("maps.deleteFailed"))
        }
    }

    if (!embedded) {
        BackHandler(true) { onExit() }
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
                    .widthIn(max = 440.dp),
                shape = RoundedCornerShape(18.dp),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
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
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Text(
                            readI18n("maps.importExistsTitle"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        readI18n("maps.importExistsMessage", I18nType.RWPP, displayName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                pendingOverwrite = null
                                suppressPrepareProgress = false
                                dismiss()
                            },
                            modifier = Modifier.heightIn(min = 42.dp),
                        ) {
                            Text(readI18n("mod.cancel"))
                        }
                        LocalContentPrimaryButton(
                            text = readI18n("maps.importOverwrite"),
                            minHeight = 42.dp,
                            onClick = {
                                pendingOverwrite = null
                                dismiss()
                                importMapFile(file, overwrite = true)
                            },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { map ->
        AnimatedAlertDialog(
            visible = true,
            onDismissRequest = { pendingDelete = null },
        ) { dismiss ->
            BorderCard(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(10.dp)
                    .widthIn(max = 440.dp),
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
                            readI18n("maps.deleteConfirmTitle"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        readI18n("maps.deleteConfirmMessage", I18nType.RWPP, map.displayName()),
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
                            text = readI18n("mod.delete"),
                            leadingIcon = Icons.Default.Delete,
                            minHeight = 42.dp,
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            onClick = {
                                deleteMap(map)
                                dismiss()
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun MapsWorkspace(modifier: Modifier = Modifier) {
        BoxWithConstraints(modifier = modifier.fillMaxSize().testTag("mapsPage")) {
            val availableWidth = maxWidth
            val wide = availableWidth >= LocalContentWideBreakpoint
            val shortViewport = maxHeight < LocalContentShortHeightBreakpoint
            val gridMinCell = if (availableWidth < 460.dp) 148.dp else 188.dp

            Column(Modifier.fillMaxSize()) {
                if (shellCache == null) {
                    LocalContentTopChrome(
                        wide = wide,
                        shortViewport = shortViewport,
                        navigation = { navModifier ->
                            if (onTabChange != null) {
                                ModsMapsSegmentedControl(
                                    selected = selectedTab,
                                    onSelect = onTabChange,
                                    enabled = !busy,
                                    compact = shortViewport,
                                    modifier = if (wide) {
                                        navModifier.width(if (availableWidth < 700.dp) 224.dp else 264.dp)
                                    } else {
                                        navModifier
                                    },
                                )
                            } else {
                                Text(
                                    readI18n("maps.title"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = navModifier,
                                )
                            }
                        },
                        wideTools = {
                            MapsHeaderTools(
                                filter = filter,
                                onFilterChange = ::setFilter,
                                count = if (fileLoad.isReady) filtered.size else null,
                                busy = busy,
                                wide = true,
                                showCountBadge = availableWidth >= 680.dp,
                            )
                        },
                        narrowTools = {
                            MapsHeaderTools(
                                filter = filter,
                                onFilterChange = ::setFilter,
                                count = if (fileLoad.isReady) filtered.size else null,
                                busy = busy,
                                wide = false,
                                showCountBadge = true,
                            )
                        },
                        onClose = if (!embedded) onExit else null,
                        reserveTrailingCloseSpace = embedded,
                        busy = busy,
                    )
                }

                when {
                    !fileLoad.isReady -> PageFileLoading(
                        state = fileLoad,
                        pageTitle = readI18n("common.map"),
                        onRetry = { scope.launch { refreshMaps() } },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    filtered.isEmpty() -> {
                        val isFiltered = filter.isNotBlank()
                        LocalContentEmptyState(
                            message = readI18n(if (isFiltered) "maps.emptyFiltered" else "maps.empty"),
                            iconPainter = painterResource(Res.drawable.launcher_maps),
                            actionLabel = if (isFiltered) readI18n("replays.resetFilters") else null,
                            onAction = if (isFiltered) ({ setFilter("") }) else null,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    else -> {
                        val gridState = rememberLazyGridState()
                        val horizontalPad = if (wide) 16.dp else 12.dp
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(gridMinCell),
                            modifier = Modifier.weight(1f).fillMaxWidth().testTag("mapsGrid"),
                            contentPadding = PaddingValues(
                                start = horizontalPad,
                                end = horizontalPad,
                                top = if (shortViewport) 8.dp else 12.dp,
                                bottom = 14.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(
                                items = filtered,
                                key = { it.map.file.absolutePath },
                            ) { entry ->
                                CustomMapCard(
                                    entry = entry,
                                    onDelete = { pendingDelete = entry.map },
                                )
                            }
                        }
                    }
                }

                LocalContentBottomBar(shortViewport = shortViewport) {
                    val buttonHeight = if (shortViewport) 40.dp else 44.dp
                    val actionsReady = fileLoad.isReady && importProgress == null && pendingOverwrite == null && pendingDelete == null
                    LocalContentSecondaryButton(
                        text = readI18n("maps.refresh"),
                        leadingIcon = Icons.Default.Refresh,
                        enabled = actionsReady,
                        minHeight = buttonHeight,
                        onClick = { scope.launch { refreshMaps() } },
                        modifier = Modifier.testTag("mapsRefresh"),
                    )
                    Spacer(Modifier.weight(1f))
                    LocalContentPrimaryButton(
                        text = readI18n("maps.import"),
                        leadingPainter = painterResource(Res.drawable.file_open),
                        enabled = fileLoad.isReady && importProgress == null && pendingOverwrite == null,
                        minHeight = buttonHeight,
                        onClick = {
                            suppressPrepareProgress = false
                            appKoin.get<ExternalHandler>().openFileChooser(
                                onProgress = { updateFileChooseProgress(it) },
                            ) { file -> importMapFile(file) }
                        },
                        modifier = Modifier.testTag("mapsImport"),
                    )
                }
            }
        }
    }

    if (embedded) {
        MapsWorkspace(modifier = Modifier.fillMaxSize())
    } else {
        ExpandedCard {
            MapsWorkspace(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CustomMapCard(
    entry: ManagedCustomMap,
    onDelete: () -> Unit,
) {
    val map = entry.map
    val thumb = entry.thumbnail
    val thumbShape = RoundedCornerShape(10.dp)
    val deleteLabel = readI18n("mod.delete")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = localContentCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, localContentCardBorderColor()),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(thumbShape)
                    .background(Color.Black.copy(alpha = 0.25f)),
            ) {
                if (thumb != null) {
                    AsyncImage(
                        model = thumb,
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

                // 轻薄暗角渐变，增强预览层次并确保右上角删除徽章在亮色地图上清晰可见
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Black.copy(alpha = 0.22f),
                                    0.45f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.34f),
                                ),
                            ),
                        ),
                )

                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.58f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = deleteLabel }
                        .clickable(role = Role.Button, onClick = onDelete),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = Color(0xFFFF8A80),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Text(
                map.displayName(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            Row(
                modifier = Modifier.padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LocalContentMetaPill(
                    text = readI18n("maps.extensionTmx"),
                    color = MaterialTheme.colorScheme.secondary,
                )
                LocalContentMetaPill(
                    text = entry.sizeLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
