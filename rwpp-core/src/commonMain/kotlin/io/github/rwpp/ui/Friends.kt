/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.GeneralProportion
import io.github.rwpp.widget.LargeDividingLine
import io.github.rwpp.widget.LargeProportion
import io.github.rwpp.widget.RWSingleOutlinedTextField
import io.github.rwpp.widget.RWTextButton
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.autoClearFocus
import io.github.rwpp.widget.v2.ExpandedCard

@Composable
internal fun AccountFriendsSection(
    isSmall: Boolean,
    initiallyShowAdd: Boolean = false,
) {
    val friends = FakeFriendsSession.friends
    var showAdd by remember { mutableStateOf(initiallyShowAdd) }

    LargeDividingLine { 16.dp }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            readI18n("friends.title", I18nType.RWPP),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        if (!isSmall) {
            RWTextButton(
                label = readI18n("friends.add", I18nType.RWPP),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = { showAdd = true },
            )
        }
    }
    if (isSmall) {
        Spacer(Modifier.height(8.dp))
        RWTextButton(
            label = readI18n("friends.add", I18nType.RWPP),
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
            onClick = { showAdd = true },
        )
    }

    Spacer(Modifier.height(12.dp))

    if (friends.isEmpty()) {
        Text(
            readI18n("friends.empty", I18nType.RWPP),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            textAlign = TextAlign.Center,
        )
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            friends.forEach { friend ->
                FriendRow(
                    friend = friend,
                    preview = FakeFriendsSession.lastMessagePreview(friend.identifier),
                    onClick = { openFriendChat(friend.identifier) },
                )
            }
        }
    }

    AddFriendDialog(
        visible = showAdd,
        onDismiss = { showAdd = false },
    )
}

fun openFriendChat(identifier: String) {
    FakeFriendsSession.openChat(identifier)
    UI.showFriendChatView = true
}

@Composable
private fun FriendRow(
    friend: FakeFriend,
    preview: String?,
    onClick: () -> Unit,
) {
    BorderCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    friend.displayName.firstOrNull()?.toString() ?: "?",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    friend.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    preview?.ifBlank { null } ?: readI18n("friends.noMessages", I18nType.RWPP),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AddFriendDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    var identifier by remember(visible) { mutableStateOf("") }
    var error by remember(visible) { mutableStateOf("") }
    val isSmall = LocalWindowManager.current == WindowManager.Small

    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
    ) { dismiss ->
        BorderCard(
            modifier = friendsDialogCardModifier(),
            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isSmall) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .autoClearFocus()
                    .padding(
                        horizontal = if (isSmall) 14.dp else 18.dp,
                        vertical = if (isSmall) 12.dp else 16.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (isSmall) 10.dp else 12.dp),
            ) {
                Text(
                    readI18n("friends.addTitle", I18nType.RWPP),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    readI18n("friends.addHint", I18nType.RWPP),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
                RWSingleOutlinedTextField(
                    label = readI18n("account.identifier", I18nType.RWPP),
                    value = identifier,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    onValueChange = {
                        identifier = it
                        error = ""
                    },
                )
                if (error.isNotBlank()) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val confirm = {
                    when (FakeFriendsSession.addFriend(identifier)) {
                        AddFriendResult.Blank ->
                            error = readI18n("account.identifierRequired", I18nType.RWPP)
                        AddFriendResult.Duplicate ->
                            error = readI18n("friends.alreadyAdded", I18nType.RWPP)
                        AddFriendResult.Self ->
                            error = readI18n("friends.cannotAddSelf", I18nType.RWPP)
                        AddFriendResult.Ok -> {
                            error = ""
                            dismiss()
                        }
                    }
                }
                if (isSmall) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RWTextButton(
                            label = readI18n("friends.add", I18nType.RWPP),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = confirm,
                        )
                        TextButton(onClick = dismiss) {
                            Text(
                                readI18n("common.cancel", I18nType.RWPP),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = dismiss) {
                            Text(
                                readI18n("common.cancel", I18nType.RWPP),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                        RWTextButton(
                            label = readI18n("friends.add", I18nType.RWPP),
                            onClick = confirm,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FriendChatView(onExit: () -> Unit) {
    BackHandler(true, onExit)
    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("friendChat").broadcastIn()
        }
    }

    val friend = FakeFriendsSession.activeFriend()
    val identifier = FakeFriendsSession.activeChatIdentifier
    val messages = identifier?.let { FakeFriendsSession.messagesOf(it) }
    val isSmall = LocalWindowManager.current == WindowManager.Small
    var draft by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    LaunchedEffect(messages?.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    ExpandedCard {
        Column(modifier = Modifier.fillMaxSize().imePadding().autoClearFocus()) {
            Box(modifier = Modifier.fillMaxWidth()) {
                ExitButton(onExit)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(36.dp))
                    Text(
                        friend?.displayName ?: readI18n("friends.chatTitle", I18nType.RWPP),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isNullOrEmpty()) {
                    Text(
                        readI18n("friends.noMessages", I18nType.RWPP),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    messages.forEach { message ->
                        ChatBubble(message)
                    }
                }
            }

            val send = {
                if (FakeFriendsSession.sendMessage(draft)) {
                    draft = ""
                }
            }
            if (isSmall) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RWSingleOutlinedTextField(
                        label = readI18n("friends.inputHint", I18nType.RWPP),
                        value = draft,
                        modifier = Modifier.fillMaxWidth(),
                        onValueChange = { draft = it },
                    )
                    RWTextButton(
                        label = readI18n("friends.send", I18nType.RWPP),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = send,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RWSingleOutlinedTextField(
                        label = readI18n("friends.inputHint", I18nType.RWPP),
                        value = draft,
                        modifier = Modifier.weight(1f),
                        onValueChange = { draft = it },
                    )
                    RWTextButton(
                        label = readI18n("friends.send", I18nType.RWPP),
                        onClick = send,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: FakeChatMessage) {
    val mine = message.fromMe
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.82f),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (mine) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (mine) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Text(
                message.timeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun friendsDialogCardModifier(): Modifier {
    val isSmall = LocalWindowManager.current == WindowManager.Small
    val maxHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp() * 0.9f
    }
    return Modifier
        .fillMaxWidth(if (isSmall) LargeProportion() else GeneralProportion())
        .widthIn(min = if (isSmall) 0.dp else 280.dp, max = 420.dp)
        .heightIn(max = maxHeight)
        .imePadding()
        .navigationBarsPadding()
}
