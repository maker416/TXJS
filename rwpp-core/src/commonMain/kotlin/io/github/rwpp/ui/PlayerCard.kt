/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.account.RoomIdentityController
import io.github.rwpp.account.RoomIdentityResult
import io.github.rwpp.account.accountErrorText
import io.github.rwpp.coil.AccountAvatar
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.Player
import io.github.rwpp.game.teamAlias
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.account.AccountApiException
import io.github.rwpp.net.account.AccountFieldRules
import io.github.rwpp.net.account.FriendRequestStatus
import io.github.rwpp.net.account.PublicUser
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.RWSingleOutlinedTextField
import io.github.rwpp.widget.WindowManager
import kotlinx.coroutines.launch

/**
 * 房间内成员个人名片弹窗：上半为游戏信息（玩家名/队伍/延迟/房主标记），
 * 下半按 [RoomIdentityResult] 渲染账号区（头像/昵称/在线状态/加好友/发私信）。
 * 账号数据由 [RoomIdentityController.resolvePlayer] 带外查询（relaymod 房间身份公示 + UAS 公开接口），
 * 不涉及游戏联机协议。
 */
@Composable
fun PlayerCardDialog(
    visible: Boolean,
    player: Player,
    room: GameRoom,
    onDismiss: () -> Unit,
) {
    AnimatedAlertDialog(visible, onDismissRequest = onDismiss) { dismiss ->
        val isSmall = LocalWindowManager.current == WindowManager.Small
        AccountAuthCard(scrollable = isSmall) {
            AccountDialogHeader(
                title = readI18n("playerCard.title", I18nType.RWPP),
                icon = {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                },
            )

            // —— 游戏区 ——
            PlayerCardGameSection(player)

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
            )

            // —— 账号区 ——
            PlayerCardAccountSection(player, room, dismiss)
        }
    }
}

