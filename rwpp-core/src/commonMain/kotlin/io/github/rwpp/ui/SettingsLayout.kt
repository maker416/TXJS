/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.widget.ExitButton

/** Keep navigation and actions fixed while the selected settings scroll independently. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsLayout(
    categories: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onExit: () -> Unit,
    onSave: () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useSidebar = maxWidth >= 720.dp
        val sidebarWidth = (maxWidth * 0.2f).coerceIn(144.dp, 208.dp)
        val pane: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize().testTag("settings-content")) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Done, contentDescription = readI18n("settings.saveChanges"))
                        Spacer(Modifier.width(8.dp))
                        Text(readI18n("settings.saveChanges"))
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(48.dp)) { ExitButton(onExit) }
                }
                if (!useSidebar) {
                    SecondaryScrollableTabRow(
                        selectedTabIndex = selectedIndex,
                        edgePadding = 8.dp,
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.testTag("settings-tabs"),
                    ) {
                        categories.forEachIndexed { index, label ->
                            Tab(
                                selected = selectedIndex == index,
                                onClick = { onSelect(index) },
                                text = { Text(label, maxLines = 1) },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
        if (useSidebar) {
            Row(Modifier.fillMaxSize()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                    modifier = Modifier.width(sidebarWidth).fillMaxHeight(),
                ) {
                    Column(
                        Modifier.testTag("settings-sidebar").verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        categories.forEachIndexed { index, label ->
                            NavigationDrawerItem(
                                label = {
                                    Text(label, style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                selected = selectedIndex == index,
                                onClick = { onSelect(index) },
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.heightIn(min = 48.dp),
                                colors = NavigationDrawerItemDefaults.colors(
                                    unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            )
                        }
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Box(Modifier.weight(1f).fillMaxHeight()) { pane() }
            }
        } else {
            pane()
        }
    }
}
