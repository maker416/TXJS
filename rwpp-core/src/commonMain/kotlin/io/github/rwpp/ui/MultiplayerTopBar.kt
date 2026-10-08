/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.coil.AccountAvatar
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.history_rotate_ccw_clock_30
import io.github.rwpp.rwpp_core.generated.resources.tune_30
import io.github.rwpp.widget.RWOutlinedTextColors
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.v2.RWIconButton
import org.jetbrains.compose.resources.painterResource

/** 列表顶内边距初值；实际以顶栏 [onGloballyPositioned] 测量为准（直连框约 56dp + 行内边距）。 */
internal val MultiplayerTopBarHeight = 68.dp

/**
 * 浮在列表之上的顶栏；列表通过顶部 [MultiplayerTopBarHeight] 内边距占位，避免顶栏与 [LazyColumn] 同 Column 重组。
 */
@Composable
internal fun MultiplayerTopBar(
    modifier: Modifier = Modifier,
    userName: String,
    accountNameLocked: Boolean,
    onUserNameChange: (String) -> Unit,
    joinServerAddress: String,
    onJoinServerAddressChange: (String) -> Unit,
    onJoinServer: () -> Unit,
    onFilter: () -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
    joinHistory: List<String>,
    onSelectJoinHistory: (String) -> Unit,
    onClose: () -> Unit,
) {
    val accountAvatar = remember(AccountSession.loggedIn, AccountSession.user, AccountSession.avatarVersion) {
        val user = AccountSession.user
        if (AccountSession.loggedIn && user != null) {
            AccountAvatar(user.id, user.hasAvatar, AccountSession.avatarVersion)
        } else {
            null
        }
    }

    val joinFieldWidth = when (LocalWindowManager.current) {
        WindowManager.Small -> 220.dp
        WindowManager.Middle -> 280.dp
        WindowManager.Large -> 320.dp
    }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        MultiplayerTopBarRow(
            userName = userName,
            accountNameLocked = accountNameLocked,
            onUserNameChange = onUserNameChange,
            accountAvatar = accountAvatar,
            joinServerAddress = joinServerAddress,
            onJoinServerAddressChange = onJoinServerAddressChange,
            onJoinServer = onJoinServer,
            onFilter = onFilter,
            onRefresh = onRefresh,
            isRefreshing = isRefreshing,
            joinFieldWidth = joinFieldWidth,
            joinHistory = joinHistory,
            onSelectJoinHistory = onSelectJoinHistory,
            onClose = onClose,
        )
    }
}

@Composable
private fun MultiplayerTopBarRow(
    userName: String,
    accountNameLocked: Boolean,
    onUserNameChange: (String) -> Unit,
    accountAvatar: AccountAvatar?,
    joinServerAddress: String,
    onJoinServerAddressChange: (String) -> Unit,
    onJoinServer: () -> Unit,
    onFilter: () -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
    joinFieldWidth: androidx.compose.ui.unit.Dp,
    joinHistory: List<String>,
    onSelectJoinHistory: (String) -> Unit,
    onClose: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MultiplayerTopBarHeight)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // 工具区还需三个 40dp 按钮、三个 6dp 间距与 16dp 区域内边距。
        // 空间不足时换成两行，昵称和关闭按钮始终拥有完整的可交互区域。
        val compact = maxWidth < joinFieldWidth + 154.dp + 168.dp * 2
        val identity: @Composable () -> Unit = {
            MultiplayerTopBarZone(modifier = Modifier.widthIn(max = 168.dp)) {
                MultiplayerIdentityContent(
                    userName = userName,
                    accountNameLocked = accountNameLocked,
                    onUserNameChange = onUserNameChange,
                    accountAvatar = accountAvatar,
                )
            }
        }

        val tools: @Composable () -> Unit = {
            MultiplayerTopBarZone(modifier = if (compact) Modifier.fillMaxWidth() else Modifier) {
                MultiplayerJoinToolsContent(
                    joinServerAddress = joinServerAddress,
                    onJoinServerAddressChange = onJoinServerAddressChange,
                    onJoinServer = onJoinServer,
                    onFilter = onFilter,
                    onRefresh = onRefresh,
                    isRefreshing = isRefreshing,
                    joinFieldWidth = joinFieldWidth,
                    joinHistory = joinHistory,
                    onSelectJoinHistory = onSelectJoinHistory,
                    stretchJoinField = compact,
                    modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
                )
            }
        }

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { identity() }
                    Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                        PanelCloseIconButton(onClose = onClose)
                    }
                }
                tools()
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { identity() }
                tools()
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    PanelCloseIconButton(onClose = onClose)
                }
            }
        }
    }
}

