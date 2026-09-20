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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.account.accountErrorText
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.account.AccountApiException
import io.github.rwpp.net.account.AccountFieldRules
import io.github.rwpp.net.account.ChatMessageDto
import io.github.rwpp.net.account.FriendRequestDto
import io.github.rwpp.net.account.FriendRequestStatus
import io.github.rwpp.net.account.PublicUser
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.LargeDividingLine
import io.github.rwpp.widget.RWSingleOutlinedTextField
import io.github.rwpp.widget.RWTextButton
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.autoClearFocus
import io.github.rwpp.widget.v2.ExpandedCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun AccountFriendsSection(
    isSmall: Boolean,
    initiallyShowAdd: Boolean = false,
) {
    val friends = FriendsSession.friends
    val incoming = FriendsSession.incoming
    val outgoing = FriendsSession.outgoing
    var showAdd by remember { mutableStateOf(initiallyShowAdd) }
    val scope = rememberCoroutineScope()

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
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(22.dp))
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
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(22.dp))
            },
            onClick = { showAdd = true },
        )
    }

    if (FriendsSession.loadingLists) {
        CircularProgressIndicator(
            modifier = Modifier.padding(vertical = 8.dp).size(28.dp),
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (incoming.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            readI18n("friends.incoming", I18nType.RWPP),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        incoming.forEach { req ->
            FriendRequestRow(
                request = req,
                incoming = true,
                onAccept = { scope.launch { runCatching { FriendsSession.accept(req.id) } } },
                onReject = { scope.launch { runCatching { FriendsSession.reject(req.id) } } },
                onCancel = {},
            )
        }
    }

    if (outgoing.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            readI18n("friends.outgoing", I18nType.RWPP),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        outgoing.forEach { req ->
            FriendRequestRow(
                request = req,
                incoming = false,
                onAccept = {},
                onReject = {},
                onCancel = { scope.launch { runCatching { FriendsSession.cancel(req.id) } } },
            )
        }
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
            friends.forEach { item ->
                FriendRow(
                    user = item.user,
                    preview = FriendsSession.lastMessagePreview(item.user.id),
                    unread = FriendsSession.unreadOf(item.user.id),
                    onClick = {
                        scope.launch {
                            runCatching { FriendsSession.openChat(item.user) }
                            UI.showFriendChatView = true
                        }
                    },
                    onDelete = {
                        scope.launch { runCatching { FriendsSession.deleteFriend(item.user.id) } }
                    },
                )
            }
        }
    }

    AddFriendDialog(
        visible = showAdd,
        onDismiss = { showAdd = false },
    )
}

fun openFriendChat(peer: PublicUser) {
    UI.showFriendChatView = true
}

