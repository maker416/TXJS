/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.account.RelativeTime
import io.github.rwpp.account.accountErrorText
import io.github.rwpp.coil.AccountAvatar
import io.github.rwpp.config.Settings
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
import io.github.rwpp.net.account.ROOM_INVITE_PREFIX
import io.github.rwpp.net.account.RoomInvite
import io.github.rwpp.net.account.RoomInviteCodec
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.projectVersion
import io.github.rwpp.utils.compareVersions
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.block_30
import io.github.rwpp.rwpp_core.generated.resources.group_30
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.LargeProportion
import io.github.rwpp.widget.RWOutlinedTextColors
import io.github.rwpp.widget.RWSingleOutlinedTextField
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.v2.RWIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject

/**
 * 好友主页：微信式布局——左侧好友列表，右侧聊天内容。
 * 小屏（[WindowManager.Small]）退化为单栏：列表与聊天之间滑动切换。
 */
@Composable
fun FriendsView(
    onExit: () -> Unit,
    onGoLogin: () -> Unit,
    initiallyShowAdd: Boolean = false,
) {
    val windowManager = LocalWindowManager.current
    val isSmall = windowManager == WindowManager.Small
    BackHandler(true) {
        // 小屏单栏时，打开着聊天则先返回列表，否则退出好友页
        if (isSmall && FriendsSession.activePeer != null) {
            FriendsSession.closeChat()
        } else {
            onExit()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("friends").broadcastIn()
        }
    }

    val scope = rememberCoroutineScope()
    val loggedIn = AccountSession.loggedIn
    var showAdd by remember { mutableStateOf(initiallyShowAdd) }
    var versionMismatchInvite by remember { mutableStateOf<RoomInvite?>(null) }

    /** 接受邀请：交由多人页消费 pendingInviteJoin 走既有 Loading/directJoinServer 链路。 */
    val proceedJoinInvite: (RoomInvite) -> Unit = { invite ->
        UI.pendingInviteJoin = invite
        FriendsSession.closeChat()
        UI.showFriendsView = false
        UI.showMultiplayerView = true
    }

    val onJoinInvite: (RoomInvite) -> Unit = { invite ->
        when {
            UI.showRoomView -> UI.showWarning(readI18n("friends.inviteLeaveRoomFirst", I18nType.RWPP))
            invite.version.isNotBlank() && compareVersions(invite.version, projectVersion) != 0 ->
                versionMismatchInvite = invite
            else -> proceedJoinInvite(invite)
        }
    }

    LaunchedEffect(Unit) {
        AccountSession.restoreIfNeeded()
        runCatching { FriendsSession.refreshLists() }
        while (true) {
            delay(8_000)
            runCatching { FriendsSession.refreshLists() }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (AccountSession.networkEnabled && FriendsSession.activePeer != null) {
                runCatching { FriendsSession.pollMessages() }
            }
            delay(4_000)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Card(
            shape = RectangleShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.background
                    .copy((UI.backgroundTransparency + 0.2f).coerceAtMost(1f)),
            ),
            modifier = Modifier
                // 必须垫在 fillMaxHeight 前面：先扣掉键盘高度，卡片才能坐在键盘上方。
                .settledImeAvoidance()
                .fillMaxHeight()
                .fillMaxWidth(if (isSmall) 0.95f else 0.88f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!loggedIn) {
                    FriendsLoginRequired(onGoLogin = onGoLogin)
                } else if (isSmall) {
                    // 小屏：列表 ↔ 聊天 单栏滑动切换（微信手机版交互）
                    val chatOpen = FriendsSession.activePeer != null
                    AnimatedContent(
                        targetState = chatOpen,
                        transitionSpec = {
                            if (targetState) {
                                (slideInHorizontally(tween(280)) { it } + fadeIn(tween(280)))
                                    .togetherWith(slideOutHorizontally(tween(280)) { -it } + fadeOut(tween(280)))
                            } else {
                                (slideInHorizontally(tween(280)) { -it } + fadeIn(tween(280)))
                                    .togetherWith(slideOutHorizontally(tween(280)) { it } + fadeOut(tween(280)))
                            }
                        },
                        label = "friendsListChat",
                    ) { open ->
                        if (open) {
                            FriendChatPane(
                                showBack = true,
                                onBack = { FriendsSession.closeChat() },
                                onJoinInvite = onJoinInvite,
                            )
                        } else {
                            FriendListPane(
                                onAdd = { showAdd = true },
                                onOpenChat = { peer ->
                                    scope.launch { runCatching { FriendsSession.openChat(peer) } }
                                },
                            )
                        }
                    }
                } else {
                    // 中大窗口：左侧列表 + 右侧聊天（微信桌面版布局）
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .width(if (windowManager == WindowManager.Large) 320.dp else 280.dp)
                                .fillMaxHeight(),
                        ) {
                            FriendListPane(
                                onAdd = { showAdd = true },
                                onOpenChat = { peer ->
                                    scope.launch { runCatching { FriendsSession.openChat(peer) } }
                                },
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)),
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            FriendChatPane(showBack = false, onBack = {}, onJoinInvite = onJoinInvite)
                        }
                    }
                }
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    ExitButton(onExit)
                }
            }
        }
    }

    AddFriendDialog(
        visible = showAdd,
        onDismiss = { showAdd = false },
    )

    // 邀请方与本机版本不一致时的确认（仍允许尝试加入）
    InviteVersionMismatchDialog(
        invite = versionMismatchInvite,
        onJoinAnyway = proceedJoinInvite,
        onDismiss = { versionMismatchInvite = null },
    )
}

