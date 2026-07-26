/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.v2.ExpandedCard
import io.github.rwpp.widget.v2.bounceClick

enum class ModsMapsTab {
    Mods,
    Maps,
}

/**
 * 模组 / 地图顶部分段切换控件。
 */
@Composable
fun ModsMapsSegmentedControl(
    selected: ModsMapsTab,
    onSelect: (ModsMapsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = modifier.widthIn(min = 168.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .55f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = .35f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Segment(
                label = readI18n("maps.switchToMods"),
                selected = selected == ModsMapsTab.Mods,
                onClick = { onSelect(ModsMapsTab.Mods) },
                modifier = Modifier.weight(1f),
            )
            Segment(
                label = readI18n("maps.switchToMaps"),
                selected = selected == ModsMapsTab.Maps,
                onClick = { onSelect(ModsMapsTab.Maps) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = .22f)
    } else {
        Color.Transparent
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier.bounceClick(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = bg,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * 模组与自定义地图合一管理页。
 *
 * 共用一个 ExpandedCard 外壳：顶部分段与关闭按钮固定，
 * 仅内容区（含底栏）左右滑动切换。
 */
@Composable
fun ModsAndMapsView(onExit: () -> Unit) {
    var tab by rememberSaveable {
        val initial = UI.pendingModsMapsTab ?: ModsMapsTab.Mods
        UI.pendingModsMapsTab = null
        mutableStateOf(initial)
    }

    BackHandler(true, onExit)

    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("mods").broadcastIn()
            CloseUIPanelEvent("maps").broadcastIn()
        }
    }

    ExpandedCard {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 14.dp, end = 46.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModsMapsSegmentedControl(
                        selected = tab,
                        onSelect = { tab = it },
                        modifier = Modifier.widthIn(min = 200.dp, max = 280.dp),
                    )
                }

                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    transitionSpec = {
                        val toMaps = targetState == ModsMapsTab.Maps
                        val enter = slideInHorizontally(
                            animationSpec = tween(320),
                            initialOffsetX = { full -> if (toMaps) full else -full },
                        ) + fadeIn(animationSpec = tween(240))
                        val exit = slideOutHorizontally(
                            animationSpec = tween(320),
                            targetOffsetX = { full -> if (toMaps) -full else full },
                        ) + fadeOut(animationSpec = tween(200))
                        enter togetherWith exit
                    },
                    label = "modsMapsContent",
                ) { current ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (current) {
                            ModsMapsTab.Mods -> ModsView(
                                onExit = onExit,
                                embedded = true,
                            )
                            ModsMapsTab.Maps -> CustomMapsManagementView(
                                onExit = onExit,
                                embedded = true,
                            )
                        }
                    }
                }
            }
            ExitButton(onExit)
        }
    }
}