@Composable
private fun FriendRequestRow(
    request: FriendRequestDto,
    incoming: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
) {
    val other = if (incoming) request.fromUser else request.toUser
    BorderCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                other.nickname.ifBlank { other.username },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                other.username,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (incoming) {
                    RWTextButton(label = readI18n("friends.accept", I18nType.RWPP), onClick = onAccept)
                    TextButton(onClick = onReject) {
                        Text(
                            readI18n("friends.reject", I18nType.RWPP),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    TextButton(onClick = onCancel) {
                        Text(
                            readI18n("friends.cancel", I18nType.RWPP),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendRow(
    user: PublicUser,
    preview: String?,
    unread: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
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
                    user.nickname.firstOrNull()?.toString() ?: "?",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    user.nickname.ifBlank { user.username },
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
            if (unread > 0) {
                SurfaceBadge(unread)
            }
            TextButton(onClick = { confirmDelete = true }) {
                Text(
                    readI18n("friends.delete", I18nType.RWPP),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (confirmDelete) {
        AnimatedAlertDialog(
            visible = true,
            onDismissRequest = { confirmDelete = false },
        ) { dismiss ->
            BorderCard(
                modifier = accountDialogCardModifier(),
                backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        readI18n("friends.deleteConfirmTitle", I18nType.RWPP),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        readI18n("friends.deleteConfirmBody", I18nType.RWPP),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = dismiss) {
                            Text(readI18n("common.cancel", I18nType.RWPP))
                        }
                        TextButton(
                            onClick = {
                                onDelete()
                                dismiss()
                            },
                        ) {
                            Text(
                                readI18n("friends.delete", I18nType.RWPP),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SurfaceBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun AddFriendDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    var username by remember(visible) { mutableStateOf("") }
    var error by remember(visible) { mutableStateOf("") }
    var info by remember(visible) { mutableStateOf("") }
    var submitting by remember(visible) { mutableStateOf(false) }
    val isSmall = LocalWindowManager.current == WindowManager.Small
    val scope = rememberCoroutineScope()

    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = { if (!submitting) onDismiss() },
        enableDismiss = !submitting,
    ) { dismiss ->
        BorderCard(
            modifier = accountDialogCardModifier(),
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
                    label = readI18n("account.username", I18nType.RWPP),
                    value = username,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    onValueChange = {
                        username = it
                        error = ""
                        info = ""
                    },
                )
                if (info.isNotBlank() && error.isBlank()) {
                    Text(info, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                val confirm: () -> Unit = {
                    val name = AccountFieldRules.normalizeUsername(username)
                    when {
                        !AccountFieldRules.isValidUsername(name) ->
                            error = readI18n("account.usernameInvalid", I18nType.RWPP)
                        AccountSession.username.equals(name, ignoreCase = true) ->
                            error = readI18n("friends.cannotAddSelf", I18nType.RWPP)
                        else -> {
                            submitting = true
                            scope.launch {
                                runCatching { FriendsSession.sendRequest(name) }
                                    .onSuccess { req ->
                                        info = if (req.status == FriendRequestStatus.ACCEPTED) {
                                            readI18n("friends.requestAccepted", I18nType.RWPP)
                                        } else {
                                            readI18n("friends.requestSent", I18nType.RWPP)
                                        }
                                        error = ""
                                        submitting = false
                                        if (req.status == FriendRequestStatus.ACCEPTED) dismiss()
                                    }.onFailure { e ->
                                        error = (e as? AccountApiException)?.let { accountErrorText(it) }
                                            ?: e.message.orEmpty()
                                        submitting = false
                                    }
                            }
                        }
                    }
                }
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp).align(Alignment.CenterHorizontally),
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (isSmall) {
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

    val peer = FriendsSession.activePeer
    val messages = FriendsSession.messages
    val isSmall = LocalWindowManager.current == WindowManager.Small
    var draft by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val selfId = AccountSession.user?.id

    LaunchedEffect(Unit) {
        while (true) {
            if (AccountSession.networkEnabled && FriendsSession.activePeer != null) {
                runCatching { FriendsSession.pollMessages() }
            }
            delay(4_000)
        }
    }

    LaunchedEffect(messages.size) {
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
                        peer?.nickname?.ifBlank { peer.username } ?: readI18n("friends.chatTitle", I18nType.RWPP),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (peer != null) {
                        Text(
                            peer.username,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (FriendsSession.chatError.isNotBlank()) {
                Text(
                    FriendsSession.chatError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty()) {
                    Text(
                        readI18n("friends.noMessages", I18nType.RWPP),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    messages.forEach { message ->
                        ChatBubble(message, fromMe = message.senderId == selfId)
                    }
                }
            }

            val send = {
                val body = draft.trim()
                if (AccountFieldRules.isValidChatBody(body)) {
                    scope.launch {
                        if (FriendsSession.send(body)) {
                            draft = ""
                        }
                    }
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
                        onValueChange = { if (it.codePointCount(0, it.length) <= 2000) draft = it },
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
                        onValueChange = { if (it.codePointCount(0, it.length) <= 2000) draft = it },
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
private fun ChatBubble(message: ChatMessageDto, fromMe: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromMe) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.82f),
            horizontalAlignment = if (fromMe) Alignment.End else Alignment.Start,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (fromMe) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (fromMe) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Text(
                formatAccountTime(message.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

internal fun formatAccountTime(rfc3339: String): String {
    if (rfc3339.isBlank()) return ""
    val tIndex = rfc3339.indexOf('T')
    if (tIndex <= 0 || tIndex + 6 > rfc3339.length) return rfc3339
    val date = rfc3339.substring(0, tIndex)
    val time = rfc3339.substring(tIndex + 1, (tIndex + 6).coerceAtMost(rfc3339.length))
    return "$date $time"
}

