/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.map.Replay
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.widget.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.nanihadesuka.compose.LazyVerticalGridScrollbar
import my.nanihadesuka.compose.ScrollbarSettings
import org.koin.compose.koinInject

@Composable
fun ReplaysViewDialog(
    onExit: () -> Unit
) {
    BackHandler(true, onExit)
    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("replays").broadcastIn()
        }
    }

    val game = koinInject<Game>()
    var filter by remember { mutableStateOf("") }
    var allReplays by remember { mutableStateOf(listOf<Replay>()) }
    val replays = remember(allReplays, filter) { allReplays.filter { it.name.contains(filter, ignoreCase = true) } }

    // 回放目录枚举移入 IO 协程，避免组合期磁盘 IO；加载期间先显示空列表占位
    LaunchedEffect(Unit) {
        allReplays = withContext(Dispatchers.IO) { game.getAllReplays() }
    }

    BorderCard(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp)
    ) {
        Box {
            ExitButton(onExit)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().scaleFit(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("Replay", style = MaterialTheme.typography.headlineLarge)
                }

                RWSingleOutlinedTextField(
                    "Filter",
                    filter,
                    modifier = Modifier.fillMaxWidth(.5f).align(Alignment.CenterHorizontally),
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                ) {
                    filter = it
                }

                LargeDividingLine { 0.dp }
                val state = rememberLazyGridState()
                LazyVerticalGridScrollbar(
                    state,
                    settings = ScrollbarSettings.Default.copy(
                        thumbSelectedColor = MaterialTheme.colorScheme.primary,
                        thumbUnselectedColor = MaterialTheme.colorScheme.inversePrimary,
                    ),
                ) {
                    LazyVerticalGrid(
                        state = state,
                        columns = GridCells.Fixed(5),
                    ) {
                        items(
                            replays,
                            key = { it.id }
                        ) { replay ->
                            // key 与列表项身份一致：项复用时 displayName 不会错串到其他回放
                            val mapName = rememberSaveable(replay.id) { replay.displayName() }
                            MapItem(mapName, null, false) {
                                game.watchReplay(replay)
                            }
                        }
                    }
                }
            }
        }
    }
}
