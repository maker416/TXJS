/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.rwpp.game.Game
import io.github.rwpp.game.map.GameMap
import io.github.rwpp.game.map.MapType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.logger
import io.github.rwpp.widget.*
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject

@Composable
fun MapViewDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    lastSelectedIndex: Int = 0,
    lastSelectedMapType: MapType = MapType.SkirmishMap,
    onSelectedMap: (Int, GameMap) -> Unit,
) = AnimatedAlertDialog(visible = visible, onDismissRequest = onDismissRequest) { dismiss ->
    val game = koinInject<Game>()
    MapSelectionContent(
        mapTypes = if (game.gameRoom.isHost) MapType.entries else listOf(MapType.SkirmishMap),
        lastSelectedIndex = lastSelectedIndex,
        lastSelectedMapType = lastSelectedMapType,
        loadMaps = { type, refresh ->
            // 保留原有调用线程：地图枚举也会访问引擎模组目录与平台资源。
            if (refresh) game.getAllMaps(true)
            game.getAllMapsByMapType(type)
        },
        onDismiss = dismiss,
        onSelectedMap = onSelectedMap,
    )
}

private data class MapBrowserEntry(val sourceIndex: Int, val map: GameMap, val name: String, val key: String)

/** 房间和测试共用实际地图选择界面；引擎只负责提供地图列表。 */
@Composable
internal fun MapSelectionContent(
    mapTypes: List<MapType>,
    lastSelectedIndex: Int,
    lastSelectedMapType: MapType,
    loadMaps: (MapType, Boolean) -> List<GameMap>,
    onDismiss: () -> Unit,
    onSelectedMap: (Int, GameMap) -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    var mapType by remember(mapTypes, lastSelectedMapType) {
        mutableStateOf(lastSelectedMapType.takeIf { it in mapTypes } ?: mapTypes.first())
    }
    var entries by remember { mutableStateOf(emptyList<MapBrowserEntry>()) }
    var loadedMapType by remember { mutableStateOf<MapType?>(null) }
    var loadFailure by remember { mutableStateOf<String?>(null) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var loadedRefreshGeneration by remember { mutableIntStateOf(0) }
    val currentLoadMaps by rememberUpdatedState(loadMaps)
    LaunchedEffect(mapType, refreshGeneration) {
        loadFailure = null
        try {
            entries = currentLoadMaps(mapType, refreshGeneration != loadedRefreshGeneration)
                .mapIndexed { index, map ->
                    MapBrowserEntry(index, map, map.displayName(), "${mapType.name}:${map.id}:${map.mapName}:$index")
                }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            logger.warn("Failed to load map selection list: $mapType", failure)
            entries = emptyList()
            loadFailure = failure.message ?: failure.toString()
        }
        loadedMapType = mapType
        loadedRefreshGeneration = refreshGeneration
    }

    val filteredMaps = remember(entries, filter, loadedMapType, mapType) {
        if (loadedMapType != mapType) emptyList() else {
            entries.filter { it.name.contains(filter.trim(), ignoreCase = true) }
        }
    }
    val gridState = rememberLazyGridState()
    var initialScrollDone by remember { mutableStateOf(false) }
    var previousScrollRequest by remember { mutableStateOf<Pair<MapType, String>?>(null) }
    LaunchedEffect(filteredMaps, mapType, filter) {
        val request = mapType to filter
        if (filteredMaps.isNotEmpty() && previousScrollRequest != request) {
            val index = if (!initialScrollDone && mapType == lastSelectedMapType && filter.isEmpty()) {
                lastSelectedIndex.coerceIn(filteredMaps.indices)
            } else 0
            gridState.scrollToItem(index)
            initialScrollDone = true
            previousScrollRequest = request
        }
    }

    BorderCard(modifier = Modifier.fillMaxSize(0.95f).padding(10.dp)) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    readI18n("multiplayer.room.mapView"),
                    modifier = Modifier.fillMaxWidth().padding(end = 48.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                @Composable
                fun TypeSelector(modifier: Modifier) = LargeDropdownMenu(
                    modifier = modifier.testTag("mapType"),
                    label = readI18n("multiplayer.room.mapType"),
                    items = mapTypes,
                    selectedItemToString = { it.displayName() },
                    selectedIndex = mapTypes.indexOf(mapType),
                    onItemSelected = { _, type -> mapType = type },
                )

                @Composable
                fun SearchField(modifier: Modifier) = OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = modifier.testTag("mapSearch"),
                    label = { Text(readI18n("maps.search")) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    singleLine = true,
                    colors = RWOutlinedTextColors,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                )

                @Composable
                fun RefreshButton() = IconButton(
                    onClick = { refreshGeneration++ },
                    modifier = Modifier.size(48.dp).testTag("mapRefresh"),
                ) {
                    Icon(Icons.Default.Refresh, readI18n("maps.refresh"), tint = MaterialTheme.colorScheme.onSurface)
                }

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 640.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TypeSelector(Modifier.weight(1f))
                                RefreshButton()
                            }
                            SearchField(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TypeSelector(Modifier.width(240.dp))
                            SearchField(Modifier.weight(1f))
                            RefreshButton()
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                if (filteredMaps.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        if (loadFailure != null && loadedMapType == mapType) {
                            Text(
                                readI18n("common.failed") + ": " + loadFailure,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else if (loadedMapType == mapType) {
                            Text(readI18n("maps.emptyFiltered"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(180.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("mapGrid"),
                        contentPadding = PaddingValues(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            count = filteredMaps.size,
                            key = { index -> filteredMaps[index].key },
                        ) { index ->
                            val entry = filteredMaps[index]
                            MapItem(
                                name = entry.name,
                                model = entry.map,
                                showImage = mapType != MapType.SavedGame,
                            ) {
                                // 筛选只改变展示位置；保存源列表位置才能在重开时回到所选地图。
                                onSelectedMap(entry.sourceIndex, entry.map)
                                onDismiss()
                            }
                        }
                    }
                }
            }
            // 最后绘制，标题与工具栏不能覆盖关闭按钮的点击区域。
            ExitButton(onDismiss)
        }
    }
}

@Composable
fun LazyGridItemScope.MapItem(
    name: String,
    model: Any?,
    showImage: Boolean = true,
    onClick: () -> Unit,
) {
    BorderCard(
        // 固定占位：缩略图解码、长名称与窗口尺寸分档不能改变网格行高。
        modifier = Modifier.fillMaxWidth().height(200.dp),
        onClick = onClick,
        backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (showImage) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().padding(8.dp).weight(1f).align(Alignment.CenterHorizontally),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            name,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (showImage) 2 else 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (!showImage) Spacer(Modifier.weight(1f))
    }
}
