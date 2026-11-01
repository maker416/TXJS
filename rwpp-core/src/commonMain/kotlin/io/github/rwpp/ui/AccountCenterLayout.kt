/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n

internal enum class AccountCenterPage(val titleKey: String, val hintKey: String, val icon: ImageVector) {
    Profile("profileSection", "profilePageHint", Icons.Default.Person),
    Points("pointsSection", "pointsPageHint", Icons.Default.Star),
    Privacy("privacySection", "privacyPageHint", Icons.Default.Lock),
    Security("securitySection", "securityPageHint", Icons.Default.Settings),
}

/** 左侧导航常驻，只让右侧当前功能滚动；尺寸依据实际可用宽度，适配手机横屏。 */
@Composable
internal fun AccountCenterLayout(
    selectedPage: AccountCenterPage,
    onSelectPage: (AccountCenterPage) -> Unit,
    displayName: String,
    username: String,
    banner: String,
    avatar: @Composable () -> Unit,
    content: @Composable ColumnScope.(compact: Boolean) -> Unit,
) {
    val pageStates = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val short = maxHeight < 400.dp
        val gap = if (compact) 12.dp else 24.dp
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(if (compact) 88.dp else 200.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                    .verticalScroll(rememberScrollState(), overscrollEffect = null)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!compact) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (short) 0.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        avatar()
                        Column(Modifier.weight(1f)) {
                            Text(displayName, style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("@$username", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = if (short) 4.dp else 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant)
                }
                Column(
                    Modifier.fillMaxWidth().selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(if (short) 4.dp else 6.dp),
                ) {
                    AccountCenterPage.entries.forEach { page ->
                        val selected = selectedPage == page
                        val tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        val itemModifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent)
                            .selectable(selected, role = Role.Tab, onClick = { onSelectPage(page) })
                            .heightIn(min = if (compact) 64.dp else 48.dp)
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                        val label = readI18n("account.${page.titleKey}", I18nType.RWPP)
                        if (compact) {
                            Column(itemModifier, horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(page.icon, null, Modifier.size(22.dp), tint = tint)
                                Text(label, color = tint, style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center)
                            }
                        } else {
                            Row(itemModifier, verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(page.icon, null, Modifier.size(22.dp), tint = tint)
                                Text(label, color = tint, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = gap)) {
                // 标题与关闭入口都在滚动区之外，页面切换不移动导航。
                Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp)) {
                    Text(readI18n("account.${selectedPage.titleKey}", I18nType.RWPP),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(readI18n("account.${selectedPage.hintKey}", I18nType.RWPP),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                pageStates.SaveableStateProvider(selectedPage.name) {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().testTag("account-center-content"),
                        state = rememberLazyListState(),
                        contentPadding = PaddingValues(bottom = 20.dp),
                        overscrollEffect = null,
                    ) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (banner.isNotBlank()) AccountMessageBanner(banner, isError = true)
                                content(compact)
                            }
                        }
                    }
                }
            }
        }
    }
}
