/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.sendChatMessageOrCommand
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.account.PublicUser
import io.github.rwpp.net.account.ROOM_INVITE_TTL_MS
import io.github.rwpp.net.account.RoomInvite
import io.github.rwpp.net.account.RoomInviteCodec
import io.github.rwpp.projectVersion
import io.github.rwpp.utils.compareVersions
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.swords_30
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.LargeProportion
import io.github.rwpp.widget.RWSingleOutlinedTextField
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/** 房间邀请策略控制消息前缀：房主通过房间聊天广播；RWPP 端识别后抑制显示，原版玩家会看到一行短文本。 */
const val INVITE_POLICY_CONTROL_PREFIX = "[RWJSCTL1]"

/**
 * 房间邀请策略（仅本进程状态，离开房间时重置为允许）。
 *
 * 邀请走账号私信带外通道，游戏联机协议无法硬阻断；房主的开关经房间聊天广播控制消息，
 * 成员的 RWPP 客户端收到后隐藏邀请入口，属于软约束（旧版/改版客户端不受限）。
 */
object RoomInvitePolicy {
    /** 成员是否被允许发起邀请；房主自身不受此限制。 */
    var membersCanInvite by mutableStateOf(true)
        private set

    fun controlMessage(allow: Boolean): String =
        "$INVITE_POLICY_CONTROL_PREFIX invite=$allow"

    /** 房主切换策略：本地生效并向全房广播。 */
    fun setByHost(room: GameRoom, allow: Boolean) {
        membersCanInvite = allow
        room.sendChatMessageOrCommand(controlMessage(allow))
    }

    /** 离开房间时重置为默认允许。 */
    fun reset() {
        membersCanInvite = true
    }

    /**
     * 识别并应用控制消息；命中（聊天里应抑制显示）返回 true。
     * 房主收到自己广播的回显时重复应用同值，无副作用。
     *
     * 调用方必须先校验发送者身份（仅 `Player.isRoomHost` 的消息才应传到这里），
     * 否则成员可手打 `[RWJSCTL1] invite=true` 绕过房主禁令。
     */
    fun handleControlMessage(message: String): Boolean {
        if (!message.startsWith(INVITE_POLICY_CONTROL_PREFIX)) return false
        when (message.removePrefix(INVITE_POLICY_CONTROL_PREFIX).trim()) {
            "invite=true" -> membersCanInvite = true
            "invite=false" -> membersCanInvite = false
        }
        return true
    }
}

/**
 * 房间邀请卡片：在好友聊天流中替代普通气泡渲染，也可作为邀请弹窗内的预览。
 *
 * @param fromMe 自己发出的邀请（按钮替换为等待提示）
 * @param onJoin 点击「立即加入」；为 null 时仅展示（预览态）
 */