@Composable
private fun MultiplayerTopBarZone(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
private fun MultiplayerIdentityContent(
    userName: String,
    accountNameLocked: Boolean,
    onUserNameChange: (String) -> Unit,
    accountAvatar: AccountAvatar?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AccountAvatarBox(
            displayName = userName,
            size = 36.dp,
            avatar = accountAvatar,
        )
        if (accountNameLocked) {
            Text(
                userName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 108.dp),
            )
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        } else {
            OutlinedTextField(
                value = userName,
                onValueChange = onUserNameChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                colors = RWOutlinedTextColors,
                modifier = Modifier.width(108.dp),
            )
        }
    }
}

@Composable
private fun MultiplayerJoinToolsContent(
    joinServerAddress: String,
    onJoinServerAddressChange: (String) -> Unit,
    onJoinServer: () -> Unit,
    onFilter: () -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
    joinFieldWidth: androidx.compose.ui.unit.Dp,
    joinHistory: List<String>,
    onSelectJoinHistory: (String) -> Unit,
    stretchJoinField: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var historyMenuExpanded by remember { mutableStateOf(false) }
    val joinFieldExtraHeight = 16.dp

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            RWIconButton(
                painter = painterResource(Res.drawable.history_rotate_ccw_clock_30),
                size = 40.dp,
                modifier = Modifier.size(40.dp),
            ) { historyMenuExpanded = true }
            DropdownMenu(
                expanded = historyMenuExpanded,
                onDismissRequest = { historyMenuExpanded = false },
                offset = DpOffset(0.dp, joinFieldExtraHeight),
                modifier = Modifier.widthIn(min = joinFieldWidth + 92.dp, max = 420.dp),
            ) {
                if (joinHistory.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                readI18n("multiplayer.joinHistoryEmpty"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        onClick = { historyMenuExpanded = false },
                        modifier = Modifier.height(40.dp),
                    )
                } else {
                    joinHistory.take(NetworkJoinHistory.MAX_ENTRIES).forEach { entry ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    entry,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            onClick = {
                                onSelectJoinHistory(entry)
                                historyMenuExpanded = false
                            },
                            modifier = Modifier.height(40.dp),
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = joinServerAddress,
            onValueChange = onJoinServerAddressChange,
            singleLine = true,
            placeholder = {
                Text(
                    readI18n("multiplayer.joinServer"),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            textStyle = MaterialTheme.typography.bodySmall,
            colors = RWOutlinedTextColors,
            shape = RoundedCornerShape(8.dp),
            leadingIcon = {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            },
            trailingIcon = {
                IconButton(
                    onClick = onJoinServer,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = readI18n("multiplayer.joinServer"),
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            modifier = (if (stretchJoinField) Modifier.weight(1f) else Modifier.width(joinFieldWidth))
                .defaultMinSize(minHeight = 40.dp),
        )
        RWIconButton(
            painter = painterResource(Res.drawable.tune_30),
            size = 40.dp,
            modifier = Modifier.size(40.dp),
        ) { onFilter() }
        RefreshButtonWithHint(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.size(40.dp),
            buttonSize = 40.dp,
        )
    }
}
