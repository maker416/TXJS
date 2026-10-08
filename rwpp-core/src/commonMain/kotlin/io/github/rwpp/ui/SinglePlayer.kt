/*
 * Copyright 2023-2025 RWPP contributors
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.game.map.GameMap
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.destruction_30
import io.github.rwpp.rwpp_core.generated.resources.file_open
import io.github.rwpp.rwpp_core.generated.resources.shield_30
import io.github.rwpp.rwpp_core.generated.resources.stacks_30
import io.github.rwpp.rwpp_core.generated.resources.swords_30
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.v2.ExpandedCard
import io.github.rwpp.widget.v2.bounceClick
import org.jetbrains.compose.resources.painterResource

@Composable
fun SinglePlayerView(
    onExit: () -> Unit,
    onMission: () -> Unit,
    onSurvival: () -> Unit,
    onSkirmish: () -> Unit,
    onSandbox: () -> Unit,
    onLoadSavedGame: (GameMap) -> Unit,
) {
    BackHandler(true, onExit)
    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("singlePlayer").broadcastIn()
        }
    }

    var savedGamePickerVisible by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    SavedGamePickerDialog(
        visible = savedGamePickerVisible,
        onDismissRequest = { savedGamePickerVisible = false },
        onSelectedSave = { map ->
            savedGamePickerVisible = false
            onLoadSavedGame(map)
        },
    )

    val cardWidth = when (LocalWindowManager.current) {
        WindowManager.Small -> 1f
        WindowManager.Middle -> 0.88f
        WindowManager.Large -> 0.7f
    }

    ExpandedCard {
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    SinglePlayerEntry(
                        modifier = Modifier.fillMaxWidth(cardWidth),
                        icon = painterResource(Res.drawable.destruction_30),
                        title = readI18n("menu.singlePlayer.mission"),
                        description = readI18n("menu.singlePlayer.missionDesc"),
                        onClick = onMission,
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    SinglePlayerEntry(
                        modifier = Modifier.fillMaxWidth(cardWidth),
                        icon = painterResource(Res.drawable.shield_30),
                        title = readI18n("menu.singlePlayer.survival"),
                        description = readI18n("menu.singlePlayer.survivalDesc"),
                        onClick = onSurvival,
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    SinglePlayerEntry(
                        modifier = Modifier.fillMaxWidth(cardWidth),
                        icon = painterResource(Res.drawable.swords_30),
                        title = readI18n("menu.singlePlayer.skirmish"),
                        description = readI18n("menu.singlePlayer.skirmishDesc"),
                        onClick = onSkirmish,
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    SinglePlayerEntry(
                        modifier = Modifier.fillMaxWidth(cardWidth),
                        icon = painterResource(Res.drawable.stacks_30),
                        title = readI18n("menu.singlePlayer.sandbox"),
                        description = readI18n("menu.singlePlayer.sandboxDesc"),
                        onClick = onSandbox,
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    SinglePlayerEntry(
                        modifier = Modifier.fillMaxWidth(cardWidth),
                        icon = painterResource(Res.drawable.file_open),
                        title = readI18n("menus.singlePlayer.loadSave", I18nType.RW),
                        description = readI18n("menu.singlePlayer.loadSaveDesc"),
                        onClick = { savedGamePickerVisible = true },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            PanelScrollRail(onClose = onExit, listState = listState)
        }
    }
}

@Composable
private fun SinglePlayerEntry(
    modifier: Modifier = Modifier,
    icon: Painter,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    BorderCard(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .bounceClick(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        RoundedCornerShape(14.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