/** 未登录占位：引导跳转用户主页登录。 */
@Composable
private fun FriendsLoginRequired(onGoLogin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(Res.drawable.group_30),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            readI18n("friends.loginRequiredTitle", I18nType.RWPP),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            readI18n("friends.loginRequiredBody", I18nType.RWPP),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        AccountPrimaryButton(
            label = readI18n("friends.goLogin", I18nType.RWPP),
            onClick = onGoLogin,
            leadingIcon = {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
    }
}

/** 左侧栏：标题 + 加好友入口 + 申请区 + 好友列表。 */
@Composable
private fun FriendListPane(
    onAdd: () -> Unit,
    onOpenChat: (PublicUser) -> Unit,
) {
    val friends = FriendsSession.friends
    val incoming = FriendsSession.incoming
    val outgoing = FriendsSession.outgoing
    val scope = rememberCoroutineScope()
    val enableAnimations = koinInject<Settings>().enableAnimations
    var showBlocked by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 52.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.group_30),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                readI18n("friends.title", I18nType.RWPP),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (FriendsSession.loadingLists) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
                    .clickable { showBlocked = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.block_30),
                    contentDescription = readI18n("friends.blockedList", I18nType.RWPP),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = readI18n("friends.add", I18nType.RWPP),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)),
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (incoming.isNotEmpty()) {
                item(key = "incomingHeader") {
                    FriendListSectionLabel(readI18n("friends.incoming", I18nType.RWPP))
                }
                items(incoming, key = { "in_${it.id}" }) { req ->
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
                item(key = "outgoingHeader") {
                    FriendListSectionLabel(readI18n("friends.outgoing", I18nType.RWPP))
                }
                items(outgoing, key = { "out_${it.id}" }) { req ->
                    FriendRequestRow(
                        request = req,
                        incoming = false,
                        onAccept = {},
                        onReject = {},
                        onCancel = { scope.launch { runCatching { FriendsSession.cancel(req.id) } } },
                    )
                }
            }

            if (friends.isEmpty()) {
                item(key = "emptyFriends") {
                    Text(
                        readI18n("friends.empty", I18nType.RWPP),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                items(friends, key = { "friend_${it.user.id}" }) { item ->
                    FriendRow(
                        user = item.user,
                        online = item.online,
                        avatar = AccountAvatar(item.user.id, item.user.hasAvatar, AccountSession.avatarVersion),
                        // 邀请消息的报文原文不能见人：列表摘要换成占位文案
                        preview = FriendsSession.lastMessagePreview(item.user.id)?.let { body ->
                            if (body.startsWith(ROOM_INVITE_PREFIX)) {
                                readI18n("friends.inviteSummary", I18nType.RWPP)
                            } else {
                                body
                            }
                        },
                        unread = FriendsSession.unreadOf(item.user.id),
                        selected = FriendsSession.activePeer?.id == item.user.id,
                        modifier = if (enableAnimations) Modifier.animateItem() else Modifier,
                        onClick = { onOpenChat(item.user) },
                        onDelete = {
                            scope.launch { runCatching { FriendsSession.deleteFriend(item.user.id) } }
                        },
                        onBlock = {
                            scope.launch { runCatching { FriendsSession.block(item.user.id) } }
                        },
                    )
                }
            }
        }
    }

    BlockedUsersDialog(
        visible = showBlocked,
        onDismiss = { showBlocked = false },
    )
}

