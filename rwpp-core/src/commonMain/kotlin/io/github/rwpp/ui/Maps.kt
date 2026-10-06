/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.rwpp.game.Game
import io.github.rwpp.game.map.GameMap
import io.github.rwpp.game.map.MapType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.widget.*
import io.github.rwpp.widget.v2.RWIconButton
import org.koin.compose.koinInject

@Composable
fun MapViewDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    lastSelectedIndex: Int = 0,
    lastSelectedMapType: MapType = MapType.SkirmishMap,
    onSelectedMap: (Int, GameMap) -> Unit
) = AnimatedAlertDialog(
    visible = visible, onDismissRequest = onDismissRequest
) { d ->
    BorderCard(
        modifier = Modifier
            .fillMaxSize(0.95f)
            .padding(10.dp)
            .autoClearFocus()
    ) {
        Box {
            ExitButton(d)
            Column {

                val game = koinInject<Game>()
                var filter by remember { mutableStateOf("") }
                val room = koinInject<Game>().gameRoom

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        readI18n("multiplayer.room.mapView"),
                        modifier = Modifier.padding(5.dp),
                        style = MaterialTheme.typography.headlineLarge.run { copy(fontSize = this.fontSize * scaleFitFloat()) })
                }

                var selectedIndex0 by remember { mutableStateOf(lastSelectedMapType.ordinal) }
                var maps by remember { mutableStateOf(listOf<GameMap>()) }
                val mapType = MapType.entries[selectedIndex0]
                var refreshGeneration by remember { mutableIntStateOf(0) }

                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(top = 5.dp)

                ) {
                    LargeDropdownMenu(
                        modifier = Modifier.wrapContentSize().padding(5.dp),
                        label = readI18n("multiplayer.room.mapType"),
                        items = if (room.isHost) MapType.entries else listOf(MapType.SkirmishMap),
                        selectedItemToString = { it.displayName() },
                        selectedIndex = selectedIndex0,
                        onItemSelected = { index, _ -> selectedIndex0 = index }
                    )

                    RWSingleOutlinedTextField(
                        "Filter",
                        filter,
                        modifier = Modifier.fillMaxWidth(.4f).padding(5.dp),
                        leadingIcon = { Icon(Icons.Default.Search, null) }
                    ) {
                        filter = it
                    }

                    RWIconButton(
                        Icons.Default.Refresh,
                        modifier = Modifier.offset(y = 10.dp).padding(5.dp),
                        size = 50.dp
                    ) {
                        refreshGeneration++
                    }
                }

                LargeDividingLine { 0.dp }

                with(game) {
                    LaunchedEffect(mapType, refreshGeneration) {
                        if (refreshGeneration > 0) getAllMaps(true)
                        maps = getAllMapsByMapType(mapType)
                    }
                    val filteredMaps = remember(maps, filter) {
                        maps.filter {
                            it.displayName().contains(filter, true)
                        }
                    }

                    val state1 = rememberLazyGridState()

                    var initialScrollDone by remember { mutableStateOf(false) }
                    LaunchedEffect(filteredMaps, mapType, filter) {
                        if (filteredMaps.isNotEmpty()) {
                            val index = if (!initialScrollDone && mapType == lastSelectedMapType && filter.isEmpty())
                                lastSelectedIndex.coerceIn(filteredMaps.indices) else 0
                            state1.scrollToItem(index)
                            initialScrollDone = true
                        }
                    }

                    LazyVerticalGrid(
                        state = state1,
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        items(
                            count = filteredMaps.size,
                            key = { "${mapType.name}:${filteredMaps[it].id}:${filteredMaps[it].mapName}" }
                        ) {
                            val map = filteredMaps[it]
                            val name = remember(map) { map.displayName() }
                            MapItem(
                                name,
                                map,
                                mapType != MapType.SavedGame
                            ) { onSelectedMap(it, map); d() }
                        }
                    }
                }
            }
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
        // 先固定占位，缩略图解码/缺图/异步切换都不能改变网格行高和桌面弹窗边界。
        modifier = Modifier
            .padding(10.dp)
            .fillMaxWidth()
            .height(200.dp * scaleFitFloat()),
        onClick = onClick,
        backgroundColor = MaterialTheme.colorScheme.surfaceContainer.copy(.7f)
    ) {
        if(showImage) {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().padding(5.dp).weight(1f).align(Alignment.CenterHorizontally),
            )
        }
        // 限制名称行数：卡片限高 200dp，长名称无限换行会把 weight(1f) 的图片挤压到不可见
        Text(
            name,
            modifier = Modifier.padding(5.dp).align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = if (showImage) 2 else 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
