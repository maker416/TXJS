/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.RWSingleOutlinedTextField
import io.github.rwpp.widget.UiCircularProgressIndicator
import io.github.rwpp.widget.UiSwitch
import io.github.rwpp.widget.v2.LazyListScrollbarRail
import kotlin.math.roundToInt

/** 只保存筛选条件；标签请求和偏好持久化仍由多人页面负责。 */
internal data class MultiplayerFilters(
    val mapName: String = "",
    val creatorName: String = "",
    val playerLimitRange: IntRange = 0..100,
    val hideModdedRooms: Boolean = false,
    val hideNonJoinableRooms: Boolean = false,
    val selectedLabels: Set<String> = emptySet(),
)

/** 标题和操作区独立于滚动正文，低高度窗口也能完整显示黑名单入口。 */
@Composable
internal fun MultiplayerFilterPanel(
    filters: MultiplayerFilters,
    availableLabels: List<String>?,
    labelsLoading: Boolean,
    labelsFailed: Boolean,
    onFiltersChange: (MultiplayerFilters) -> Unit,
    onReset: () -> Unit,
    onBlacklist: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    BorderCard(
        modifier = modifier,
        backgroundColor = colors.background.copy(alpha = .98f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .65f)),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 64.dp, top = 18.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    readI18n("multiplayer.filter.title"),
                    modifier = Modifier.testTag("multiplayer-filter-title"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    readI18n("multiplayer.filter.subtitle"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { ExitButton(onDismiss) }
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f))

        // 固定预留滚动条宽度，滚动与标签异步加载都不会改变筛选项的宽度。
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = 16.dp).testTag("multiplayer-filter-scroll"),
                contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "search") {
                    FilterSectionCard(readI18n("multiplayer.filter.sectionSearch")) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val sideBySide = maxWidth / LocalDensity.current.fontScale >= 480.dp
                            val mapField: @Composable (Modifier) -> Unit = { fieldModifier ->
                                RWSingleOutlinedTextField(
                                    label = readI18n("multiplayer.filter.gameMapNameFilter"),
                                    value = filters.mapName,
                                    modifier = fieldModifier.testTag("multiplayer-filter-map"),
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    onValueChange = { onFiltersChange(filters.copy(mapName = it)) },
                                )
                            }
                            val creatorField: @Composable (Modifier) -> Unit = { fieldModifier ->
                                RWSingleOutlinedTextField(
                                    label = readI18n("multiplayer.filter.creatorNameFilter"),
                                    value = filters.creatorName,
                                    modifier = fieldModifier.testTag("multiplayer-filter-creator"),
                                    onValueChange = { onFiltersChange(filters.copy(creatorName = it)) },
                                )
                            }
                            if (sideBySide) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    mapField(Modifier.weight(1f))
                                    creatorField(Modifier.weight(1f))
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    mapField(Modifier.fillMaxWidth())
                                    creatorField(Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
                item(key = "range") {
                    FilterSectionCard(readI18n("multiplayer.filter.sectionRange")) {
                        var range by remember(filters.playerLimitRange) { mutableStateOf(filters.playerLimitRange) }
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                readI18n("multiplayer.filter.playerLimitRange"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Surface(shape = RoundedCornerShape(8.dp), color = colors.primary.copy(alpha = .12f)) {
                                Text(
                                    "${range.first} – ${range.last}",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.primary,
                                )
                            }
                        }
                        RangeSlider(
                            valueRange = 0f..100f,
                            modifier = Modifier.fillMaxWidth(),
                            value = range.first.toFloat()..range.last.toFloat(),
                            colors = SliderDefaults.colors(inactiveTrackColor = colors.primary.copy(alpha = .18f)),
                            onValueChange = { range = it.start.roundToInt()..it.endInclusive.roundToInt() },
                            onValueChangeFinished = { onFiltersChange(filters.copy(playerLimitRange = range)) },
                        )
                    }
                }
                item(key = "display") {
                    FilterSectionCard(readI18n("multiplayer.filter.sectionDisplay")) {
                        FilterSwitchRow(
                            label = readI18n("multiplayer.filter.hideModdedRooms"),
                            hint = readI18n("multiplayer.filter.hideModdedRoomsHint"),
                            checked = filters.hideModdedRooms,
                            modifier = Modifier.testTag("multiplayer-filter-modded"),
                            onCheckedChange = { onFiltersChange(filters.copy(hideModdedRooms = it)) },
                        )
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .3f))
                        FilterSwitchRow(
                            label = readI18n("multiplayer.filter.hideNonJoinableRooms"),
                            hint = readI18n("multiplayer.filter.hideNonJoinableRoomsHint"),
                            checked = filters.hideNonJoinableRooms,
                            modifier = Modifier.testTag("multiplayer-filter-joinable"),
                            onCheckedChange = { onFiltersChange(filters.copy(hideNonJoinableRooms = it)) },
                        )
                    }
                }
                item(key = "labels") {
                    FilterSectionCard(readI18n("multiplayer.filter.sectionLabel")) {
                        Text(
                            readI18n("multiplayer.filter.labelHint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        when {
                            labelsLoading -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                UiCircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                FilterStatusText(readI18n("multiplayer.filter.labelLoading"))
                            }
                            labelsFailed -> FilterStatusText(readI18n("multiplayer.filter.labelLoadFailed"))
                            availableLabels.isNullOrEmpty() -> FilterStatusText(readI18n("multiplayer.filter.labelUnavailable"))
                            else -> FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                availableLabels.forEach { label ->
                                    val selected = label in filters.selectedLabels
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            onFiltersChange(filters.copy(selectedLabels =
                                                if (selected) filters.selectedLabels - label else filters.selectedLabels + label,
                                            ))
                                        },
                                        label = { Text(label) },
                                        leadingIcon = if (selected) {
                                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                                        } else null,
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                }
                            }
                        }
                        // 请求失败时也保留清除入口，避免已有标签条件无法撤销。
                        if (filters.selectedLabels.isNotEmpty()) {
                            TextButton(onClick = { onFiltersChange(filters.copy(selectedLabels = emptySet())) }) {
                                Text(readI18n("multiplayer.filter.labelClearSelection"))
                            }
                        }
                    }
                }
            }
            LazyListScrollbarRail(
                listState,
                modifier = Modifier.align(Alignment.CenterEnd).width(12.dp).fillMaxHeight().padding(vertical = 12.dp),
            )
        }

        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f))
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceContainer.copy(alpha = .35f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onBlacklist,
                modifier = Modifier.heightIn(min = 48.dp).testTag("multiplayer-filter-blacklist"),
                shape = RoundedCornerShape(12.dp),
            ) { Text(readI18n("multiplayer.filter.blacklist")) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.heightIn(min = 48.dp).testTag("multiplayer-filter-reset"),
                shape = RoundedCornerShape(12.dp),
            ) { Text(readI18n("multiplayer.filter.reset")) }
        }
    }
}

@Composable
private fun FilterSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .55f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun FilterStatusText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FilterSwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            FilterStatusText(hint)
        }
        UiSwitch(checked = checked, onCheckedChange = null)
    }
}