/** 游戏区：玩家名（含房主标记）、队伍、延迟。 */
@Composable
private fun PlayerCardGameSection(player: Player) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            player.name,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (player.isRoomHost) {
            AccountStatusPill(readI18n("playerCard.hostBadge", I18nType.RWPP))
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            readI18n("playerCard.team", I18nType.RWPP, player.teamAlias()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
        Text(
            readI18n("playerCard.ping", I18nType.RWPP, player.ping),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
}

/** 账号区：按 [RoomIdentityResult] 分派渲染。 */
@Composable
private fun PlayerCardAccountSection(
    player: Player,
    room: GameRoom,
    dismiss: () -> Unit,
) {
    var result by remember(player) { mutableStateOf<RoomIdentityResult?>(null) }
    var retryNonce by remember(player) { mutableIntStateOf(0) }
    // 登录态变化（弹窗开着时完成了登录）也触发重新解析
    val loggedIn = AccountSession.loggedIn
    LaunchedEffect(player, retryNonce, loggedIn) {
        result = null
        result = RoomIdentityController.resolvePlayer(player, room)
    }

    when (val r = result) {
        null -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
            Text(
                readI18n("playerCard.loading", I18nType.RWPP),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        RoomIdentityResult.AiPlayer -> AccountMessageBanner(
            readI18n("playerCard.aiPlayer", I18nType.RWPP),
        )

        RoomIdentityResult.NotLoggedIn -> NotLoggedInSection(dismiss)

        RoomIdentityResult.FeatureUnavailable -> AccountMessageBanner(
            readI18n("playerCard.featureUnavailable", I18nType.RWPP),
        )

        RoomIdentityResult.Self -> SelfSection(dismiss)

        is RoomIdentityResult.Found -> FoundSection(r.user, r.online, dismiss)

        RoomIdentityResult.NotFound -> NotFoundSection()

        is RoomIdentityResult.Ambiguous -> AmbiguousSection(r.users)

        is RoomIdentityResult.Error -> Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AccountMessageBanner(r.message.ifBlank { "error" }, isError = true)
            AccountPrimaryButton(
                label = readI18n("playerCard.retry", I18nType.RWPP),
                onClick = { retryNonce++ },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 未登录：提示 + 去登录（账号页渲染在房间页上层，先关掉名片避免弹窗压在账号页上）。 */
@Composable
private fun NotLoggedInSection(dismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AccountMessageBanner(readI18n("playerCard.notLoggedIn", I18nType.RWPP))
        AccountPrimaryButton(
            label = readI18n("friends.goLogin", I18nType.RWPP),
            onClick = {
                dismiss()
                UI.showAccountView = true
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 点的是自己：展示自己的账号卡（未登录时按未登录处理）。 */
@Composable
private fun SelfSection(dismiss: () -> Unit) {
    val user = AccountSession.user
    if (!AccountSession.loggedIn || user == null) {
        NotLoggedInSection(dismiss)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayerCardUserRow(
            user = PublicUser(user.id, user.username, user.nickname, hasAvatar = user.hasAvatar),
            online = null,
        )
        AccountMessageBanner(readI18n("playerCard.self", I18nType.RWPP))
    }
}

/** 命中唯一账号：名片 + 加好友 / 已是好友则发私信。 */
@Composable
private fun FoundSection(user: PublicUser, online: Boolean, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val isFriend = FriendsSession.friends.any { it.user.id == user.id }
    val isSelf = AccountSession.username.equals(user.username, ignoreCase = true)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayerCardUserRow(user, online)
        when {
            isSelf -> AccountMessageBanner(readI18n("playerCard.self", I18nType.RWPP))
            isFriend -> AccountPrimaryButton(
                label = readI18n("playerCard.sendMessage", I18nType.RWPP),
                onClick = {
                    dismiss()
                    UI.showFriendsView = true
                    scope.launch { runCatching { FriendsSession.openChat(user) } }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            else -> AddFriendButton(
                username = user.username,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 未命中：说明 + 手动输入账号用户名加好友（仿 AddFriendDialog）。 */
@Composable
private fun NotFoundSection() {
    var username by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AccountMessageBanner(readI18n("playerCard.notFound", I18nType.RWPP))
        RWSingleOutlinedTextField(
            label = readI18n("account.username", I18nType.RWPP),
            value = username,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            onValueChange = {
                username = it
                error = ""
            },
        )
        if (error.isNotBlank()) {
            AccountMessageBanner(error, isError = true)
        }
        AddFriendButton(
            username = username,
            normalizeAndValidate = true,
            onInvalid = { error = it },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 同名多候选：身份不可信提示 + 候选列表（逐条查看 / 加好友）。 */
@Composable
private fun AmbiguousSection(users: List<PublicUser>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AccountMessageBanner(readI18n("playerCard.ambiguous", I18nType.RWPP), isError = true)
        users.forEach { user ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AccountAvatarBox(
                    user.nickname.ifBlank { user.username },
                    size = 36.dp,
                    avatar = AccountAvatar(user.id, user.hasAvatar, AccountSession.avatarVersion),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        user.nickname.ifBlank { user.username },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "@${user.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AddFriendButton(username = user.username, compact = true)
            }
        }
    }
}

/** 名片行：头像 + 昵称 + @username + 在线状态胶囊。 */
@Composable
private fun PlayerCardUserRow(user: PublicUser, online: Boolean?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccountAvatarBox(
            user.nickname.ifBlank { user.username },
            size = 52.dp,
            avatar = AccountAvatar(user.id, user.hasAvatar, AccountSession.avatarVersion),
            online = online,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                user.nickname.ifBlank { user.username },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "@${user.username}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (online != null) {
            AccountStatusPill(
                text = readI18n(
                    if (online) "friends.online" else "friends.offline",
                    I18nType.RWPP,
                ),
                dotColor = if (online) Color(95, 190, 95) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
        }
    }
}

private enum class AddFriendState { Idle, Sending, Sent, Accepted }

/**
 * 加好友按钮（含发送中 / 已发送 / 已互加 / 失败展示）。
 * [normalizeAndValidate] 为 true 时先按账号用户名规则归一/校验（手动输入场景），失败经 [onInvalid] 回报。
 */
@Composable
private fun AddFriendButton(
    username: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    normalizeAndValidate: Boolean = false,
    onInvalid: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var state by remember(username) { mutableStateOf(AddFriendState.Idle) }
    var error by remember(username) { mutableStateOf("") }

    val send: () -> Unit = {
        val name = if (normalizeAndValidate) AccountFieldRules.normalizeUsername(username) else username.trim()
        when {
            name.isBlank() -> onInvalid(readI18n("account.usernameInvalid", I18nType.RWPP))
            normalizeAndValidate && !AccountFieldRules.isValidUsername(name) ->
                onInvalid(readI18n("account.usernameInvalid", I18nType.RWPP))
            AccountSession.username.equals(name, ignoreCase = true) ->
                error = readI18n("friends.cannotAddSelf", I18nType.RWPP)
            else -> {
                state = AddFriendState.Sending
                error = ""
                scope.launch {
                    runCatching { FriendsSession.sendRequest(name) }
                        .onSuccess { req ->
                            state = if (req.status == FriendRequestStatus.ACCEPTED) {
                                AddFriendState.Accepted
                            } else {
                                AddFriendState.Sent
                            }
                        }
                        .onFailure { e ->
                            error = (e as? AccountApiException)?.let { accountErrorText(it) }
                                ?: e.message.orEmpty()
                            state = AddFriendState.Idle
                        }
                }
            }
        }
    }

    Column(
        modifier = if (compact) Modifier else modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (state) {
            AddFriendState.Accepted -> AccountMessageBanner(
                readI18n("friends.requestAccepted", I18nType.RWPP),
            )
            else -> {
                if (state == AddFriendState.Sent) {
                    AccountMessageBanner(readI18n("friends.requestSent", I18nType.RWPP))
                }
                if (error.isNotBlank()) {
                    AccountMessageBanner(error, isError = true)
                }
                val label = readI18n("playerCard.addFriend", I18nType.RWPP)
                val enabled = state != AddFriendState.Sending && state != AddFriendState.Sent
                if (compact) {
                    AccountCompactButton(label = label, onClick = send, enabled = enabled)
                } else {
                    AccountPrimaryButton(
                        label = label,
                        onClick = send,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