@Composable
private fun FriendListSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** 右侧栏：聊天头部 + 消息流 + 输入行；无选中好友时显示占位。 */
@Composable
private fun FriendChatPane(
    showBack: Boolean,
    onBack: () -> Unit,
    onJoinInvite: (RoomInvite) -> Unit,
) {
    val peer = FriendsSession.activePeer
    val messages = FriendsSession.messages
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selfId = AccountSession.user?.id
    val enableAnimations = koinInject<Settings>().enableAnimations

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (peer == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.group_30),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    readI18n("friends.selectToChat", I18nType.RWPP),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        val peerName = peer.nickname.ifBlank { peer.username }
        val presence = FriendsSession.presenceOf(peer.id)
        val lastActiveAt = presence?.lastActiveAt
        // 副标题显示在线状态：在线绿字；离线按 lastActiveAt 折算相对时间；解析失败回退「离线」
        val (presenceText, presenceColor) = when {
            presence?.online == true ->
                readI18n("friends.online", I18nType.RWPP) to Color(95, 190, 95)
            lastActiveAt != null -> {
                val elapsed = RelativeTime.elapsed(lastActiveAt, System.currentTimeMillis())
                val text = when (elapsed?.second) {
                    RelativeTime.Unit.JUST_NOW -> readI18n("friends.lastActiveJustNow", I18nType.RWPP)
                    RelativeTime.Unit.MINUTES ->
                        readI18n("friends.lastActiveMinutes", I18nType.RWPP, elapsed.first.toString())
                    RelativeTime.Unit.HOURS ->
                        readI18n("friends.lastActiveHours", I18nType.RWPP, elapsed.first.toString())
                    RelativeTime.Unit.DAYS ->
                        readI18n("friends.lastActiveDays", I18nType.RWPP, elapsed.first.toString())
                    null -> readI18n("friends.offline", I18nType.RWPP)
                }
                text to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            }
            else ->
                readI18n("friends.offline", I18nType.RWPP) to
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .dismissImeOnTap()
                .padding(start = 12.dp, end = 48.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showBack) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = readI18n("friends.backToList", I18nType.RWPP),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            AccountAvatarBox(
                peerName,
                size = 38.dp,
                avatar = AccountAvatar(peer.id, peer.hasAvatar, AccountSession.avatarVersion),
                online = presence?.online,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    peerName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    presenceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = presenceColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)),
        )

        if (FriendsSession.chatError.isNotBlank()) {
            AccountMessageBanner(
                FriendsSession.chatError,
                isError = true,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().dismissImeOnTap(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 14.dp,
                vertical = 10.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item(key = "emptyMessages") {
                    Text(
                        readI18n("friends.noMessages", I18nType.RWPP),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                items(messages, key = { it.id }) { message ->
                    val fromMe = message.senderId == selfId
                    // 房间邀请消息渲染为卡片，普通文本照旧气泡
                    val invite = remember(message.body) { RoomInviteCodec.decode(message.body) }
                    Box(modifier = if (enableAnimations) Modifier.animateItem() else Modifier) {
                        if (invite != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (fromMe) Arrangement.End else Arrangement.Start,
                            ) {
                                RoomInviteCard(
                                    invite = invite,
                                    fromMe = fromMe,
                                    modifier = Modifier.fillMaxWidth(0.82f),
                                    onJoin = if (fromMe) null else ({ onJoinInvite(invite) }),
                                )
                            }
                        } else {
                            ChatBubble(message, fromMe = fromMe)
                        }
                    }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 与等待房间紧凑输入框相同：普通 OutlinedTextField。
            // 不要用 RWSingleOutlinedTextField（weight + IntrinsicSize），不要在获焦时改系统栏。
            // 键盘避让在外层卡片的 settledImeAvoidance：弹出过程中绝不重排。
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.codePointCount(0, it.length) <= 2000) draft = it },
                placeholder = {
                    Text(
                        readI18n("friends.inputHint", I18nType.RWPP),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                singleLine = true,
                colors = RWOutlinedTextColors,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
            )
            // 有可发送内容时发送按钮染色，给明确可点反馈
            val canSend = AccountFieldRules.isValidChatBody(draft.trim())
            val sendTint by animateColorAsState(
                if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceTint,
                animationSpec = tween(200),
                label = "sendTint",
            )
            RWIconButton(Icons.Default.Send, size = 50.dp, tint = sendTint) { send() }
        }
    }
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AccountAvatarBox(
                other.nickname.ifBlank { other.username },
                size = 42.dp,
                avatar = AccountAvatar(other.id, other.hasAvatar, AccountSession.avatarVersion),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    other.nickname.ifBlank { other.username },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "@${other.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (incoming) {
                AccountCompactButton(
                    label = readI18n("friends.accept", I18nType.RWPP),
                    onClick = onAccept,
                )
                Text(
                    readI18n("friends.reject", I18nType.RWPP),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable(onClick = onReject).padding(4.dp),
                )
            } else {
                TextButton(onClick = onCancel) {
                    Text(
                        readI18n("friends.cancel", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendRow(
    user: PublicUser,
    online: Boolean,
    avatar: AccountAvatar?,
    preview: String?,
    unread: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    // 选中态颜色渐变过渡
    val containerColor by animateColorAsState(
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
        },
        animationSpec = tween(200),
        label = "friendRowBg",
    )
    val borderColor by animateColorAsState(
        if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f)
        },
        animationSpec = tween(200),
        label = "friendRowBorder",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccountAvatarBox(
                user.nickname.ifBlank { user.username },
                size = 44.dp,
                avatar = avatar,
                online = online,
            )
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
            Icon(
                painter = painterResource(Res.drawable.block_30),
                contentDescription = readI18n("friends.block", I18nType.RWPP),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(20.dp)
                    .clickable { confirmBlock = true },
            )
            Icon(
                Icons.Default.Delete,
                contentDescription = readI18n("friends.delete", I18nType.RWPP),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                modifier = Modifier
                    .size(22.dp)
                    .clickable { confirmDelete = true },
            )
        }
    }
    if (confirmDelete) {
        AnimatedAlertDialog(
            visible = true,
            onDismissRequest = { confirmDelete = false },
        ) { dismiss ->
            AccountAuthCard(scrollable = false) {
                AccountDialogHeader(
                    title = readI18n("friends.deleteConfirmTitle", I18nType.RWPP),
                    subtitle = readI18n("friends.deleteConfirmBody", I18nType.RWPP),
                    iconTint = MaterialTheme.colorScheme.error,
                    icon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) {
                        Text(
                            readI18n("common.cancel", I18nType.RWPP),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
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
    if (confirmBlock) {
        AnimatedAlertDialog(
            visible = true,
            onDismissRequest = { confirmBlock = false },
        ) { dismiss ->
            AccountAuthCard(scrollable = false) {
                AccountDialogHeader(
                    title = readI18n("friends.blockConfirmTitle", I18nType.RWPP),
                    subtitle = readI18n("friends.blockConfirmBody", I18nType.RWPP),
                    iconTint = MaterialTheme.colorScheme.error,
                    icon = {
                        Icon(
                            painter = painterResource(Res.drawable.block_30),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) {
                        Text(
                            readI18n("common.cancel", I18nType.RWPP),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    TextButton(
                        onClick = {
                            onBlock()
                            dismiss()
                        },
                    ) {
                        Text(
                            readI18n("friends.block", I18nType.RWPP),
                            color = MaterialTheme.colorScheme.error,
                        )
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
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
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
        AccountAuthCard(scrollable = isSmall) {
            AccountDialogHeader(
                title = readI18n("friends.addTitle", I18nType.RWPP),
                subtitle = readI18n("friends.addHint", I18nType.RWPP),
                icon = {
                    Icon(
                        painter = painterResource(Res.drawable.group_30),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                },
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
                AccountMessageBanner(info)
            }
            if (error.isNotBlank()) {
                AccountMessageBanner(error, isError = true)
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
            } else {
                AccountPrimaryButton(
                    label = readI18n("friends.add", I18nType.RWPP),
                    onClick = confirm,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = dismiss,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        readI18n("common.cancel", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/** 黑名单弹窗：列出已拉黑用户，可解除拉黑（成功后 FriendsSession 自动刷新列表）。 */
@Composable
private fun BlockedUsersDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
    ) {
        AccountAuthCard(scrollable = false) {
            AccountDialogHeader(
                title = readI18n("friends.blockedList", I18nType.RWPP),
                icon = {
                    Icon(
                        painter = painterResource(Res.drawable.block_30),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                },
            )
            val blocks = FriendsSession.blocks
            if (blocks.isEmpty()) {
                Text(
                    readI18n("friends.blockedEmpty", I18nType.RWPP),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(blocks, key = { it.user.id }) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AccountAvatarBox(
                                item.user.nickname.ifBlank { item.user.username },
                                size = 40.dp,
                                avatar = AccountAvatar(
                                    item.user.id,
                                    item.user.hasAvatar,
                                    AccountSession.avatarVersion,
                                ),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.user.nickname.ifBlank { item.user.username },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "@${item.user.username}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            AccountCompactButton(
                                label = readI18n("friends.unblock", I18nType.RWPP),
                                onClick = {
                                    scope.launch { runCatching { FriendsSession.unblock(item.user.id) } }
                                },
                            )
                        }
                    }
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
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (fromMe) 16.dp else 4.dp,
                            bottomEnd = if (fromMe) 4.dp else 16.dp,
                        )
                    )
                    .background(
                        if (fromMe) {
                            MaterialTheme.colorScheme.primary
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
                        MaterialTheme.colorScheme.onPrimary
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

/** 输入法弹出动画结束前不要改布局，否则鸿蒙会在显示完成前丢掉焦点。 */
private const val IME_SETTLE_MS = 300L

/**
 * 等输入法高度稳定后再把底部垫起来，让输入行落到键盘上方。
 * 弹出过程中保持原布局；窗口已经 adjustResize 掉的高度要扣掉，避免叠两次。
 */
@Composable
private fun Modifier.settledImeAvoidance(): Modifier {
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val containerHeight = LocalWindowInfo.current.containerSize.height
    var reservedPx by remember { mutableIntStateOf(0) }
    var baselineHeight by remember { mutableIntStateOf(0) }

    LaunchedEffect(imeBottomPx, containerHeight) {
        if (imeBottomPx <= 0) {
            if (containerHeight > 0) baselineHeight = containerHeight
            reservedPx = 0
            return@LaunchedEffect
        }
        delay(IME_SETTLE_MS)
        val alreadyResized = if (baselineHeight > 0) {
            (baselineHeight - containerHeight).coerceAtLeast(0)
        } else {
            0
        }
        reservedPx = (imeBottomPx - alreadyResized).coerceAtLeast(0)
    }

    val reserved = reservedPx
    return if (reserved > 0) {
        padding(bottom = with(density) { reserved.toDp() })
    } else {
        this
    }
}

/**
 * 点在消息区或标题栏的空白处时收起输入法。
 * 不用 [clickable]：Compose 1.10 的 clickable 在软键盘弹出后会变成可聚焦节点，抢走输入框焦点。
 */
@Composable
private fun Modifier.dismissImeOnTap(): Modifier {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    return pointerInput(keyboard, focusManager) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = true)
            if (waitForUpOrCancellation() != null) {
                keyboard?.hide()
                focusManager.clearFocus()
            }
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