@Composable
fun RoomInviteCard(
    invite: RoomInvite,
    fromMe: Boolean,
    modifier: Modifier = Modifier,
    onJoin: (() -> Unit)? = null,
) {
    val expired = remember(invite) { RoomInviteCodec.isExpired(invite, System.currentTimeMillis()) }
    Surface(
        modifier = modifier.alpha(if (expired) 0.55f else 1f),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    readI18n("friends.inviteCardTitle", I18nType.RWPP),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                val code = invite.code
                if (!code.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            code,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {}
                    Icon(
                        painter = painterResource(Res.drawable.swords_30),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        invite.map.ifBlank { "-" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        readI18n("friends.inviteInviter", I18nType.RWPP, invite.inviter),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        readI18n(
                            "friends.inviteMeta", I18nType.RWPP,
                            invite.players, invite.mods.toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            when {
                expired -> Text(
                    readI18n("friends.inviteExpired", I18nType.RWPP),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                fromMe -> Text(
                    readI18n("friends.inviteWaiting", I18nType.RWPP),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                onJoin != null -> AccountPrimaryButton(
                    label = readI18n("friends.inviteJoin", I18nType.RWPP),
                    onClick = onJoin,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                    },
                )
            }

            if (!expired) {
                Text(
                    readI18n(
                        "friends.inviteValidMinutes", I18nType.RWPP,
                        (ROOM_INVITE_TTL_MS / 60_000L).toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
        }
    }
}

/**
 * 邀请好友弹窗：房间摘要预览 + 好友多选 + 发送。
 *
 * @param invite 打开时快照的邀请负载；为 null 表示当前房间无法生成邀请（如无地址）
 * @param onGoLogin 未登录时点击「去登录」
 * @param onSent 全部发送成功后回调受邀好友显示名列表（供调用方在房间聊天发系统消息）
 */
@Composable
fun InviteFriendsDialog(
    visible: Boolean,
    invite: RoomInvite?,
    onGoLogin: () -> Unit,
    onSent: (names: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 「去登录」不能边退弹窗边跳账号页（退出动画 ~500ms 内弹窗仍盖住登录页），
    // 等退出动画结束 onDismissRequest 回调后再跳转
    var goLoginOnDismiss by remember { mutableStateOf(false) }

    AnimatedAlertDialog(
        visible,
        onDismissRequest = {
            onDismiss()
            if (goLoginOnDismiss) {
                goLoginOnDismiss = false
                onGoLogin()
            }
        },
    ) { dismiss ->
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf("") }

    LaunchedEffect(visible) {
        if (visible) {
            search = ""
            selected.clear()
            sendError = ""
            FriendsSession.refreshLists()
        }
    }

    BorderCard(
        modifier = Modifier
            .fillMaxWidth(LargeProportion())
            .widthIn(max = 480.dp)
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                readI18n("multiplayer.room.inviteFriends", I18nType.RWPP),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )

            if (invite != null) {
                RoomInviteCard(invite = invite, fromMe = true)
            }

            when {
                !AccountSession.loggedIn -> {
                    AccountMessageBanner(readI18n("multiplayer.room.inviteNeedLogin", I18nType.RWPP))
                    AccountPrimaryButton(
                        label = readI18n("friends.goLogin", I18nType.RWPP),
                        onClick = { goLoginOnDismiss = true; dismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                invite == null -> {
                    AccountMessageBanner(
                        readI18n("multiplayer.room.inviteNoAddress", I18nType.RWPP),
                        isError = true,
                    )
                }
                else -> {
                    RWSingleOutlinedTextField(
                        label = readI18n("friends.searchHint", I18nType.RWPP),
                        value = search,
                        modifier = Modifier.fillMaxWidth(),
                        onValueChange = { search = it },
                    )

                    val friends = FriendsSession.friends
                        .filter {
                            search.isBlank() ||
                                it.user.nickname.contains(search, true) ||
                                it.user.username.contains(search, true)
                        }
                        .sortedBy { (it.user.nickname.ifBlank { it.user.username }).lowercase() }

                    if (friends.isEmpty()) {
                        Text(
                            readI18n("friends.empty", I18nType.RWPP),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(friends.size, key = { friends[it].user.id }) { index ->
                                val friend = friends[index].user
                                val name = friend.nickname.ifBlank { friend.username }
                                val isSelected = friend.id in selected
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (isSelected) selected.remove(friend.id)
                                            else selected.add(friend.id)
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                    } else {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f)
                                        },
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        AccountAvatarBox(name, size = 38.dp, showOnlineDot = false)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                "@${friend.username}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                            },
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (sendError.isNotBlank()) {
                        AccountMessageBanner(sendError, isError = true)
                    }

                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp).align(Alignment.CenterHorizontally),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        AccountPrimaryButton(
                            label = readI18n(
                                "multiplayer.room.inviteSend", I18nType.RWPP,
                                selected.size.toString(),
                            ),
                            onClick = {
                                val body = RoomInviteCodec.encode(invite)
                                val targets = FriendsSession.friends
                                    .filter { it.user.id in selected }
                                scope.launch {
                                    sending = true
                                    val okIds = mutableListOf<Long>()
                                    val okNames = mutableListOf<String>()
                                    var failed = 0
                                    targets.forEach {
                                        if (FriendsSession.sendTo(it.user.id, body)) {
                                            okIds += it.user.id
                                            okNames += it.user.nickname.ifBlank { it.user.username }
                                        } else {
                                            failed++
                                        }
                                    }
                                    sending = false
                                    // 部分成功语义：已送达的从选中移除（重试不会重复发）并在房间内播报；
                                    // 失败者保留选中，弹窗内提示后可原地重试
                                    selected.removeAll(okIds.toSet())
                                    if (okNames.isNotEmpty()) onSent(okNames)
                                    if (failed == 0) {
                                        dismiss()
                                    } else {
                                        sendError = readI18n(
                                            "multiplayer.room.inviteSendFailed", I18nType.RWPP,
                                            failed.toString(),
                                        )
                                    }
                                }
                            },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
    }
}

// ---------------------------------------------------------------------------
// 全局悬浮邀请卡片：收到好友邀请私信时，在主菜单/多人列表等界面（房间与对局除外）
// 从屏幕右侧滑入，底部进度条倒计时，超时自动忽略。
// ---------------------------------------------------------------------------

/** 悬浮邀请卡片的展示时长：超时未操作自动忽略。 */
private const val INVITE_TOAST_DURATION_MS = 10_000

/**
 * 一条待展示的邀请悬浮通知。
 *
 * @property invite 邀请负载
 * @property peer 邀请人（点击卡片跳转私聊会话用）
 * @property messageId 私信 id（去重与超时判定时校验，避免误清掉更新的通知）
 */
class RoomInviteNotification(
    val invite: RoomInvite,
    val peer: PublicUser,
    val messageId: Long,
)

/**
 * 邀请悬浮卡片宿主：挂在 App 根布局（屏幕右侧）。
 * 读取 [UI.incomingInviteNotification]，负责滑入滑出、版本不符确认与跳转动作。
 */
@Composable
fun RoomInviteToastHost(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var versionMismatchInvite by remember { mutableStateOf<RoomInvite?>(null) }

    // 进入等待房间/对局后立刻收起：房间内无法接受其他房间的邀请
    LaunchedEffect(UI.showRoomView) {
        if (UI.showRoomView) UI.incomingInviteNotification = null
    }

    /** 接受邀请：关闭其他顶层页，交由多人页消费 pendingInviteJoin 走既有加入链路。 */
    val join: (RoomInvite) -> Unit = { invite ->
        UI.incomingInviteNotification = null
        FriendsSession.closeChat()
        UI.pendingInviteJoin = invite
        UI.showFriendsView = false
        UI.showAccountView = false
        UI.showSettingsView = false
        UI.showModsView = false
        UI.showMissionView = false
        UI.showSurvivalView = false
        UI.showSinglePlayerView = false
        UI.showReplayView = false
        UI.showExtensionView = false
        UI.showResourceBrowser = false
        UI.showOpenSourceInfoView = false
        UI.showMultiplayerView = true
    }

    // 退出动画期间状态已置 null，缓存最后一条内容保证卡片能完整滑出
    var rendered by remember { mutableStateOf<RoomInviteNotification?>(null) }
    val current = UI.incomingInviteNotification
    if (current != null) rendered = current

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = current != null,
            enter = slideInHorizontally(tween(280)) { it } + fadeIn(tween(280)),
            exit = slideOutHorizontally(tween(280)) { it } + fadeOut(tween(280)),
        ) {
            rendered?.let { notification ->
                RoomInviteToastCard(
                    notification = notification,
                    onAccept = {
                        // 无论走哪条分支，悬浮卡片都先消失
                        UI.incomingInviteNotification = null
                        when {
                            UI.showRoomView ->
                                UI.showWarning(readI18n("friends.inviteLeaveRoomFirst", I18nType.RWPP))
                            notification.invite.version.isNotBlank() &&
                                compareVersions(notification.invite.version, projectVersion) != 0 ->
                                versionMismatchInvite = notification.invite
                            else -> join(notification.invite)
                        }
                    },
                    onOpenChat = {
                        UI.incomingInviteNotification = null
                        UI.showFriendsView = true
                        scope.launch {
                            runCatching { FriendsSession.openChat(notification.peer) }
                        }
                    },
                    onTimeout = {
                        // 只清掉自己这条，避免误清倒计时期间到来的新邀请
                        if (UI.incomingInviteNotification?.messageId == notification.messageId) {
                            UI.incomingInviteNotification = null
                        }
                    },
                )
            }
        }
    }

    InviteVersionMismatchDialog(
        invite = versionMismatchInvite,
        onJoinAnyway = join,
        onDismiss = { versionMismatchInvite = null },
    )
}

/**
 * 邀请悬浮卡片：紧凑版邀请信息 + 「立即加入」按钮 + 底部倒计时进度条。
 * 点击卡片空白处跳转到与该好友的私聊会话。
 */
@Composable
private fun RoomInviteToastCard(
    notification: RoomInviteNotification,
    onAccept: () -> Unit,
    onOpenChat: () -> Unit,
    onTimeout: () -> Unit,
) {
    val invite = notification.invite
    val countdown = remember(notification.messageId) { Animatable(1f) }
    LaunchedEffect(notification.messageId) {
        countdown.animateTo(0f, tween(INVITE_TOAST_DURATION_MS, easing = LinearEasing))
        onTimeout()
    }

    Surface(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenChat),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        shadowElevation = 8.dp,
    ) {
        Column {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        readI18n("friends.inviteCardTitle", I18nType.RWPP),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    val code = invite.code
                    if (!code.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Text(
                                code,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AccountAvatarBox(
                        notification.peer.nickname.ifBlank { notification.peer.username },
                        size = 40.dp,
                        showOnlineDot = false,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            invite.map.ifBlank { "-" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            readI18n("friends.inviteInviter", I18nType.RWPP, invite.inviter),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            readI18n(
                                "friends.inviteMeta", I18nType.RWPP,
                                invite.players, invite.mods.toString(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                AccountPrimaryButton(
                    label = readI18n("friends.inviteJoin", I18nType.RWPP),
                    onClick = onAccept,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                    },
                )

                Text(
                    readI18n("friends.inviteToastHint", I18nType.RWPP),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 底部倒计时进度条：线性耗尽后自动忽略本次邀请
            LinearProgressIndicator(
                progress = { countdown.value },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            )
        }
    }
}

/**
 * 邀请方与本机版本不一致时的确认弹窗（仍允许尝试加入）。
 * 好友聊天内卡片与全局悬浮卡片共用。
 */
@Composable
fun InviteVersionMismatchDialog(
    invite: RoomInvite?,
    onJoinAnyway: (RoomInvite) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedAlertDialog(
        visible = invite != null,
        onDismissRequest = onDismiss,
    ) { dismiss ->
        BorderCard(
            modifier = Modifier
                .fillMaxWidth(LargeProportion())
                .widthIn(max = 420.dp)
                .padding(10.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    readI18n("friends.inviteVersionMismatch", I18nType.RWPP),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                AccountPrimaryButton(
                    label = readI18n("friends.inviteJoinAnyway", I18nType.RWPP),
                    onClick = {
                        val target = invite
                        dismiss()
                        if (target != null) onJoinAnyway(target)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { dismiss() }) {
                    Text(
                        readI18n("common.cancel", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}
