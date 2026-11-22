/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.rwpp.content.ExpansionPackages
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.game.map.GameMap
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.extension_30
import io.github.rwpp.rwpp_core.generated.resources.file_open
import io.github.rwpp.rwpp_core.generated.resources.launcher_maps
import io.github.rwpp.widget.animateUiColorAsState
import io.github.rwpp.widget.animateUiFloatAsState
import io.github.rwpp.widget.uiAnimationsEnabled
import io.github.rwpp.widget.v2.ExpandedCard
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource

enum class ModsMapsTab {
    Mods,
    Maps,
    Saves,
}

/**
 * 本地内容的模组 / 地图 / 存档分段切换控件。
 * 选中指示胶囊在布局阶段平滑滑移（零每帧重组），禁用动画时立即切换。
 */
@Composable
fun ModsMapsSegmentedControl(
    selected: ModsMapsTab,
    onSelect: (ModsMapsTab) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val tabs = ModsMapsTab.entries
    val segmentHeight = if (compact) 34.dp else 36.dp
    val animatedIndex = animateUiFloatAsState(
        targetValue = selected.ordinal.toFloat(),
        animationSpec = tween(240, easing = EmphasizedDecelerateEasing),
        label = "localContentTabIndicator",
    )

    Surface(
        modifier = modifier
            .widthIn(min = 180.dp)
            .alpha(if (enabled) 1f else 0.62f)
            .testTag("local-content-segmented"),
        shape = RoundedCornerShape(12.dp),
        color = localContentRecessedColor(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
        ) {
            val segmentWidth = maxWidth / tabs.size
            val showIcon = segmentWidth >= 68.dp
            val segmentWidthPx = with(LocalDensity.current) { segmentWidth.toPx() }

            Surface(
                modifier = Modifier
                    .offset { IntOffset((animatedIndex.value * segmentWidthPx).roundToInt(), 0) }
                    .width(segmentWidth)
                    .height(segmentHeight),
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)),
            ) {}

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(segmentHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val isSelected = tab == selected
                    val fg by animateUiColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(180),
                        label = "tabColor_${tab.name}",
                    )
                    val label = when (tab) {
                        ModsMapsTab.Mods -> readI18n("maps.switchToMods")
                        ModsMapsTab.Maps -> readI18n("maps.switchToMaps")
                        ModsMapsTab.Saves -> readI18n("saves.tab")
                    }
                    val iconRes = when (tab) {
                        ModsMapsTab.Mods -> Res.drawable.extension_30
                        ModsMapsTab.Maps -> Res.drawable.launcher_maps
                        ModsMapsTab.Saves -> Res.drawable.file_open
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(segmentHeight)
                            .clip(RoundedCornerShape(9.dp))
                            .testTag("local-content-tab-${tab.name}")
                            .selectable(
                                selected = isSelected,
                                enabled = enabled,
                                role = Role.Tab,
                                onClick = { onSelect(tab) },
                            )
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showIcon) {
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = null,
                                tint = fg,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 本地内容管理页：模组、自定义地图和存档。
 *
 * 共用常驻顶栏（分段导航、上下文工具、关闭按钮）与会话级缓存；
 * 切换标签时顶栏位置与尺寸完全稳定，仅内容区平滑过渡且回访已加载标签无闪烁。
 */
@Composable
fun ModsAndMapsView(onLoadSavedGame: suspend (GameMap) -> Unit, onExit: () -> Unit) {
    val cache = remember { LocalContentSessionCache() }
    var tab by rememberSaveable {
        val initial = UI.pendingModsMapsTab ?: ModsMapsTab.Mods
        UI.pendingModsMapsTab = null
        mutableStateOf(initial)
    }
    LaunchedEffect(UI.pendingModsMapsTab) {
        val pending = UI.pendingModsMapsTab ?: return@LaunchedEffect
        UI.pendingModsMapsTab = null
        if (!cache.anyBusy) tab = pending
    }

    BackHandler(true) { if (!cache.anyBusy) onExit() }

    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("mods").broadcastIn()
            CloseUIPanelEvent("maps").broadcastIn()
        }
    }

    ExpandedCard {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val availableWidth = maxWidth
            val wide = availableWidth >= LocalContentWideBreakpoint
            val shortViewport = maxHeight < LocalContentShortHeightBreakpoint
            val contentAnimations = uiAnimationsEnabled()
            val expansionLocked = ExpansionPackages.activeSha != null

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                                0.35f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.12f),
                            ),
                        ),
                    ),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LocalContentTopChrome(
                        wide = wide,
                        shortViewport = shortViewport,
                        navigation = { navModifier ->
                            ModsMapsSegmentedControl(
                                selected = tab,
                                onSelect = { target -> if (!cache.anyBusy) tab = target },
                                enabled = !cache.anyBusy,
                                compact = shortViewport,
                                modifier = if (wide) {
                                    navModifier.width(if (availableWidth < 700.dp) 224.dp else 264.dp)
                                } else {
                                    navModifier
                                },
                            )
                        },
                        wideTools = {
                            AnimatedContent(
                                targetState = tab,
                                modifier = Modifier.fillMaxWidth(),
                                transitionSpec = {
                                    if (!contentAnimations) {
                                        (EnterTransition.None togetherWith ExitTransition.None).using(null)
                                    } else {
                                        (fadeIn(tween(160, easing = StandardDecelerateEasing)) togetherWith
                                            fadeOut(tween(110, easing = EmphasizedAccelerateEasing))).using(null)
                                    }
                                },
                                label = "localContentWideTools",
                            ) { activeTab ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                ) {
                                    when (activeTab) {
                                        ModsMapsTab.Mods -> if (!expansionLocked) {
                                            ModsHeaderTools(
                                                filter = cache.modsFilter,
                                                onFilterChange = { cache.modsFilter = it },
                                                searchExpanded = cache.modsSearchExpanded,
                                                onExpandSearch = { cache.modsSearchExpanded = true },
                                                onCloseSearch = {
                                                    cache.modsFilter = ""
                                                    cache.modsSearchExpanded = false
                                                },
                                                wide = true,
                                                compactWidth = availableWidth < 740.dp,
                                            )
                                        }
                                        ModsMapsTab.Maps -> MapsHeaderTools(
                                            filter = cache.mapsFilter,
                                            onFilterChange = { cache.mapsFilter = it },
                                            count = cache.mapsVisibleCount,
                                            busy = cache.mapsBusy,
                                            wide = true,
                                            showCountBadge = availableWidth >= 680.dp,
                                        )
                                        ModsMapsTab.Saves -> SavesHeaderTools(
                                            filter = cache.savesFilter,
                                            onFilterChange = { cache.savesFilter = it },
                                            oldestFirst = cache.savesOldestFirst,
                                            onToggleSort = { cache.savesOldestFirst = !cache.savesOldestFirst },
                                            count = cache.savesVisibleCount,
                                            ready = cache.savesReady,
                                            busy = cache.savesBusy,
                                            wide = true,
                                            showCountBadge = availableWidth >= 780.dp,
                                        )
                                    }
                                }
                            }
                        },
                        narrowTools = {
                            AnimatedContent(
                                targetState = tab,
                                modifier = Modifier.fillMaxWidth(),
                                transitionSpec = {
                                    if (!contentAnimations) {
                                        (EnterTransition.None togetherWith ExitTransition.None).using(null)
                                    } else {
                                        (fadeIn(tween(160, easing = StandardDecelerateEasing)) togetherWith
                                            fadeOut(tween(110, easing = EmphasizedAccelerateEasing))).using(null)
                                    }
                                },
                                label = "localContentNarrowTools",
                            ) { activeTab ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    when (activeTab) {
                                        ModsMapsTab.Mods -> if (!expansionLocked) {
                                            ModsHeaderTools(
                                                filter = cache.modsFilter,
                                                onFilterChange = { cache.modsFilter = it },
                                                searchExpanded = cache.modsSearchExpanded,
                                                onExpandSearch = { cache.modsSearchExpanded = true },
                                                onCloseSearch = {
                                                    cache.modsFilter = ""
                                                    cache.modsSearchExpanded = false
                                                },
                                                wide = false,
                                                compactWidth = true,
                                            )
                                        }
                                        ModsMapsTab.Maps -> MapsHeaderTools(
                                            filter = cache.mapsFilter,
                                            onFilterChange = { cache.mapsFilter = it },
                                            count = cache.mapsVisibleCount,
                                            busy = cache.mapsBusy,
                                            wide = false,
                                            showCountBadge = true,
                                        )
                                        ModsMapsTab.Saves -> SavesHeaderTools(
                                            filter = cache.savesFilter,
                                            onFilterChange = { cache.savesFilter = it },
                                            oldestFirst = cache.savesOldestFirst,
                                            onToggleSort = { cache.savesOldestFirst = !cache.savesOldestFirst },
                                            count = cache.savesVisibleCount,
                                            ready = cache.savesReady,
                                            busy = cache.savesBusy,
                                            wide = false,
                                            showCountBadge = false,
                                        )
                                    }
                                }
                            }
                        },
                        onClose = { if (!cache.anyBusy) onExit() },
                        closeEnabled = !cache.anyBusy,
                        busy = cache.anyBusy,
                    )

                    CompositionLocalProvider(LocalLocalContentSessionCache provides cache) {
                        AnimatedContent(
                            targetState = tab,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            transitionSpec = {
                                if (!contentAnimations) {
                                    return@AnimatedContent (EnterTransition.None togetherWith ExitTransition.None).using(null)
                                }
                                val forward = targetState.ordinal > initialState.ordinal
                                val enter = slideInHorizontally(
                                    animationSpec = tween(250, easing = EmphasizedDecelerateEasing),
                                    initialOffsetX = { full ->
                                        val shift = (full * 0.10f).roundToInt()
                                        if (forward) shift else -shift
                                    },
                                ) + fadeIn(
                                    animationSpec = tween(200, delayMillis = 25, easing = StandardDecelerateEasing),
                                )
                                val exit = slideOutHorizontally(
                                    animationSpec = tween(175, easing = EmphasizedAccelerateEasing),
                                    targetOffsetX = { full ->
                                        val shift = (full * 0.08f).roundToInt()
                                        if (forward) -shift else shift
                                    },
                                ) + fadeOut(
                                    animationSpec = tween(130, easing = EmphasizedAccelerateEasing),
                                )
                                (enter togetherWith exit).using(null)
                            },
                            label = "modsMapsContent",
                        ) { current ->
                            Box(modifier = Modifier.fillMaxSize()) {
                                when (current) {
                                    ModsMapsTab.Mods -> ModsView(
                                        onExit = onExit,
                                        selectedTab = tab,
                                        onTabChange = { if (!cache.anyBusy) tab = it },
                                        embedded = true,
                                    )
                                    ModsMapsTab.Maps -> CustomMapsManagementView(
                                        onExit = onExit,
                                        selectedTab = tab,
                                        onTabChange = { if (!cache.anyBusy) tab = it },
                                        embedded = true,
                                    )
                                    ModsMapsTab.Saves -> SavedGamesManagementView(
                                        onLoadSavedGame = onLoadSavedGame,
                                        onTabChange = { if (!cache.anyBusy) tab = it },
                                        onBusyChange = { cache.savesBusy = it },
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
