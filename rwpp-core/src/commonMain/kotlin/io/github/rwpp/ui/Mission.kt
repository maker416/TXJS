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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.base.Difficulty
import io.github.rwpp.game.map.Mission
import io.github.rwpp.game.map.MissionType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.widget.*
import org.koin.compose.koinInject

/**
 * 任务/生存模式关卡列表。
 * @param fixedType 固定任务类型（如生存模式）；为 null 时显示类型下拉框，行为同原任务页面。
 */
@Composable
fun MissionView(fixedType: MissionType? = null, onExit: () -> Unit) {
    BackHandler(true, onExit)
    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("mission").broadcastIn()
        }
    }

    val game = koinInject<Game>()
    val configIO = koinInject<ConfigIO>()

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
                    Text(
                        if (fixedType == null) readI18n("mission.title")
                        else readI18n("menu.singlePlayer.survival"),
                        style = MaterialTheme.typography.headlineLarge
                    )
                }

                var selectedIndex0 by remember {
                    mutableStateOf(
                        // fixedType 在平台实现中不存在时为 -1，下方加载处显示空列表；
                        // 不回退到 0，避免「标题生存模式、内容 Normal 关卡」的静默错配
                        fixedType?.let { game.getAllMissionTypes().indexOf(it) } ?: 0
                    )
                }
                var selectedIndex1 by remember { mutableStateOf(configIO.getGameConfig<Int>("aiDifficulty") + 2) }

                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(top = 5.dp)
                        .scaleFit()
                ) {
                    if (fixedType == null) {
                        with(game) {
                            LargeDropdownMenu(
                                modifier = Modifier.wrapContentSize().padding(5.dp),
                                label = readI18n("mission.type"),
                                items = getAllMissionTypes(),
                                selectedIndex = selectedIndex0,
                                onItemSelected = { index, _ -> selectedIndex0 = index }
                            )
                        }
                    }

                    LargeDropdownMenu(
                        modifier = Modifier.wrapContentSize().padding(5.dp),
                        label = readI18n("common.difficulty"),
                        items = Difficulty.entries,
                        selectedIndex = selectedIndex1,
                        onItemSelected = { index, _ -> selectedIndex1 = index }
                    )
                }

                LargeDividingLine { 0.dp }

                with(game) {
                    var missions by remember { mutableStateOf(listOf<Mission>()) }
                    LaunchedEffect(selectedIndex0) {
                        missions = getAllMissionTypes().getOrNull(selectedIndex0)
                            ?.let { getMissionsByType(it) } ?: emptyList()
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                    ) {
                        items(
                            count = missions.size,
                            key = { missions[it].id }
                        ) {
                            val mission = missions[it]
                            val difficulty = Difficulty.entries[selectedIndex1]
                            MapItem(mission.displayName(), mission) {
                                startNewMissionGame(
                                    difficulty,
                                    mission
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

