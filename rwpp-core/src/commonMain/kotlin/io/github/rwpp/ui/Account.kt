/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.FriendsSession
import io.github.rwpp.account.accountErrorText
import io.github.rwpp.account.accountLoginUnauthorizedText
import io.github.rwpp.appKoin
import io.github.rwpp.coil.AccountAvatar
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.external.ExternalHandler
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.account.AccountApiException
import io.github.rwpp.net.account.AccountFieldRules
import io.github.rwpp.net.account.PointLedgersResponse
import io.github.rwpp.net.account.RegisterRequest
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.file_open
import io.github.rwpp.rwpp_core.generated.resources.login
import io.github.rwpp.rwpp_core.generated.resources.logo
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.RWSingleOutlinedTextField
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.autoClearFocus
import io.github.rwpp.widget.v2.ExpandedCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

private enum class AccountAuthKind {
    Login,
    Register,
    Forgot,
}

@Composable
fun AccountView(onExit: () -> Unit) {
    BackHandler(true, onExit)
    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("account").broadcastIn()
        }
    }

    val windowManager = LocalWindowManager.current
    val isSmall = windowManager == WindowManager.Small
    val scope = rememberCoroutineScope()

    val loggedIn = AccountSession.loggedIn
    val user = AccountSession.user
    val displayName = AccountSession.displayName
    val restoring = AccountSession.restoring

    var authKind by remember { mutableStateOf<AccountAuthKind?>(null) }
    var draftUsername by remember { mutableStateOf(AccountSession.lastUsername) }
    var refreshing by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showNicknameDialog by remember { mutableStateOf(false) }
    var showAvatarDialog by remember { mutableStateOf(false) }
    var showChangeEmail by remember { mutableStateOf(false) }
    var profileBanner by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        AccountSession.restoreIfNeeded()
        if (AccountSession.loggedIn) {
            runCatching { FriendsSession.refreshLists() }
            runCatching { AccountSession.refreshPresenceSettings() }
            runCatching { AccountSession.refreshPoints() }
        }
    }

    LaunchedEffect(loggedIn) {
        if (loggedIn && AccountSession.networkEnabled) {
            runCatching { AccountSession.refreshPresenceSettings() }
            runCatching { AccountSession.refreshPoints() }
            while (true) {
                delay(8_000)
                runCatching { FriendsSession.refreshLists() }
            }
        }
    }

    val showLoading = (refreshing || restoring) && loggedIn

    // 个人中心文字密集，背景不透明度设下限：
    // 低透明度主题下页面会被游戏画面穿透（部分设备整页几乎全透明），这里保底可读
    val pageBackground = MaterialTheme.colorScheme.background.copy(
        alpha = (UI.backgroundTransparency + 0.2f).coerceAtLeast(0.88f),
    )
    ExpandedCard(
        modifier = Modifier.verticalScroll(rememberScrollState()).autoClearFocus(),
        backgroundColor = pageBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isSmall) 14.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 紧凑顶栏：标题居中、关闭按钮居右，替代原大标题 + 长留白
            Spacer(Modifier.height(if (isSmall) 8.dp else 12.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    readI18n("account.title", I18nType.RWPP),
                    style = if (isSmall) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
                ExitButton(onExit)
            }
            Spacer(Modifier.height(if (isSmall) 10.dp else 14.dp))

            when {
                showLoading -> AccountLoadingState()
                loggedIn && user != null -> AccountLoggedInState(
                    isSmall = isSmall,
                    userId = user.id,
                    hasAvatar = user.hasAvatar,
                    username = user.username,
                    nickname = user.nickname,
                    email = user.email,
                    displayName = displayName,
                    banner = profileBanner.ifBlank { FriendsSession.listError },
                    onRefresh = {
                        if (refreshing || loggingOut) return@AccountLoggedInState
                        scope.launch {
                            refreshing = true
                            profileBanner = ""
                            runCatching {
                                AccountSession.refreshProfile()
                                FriendsSession.refreshLists()
                            }.onFailure { e ->
                                profileBanner = (e as? AccountApiException)?.let { accountErrorText(it) }
                                    ?: readI18n("account.profileFailed", I18nType.RWPP)
                            }
                            refreshing = false
                        }
                    },
                    onChangeNickname = { showNicknameDialog = true },
                    onChangeEmail = { showChangeEmail = true },
                    onAvatarClick = { showAvatarDialog = true },
                    onLogout = { showLogoutConfirm = true },
                )
                else -> AccountLoggedOutState(
                    isSmall = isSmall,
                    onLogin = {
                        draftUsername = AccountSession.lastUsername
                        authKind = AccountAuthKind.Login
                    },
                    onRegister = {
                        draftUsername = AccountSession.lastUsername
                        authKind = AccountAuthKind.Register
                    },
                )
            }

            Spacer(Modifier.height(if (isSmall) 16.dp else 24.dp))
        }
    }

    AccountLoginDialog(
        visible = authKind == AccountAuthKind.Login,
        username = draftUsername,
        onUsernameChange = { draftUsername = it },
        onDismiss = { if (authKind == AccountAuthKind.Login) authKind = null },
        onSwitchToRegister = { authKind = AccountAuthKind.Register },
        onForgot = { authKind = AccountAuthKind.Forgot },
    )

    AccountRegisterDialog(
        visible = authKind == AccountAuthKind.Register,
        username = draftUsername,
        onUsernameChange = { draftUsername = it },
        onDismiss = { if (authKind == AccountAuthKind.Register) authKind = null },
        onSwitchToLogin = { authKind = AccountAuthKind.Login },
    )

    AccountForgotDialog(
        visible = authKind == AccountAuthKind.Forgot,
        onDismiss = { if (authKind == AccountAuthKind.Forgot) authKind = null },
        onBackToLogin = { authKind = AccountAuthKind.Login },
    )

    ChangeNicknameDialog(
        visible = showNicknameDialog,
        current = user?.nickname.orEmpty(),
        onDismiss = { showNicknameDialog = false },
    )

    AccountAvatarDialog(
        visible = showAvatarDialog,
        onDismiss = { showAvatarDialog = false },
    )

    ChangeEmailDialog(
        visible = showChangeEmail,
        onDismiss = { showChangeEmail = false },
    )

    AnimatedAlertDialog(
        visible = showLogoutConfirm,
        onDismissRequest = { if (!loggingOut) showLogoutConfirm = false },
        enableDismiss = !loggingOut,
    ) { dismiss ->
        AccountAuthCard(scrollable = false) {
            AccountDialogHeader(
                title = readI18n("account.logoutConfirmTitle", I18nType.RWPP),
                subtitle = readI18n("account.logoutConfirmBody", I18nType.RWPP),
                iconTint = MaterialTheme.colorScheme.error,
                icon = {
                    Icon(
                        Icons.Default.ExitToApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(26.dp),
                    )
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = !loggingOut,
                    onClick = dismiss,
                ) {
                    Text(
                        readI18n("common.cancel", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                if (loggingOut) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(start = 8.dp).size(28.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(
                        onClick = {
                            scope.launch {
                                loggingOut = true
                                AccountSession.logout()
                                loggingOut = false
                                profileBanner = ""
                                dismiss()
                            }
                        },
                    ) {
                        Text(
                            readI18n("account.logout", I18nType.RWPP),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountLoggedOutState(
    isSmall: Boolean,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (isSmall) 20.dp else 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(
            painter = painterResource(Res.drawable.logo),
            contentDescription = null,
            modifier = Modifier
                .size(if (isSmall) 88.dp else 104.dp)
                .clip(RoundedCornerShape(22.dp))
                .border(
                    2.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    RoundedCornerShape(22.dp),
                ),
        )
        Text(
            readI18n("account.notLoggedInTitle", I18nType.RWPP),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            readI18n("account.notLoggedInBody", I18nType.RWPP),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(2.dp))
        val buttonMod = Modifier
            .fillMaxWidth(if (isSmall) 1f else 0.6f)
            .widthIn(max = 340.dp)
        AccountPrimaryButton(
            label = readI18n("account.login", I18nType.RWPP),
            onClick = onLogin,
            modifier = buttonMod,
            leadingIcon = {
                Icon(
                    painter = painterResource(Res.drawable.login),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            },
        )
        TextButton(onClick = onRegister) {
            Text(
                readI18n("account.switchToRegister", I18nType.RWPP),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AccountLoadingState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            readI18n("account.loading", I18nType.RWPP),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun AccountLoggedInState(
    isSmall: Boolean,
    userId: Long,
    hasAvatar: Boolean,
    username: String,
    nickname: String,
    email: String?,
    displayName: String,
    banner: String,
    onRefresh: () -> Unit,
    onChangeNickname: () -> Unit,
    onChangeEmail: () -> Unit,
    onAvatarClick: () -> Unit,
    onLogout: () -> Unit,
) {
    AccountProfileHeroCard {
        if (isSmall) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AccountHeroAvatar(displayName, userId, hasAvatar, onAvatarClick)
                Spacer(Modifier.height(2.dp))
                AccountHeroName(displayName, username)
                AccountStatusPill(readI18n("account.loggedIn", I18nType.RWPP))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AccountHeroAvatar(displayName, userId, hasAvatar, onAvatarClick)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountHeroName(displayName, username)
                    AccountStatusPill(readI18n("account.loggedIn", I18nType.RWPP))
                }
            }
        }
    }

    if (banner.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        AccountMessageBanner(banner, isError = true)
    }

    Spacer(Modifier.height(if (isSmall) 12.dp else 18.dp))
    AccountSectionHeader(
        title = readI18n("account.profileSection", I18nType.RWPP),
        icon = {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        },
    )
    Spacer(Modifier.height(if (isSmall) 6.dp else 8.dp))
    BorderCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AccountInfoRow(Icons.Default.Person, readI18n("account.username", I18nType.RWPP), username)
            AccountInfoDivider()
            AccountInfoRow(Icons.Default.Face, readI18n("account.nickname", I18nType.RWPP), nickname)
            AccountInfoDivider()
            AccountInfoRow(
                Icons.Default.Email,
                readI18n("account.email", I18nType.RWPP),
                email?.ifBlank { null } ?: readI18n("account.emailUnbound", I18nType.RWPP),
            )
        }
    }

    Spacer(Modifier.height(if (isSmall) 10.dp else 14.dp))
    AccountSectionHeader(
        title = readI18n("account.pointsSection", I18nType.RWPP),
        icon = {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        },
    )
    Spacer(Modifier.height(if (isSmall) 6.dp else 8.dp))
    BorderCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val points = AccountSession.points
            var showLedgers by remember { mutableStateOf(false) }
            if (points.isEmpty()) {
                // 空态与「查看流水」合并为一行，节省纵向空间
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        readI18n("account.pointsEmpty", I18nType.RWPP),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f),
                    )
                    AccountCompactButton(
                        label = readI18n("account.pointLedgers", I18nType.RWPP),
                        onClick = { showLedgers = true },
                    )
                }
            } else {
                points.forEachIndexed { index, point ->
                    if (index > 0) AccountInfoDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                point.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (point.code.isNotBlank()) {
                                Text(
                                    point.code,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                        }
                        Text(
                            point.balance.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    AccountCompactButton(
                        label = readI18n("account.pointLedgers", I18nType.RWPP),
                        onClick = { showLedgers = true },
                    )
                }
            }
            PointLedgersDialog(visible = showLedgers, onDismiss = { showLedgers = false })
        }
    }

    Spacer(Modifier.height(if (isSmall) 10.dp else 14.dp))
    AccountSectionHeader(
        title = readI18n("account.privacySection", I18nType.RWPP),
        icon = {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        },
    )
    Spacer(Modifier.height(if (isSmall) 6.dp else 8.dp))
    BorderCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val presenceSettings = AccountSession.presenceSettings
            val scope = rememberCoroutineScope()
            var privacyError by remember { mutableStateOf("") }
            // 乐观显示的本地覆盖值：切换后立即生效，请求完成（成败）后清掉回退到会话值
            var localHideStrangers by remember { mutableStateOf<Boolean?>(null) }
            var localHideFriends by remember { mutableStateOf<Boolean?>(null) }
            val hideStrangers = localHideStrangers ?: (presenceSettings?.hideFromStrangers ?: false)
            val hideFriends = localHideFriends ?: (presenceSettings?.hideFromFriends ?: false)

            if (privacyError.isNotBlank()) {
                Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    AccountMessageBanner(privacyError, isError = true)
                }
            }
            AccountPresenceSwitchRow(
                label = readI18n("account.hideFromStrangers", I18nType.RWPP),
                checked = hideStrangers,
                enabled = presenceSettings != null,
                onToggle = {
                    val target = !hideStrangers
                    localHideStrangers = target
                    scope.launch {
                        runCatching {
                            AccountSession.updatePresenceSettings(hideFromStrangers = target, hideFromFriends = null)
                        }.onSuccess {
                            privacyError = ""
                        }.onFailure { e ->
                            privacyError = (e as? AccountApiException)?.let { accountErrorText(it) }
                                ?: readI18n("account.presenceSettingsFailed", I18nType.RWPP)
                        }
                        localHideStrangers = null
                    }
                },
            )
            AccountPresenceSwitchRow(
                label = readI18n("account.hideFromFriends", I18nType.RWPP),
                checked = hideFriends,
                enabled = presenceSettings != null,
                onToggle = {
                    val target = !hideFriends
                    localHideFriends = target
                    scope.launch {
                        runCatching {
                            AccountSession.updatePresenceSettings(hideFromStrangers = null, hideFromFriends = target)
                        }.onSuccess {
                            privacyError = ""
                        }.onFailure { e ->
                            privacyError = (e as? AccountApiException)?.let { accountErrorText(it) }
                                ?: readI18n("account.presenceSettingsFailed", I18nType.RWPP)
                        }
                        localHideFriends = null
                    }
                },
            )
        }
    }

    Spacer(Modifier.height(if (isSmall) 10.dp else 14.dp))

    if (isSmall) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AccountActionTile(
                Icons.Default.Edit,
                readI18n("account.changeNickname", I18nType.RWPP),
                onChangeNickname,
                modifier = Modifier.fillMaxWidth(),
            )
            AccountActionTile(
                Icons.Default.Email,
                readI18n("account.changeEmail", I18nType.RWPP),
                onChangeEmail,
                modifier = Modifier.fillMaxWidth(),
            )
            AccountActionTile(
                Icons.Default.Refresh,
                readI18n("account.refresh", I18nType.RWPP),
                onRefresh,
                modifier = Modifier.fillMaxWidth(),
            )
            AccountActionTile(
                Icons.Default.ExitToApp,
                readI18n("account.logout", I18nType.RWPP),
                onLogout,
                modifier = Modifier.fillMaxWidth(),
                danger = true,
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AccountActionTile(
                    Icons.Default.Edit,
                    readI18n("account.changeNickname", I18nType.RWPP),
                    onChangeNickname,
                    modifier = Modifier.weight(1f),
                )
                AccountActionTile(
                    Icons.Default.Email,
                    readI18n("account.changeEmail", I18nType.RWPP),
                    onChangeEmail,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AccountActionTile(
                    Icons.Default.Refresh,
                    readI18n("account.refresh", I18nType.RWPP),
                    onRefresh,
                    modifier = Modifier.weight(1f),
                )
                AccountActionTile(
                    Icons.Default.ExitToApp,
                    readI18n("account.logout", I18nType.RWPP),
                    onLogout,
                    modifier = Modifier.weight(1f),
                    danger = true,
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    AccountMessageBanner(
        text = readI18n("account.multiplayerNameBound", I18nType.RWPP),
        icon = Icons.Default.Info,
    )
}

@Composable
private fun AccountHeroName(displayName: String, username: String) {
    Text(
        displayName,
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (displayName != username) {
        Text(
            "@$username",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AccountLoginDialog(
    visible: Boolean,
    username: String,
    onUsernameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSwitchToRegister: () -> Unit,
    onForgot: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isSmall = LocalWindowManager.current == WindowManager.Small
    var password by remember(visible) { mutableStateOf("") }
    var error by remember(visible) { mutableStateOf("") }
    var submitting by remember(visible) { mutableStateOf(false) }

    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = { if (!submitting) onDismiss() },
        enableDismiss = !submitting,
    ) { dismiss ->
        AccountAuthCard(scrollable = isSmall) {
            AccountDialogHeader(
                title = readI18n("account.loginTitle", I18nType.RWPP),
                subtitle = readI18n("account.loginHint", I18nType.RWPP),
                icon = {
                    Icon(
                        painter = painterResource(Res.drawable.login),
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
                onValueChange = onUsernameChange,
            )
            RWSingleOutlinedTextField(
                label = readI18n("account.password", I18nType.RWPP),
                value = password,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                onValueChange = { password = it },
            )
            if (error.isNotBlank()) {
                AccountMessageBanner(error, isError = true)
            }
            AccountAuthActions(
                submitting = submitting,
                confirmLabel = readI18n("account.login", I18nType.RWPP),
                confirmIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.login),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onCancel = dismiss,
                onConfirm = {
                    when {
                        !AccountFieldRules.isValidUsername(username) ->
                            error = readI18n("account.usernameInvalid", I18nType.RWPP)
                        password.isBlank() ->
                            error = readI18n("account.passwordRequired", I18nType.RWPP)
                        else -> {
                            error = ""
                            submitting = true
                            scope.launch {
                                runCatching {
                                    AccountSession.login(AccountFieldRules.normalizeUsername(username), password)
                                    FriendsSession.refreshLists()
                                }.onSuccess {
                                    submitting = false
                                    dismiss()
                                }.onFailure { e ->
                                    error = (e as? AccountApiException)?.let { accountLoginUnauthorizedText(it) }
                                        ?: e.message.orEmpty()
                                    submitting = false
                                }
                            }
                        }
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(
                    enabled = !submitting,
                    onClick = onForgot,
                ) {
                    Text(
                        readI18n("account.forgotPassword", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                TextButton(
                    enabled = !submitting,
                    onClick = onSwitchToRegister,
                ) {
                    Text(
                        readI18n("account.switchToRegister", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
fun AccountRegisterDialog(
    visible: Boolean,
    username: String,
    onUsernameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSwitchToLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember(visible) { mutableStateOf("") }
    var confirmPassword by remember(visible) { mutableStateOf("") }
    var nickname by remember(visible) { mutableStateOf("") }
    var email by remember(visible) { mutableStateOf("") }
    var code by remember(visible) { mutableStateOf("") }
    var error by remember(visible) { mutableStateOf("") }
    var info by remember(visible) { mutableStateOf("") }
    var submitting by remember(visible) { mutableStateOf(false) }
    var sendingCode by remember(visible) { mutableStateOf(false) }
    var cooldown by remember(visible) { mutableIntStateOf(0) }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = { if (!submitting) onDismiss() },
        enableDismiss = !submitting,
    ) { dismiss ->
        AccountAuthCard(scrollable = true) {
            AccountDialogHeader(
                title = readI18n("account.registerTitle", I18nType.RWPP),
                subtitle = readI18n("account.registerHint", I18nType.RWPP),
                icon = {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                },
            )
            RWSingleOutlinedTextField(
                label = readI18n("account.username", I18nType.RWPP),
                value = username,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                onValueChange = onUsernameChange,
            )
            RWSingleOutlinedTextField(
                label = readI18n("account.nicknameOptional", I18nType.RWPP),
                value = nickname,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Face, contentDescription = null) },
                onValueChange = { nickname = it },
            )
            RWSingleOutlinedTextField(
                label = readI18n("account.email", I18nType.RWPP),
                value = email,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                onValueChange = { email = it },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RWSingleOutlinedTextField(
                    label = readI18n("account.code", I18nType.RWPP),
                    value = code,
                    enabled = !submitting,
                    modifier = Modifier.weight(1f),
                    onValueChange = { if (it.length <= 6) code = it.filter { ch -> ch.isDigit() } },
                )
                AccountCompactButton(
                    label = if (cooldown > 0) {
                        readI18n("account.sendCodeWait", I18nType.RWPP, cooldown.toString())
                    } else {
                        readI18n("account.sendCode", I18nType.RWPP)
                    },
                    enabled = !submitting && !sendingCode && cooldown == 0,
                    onClick = {
                        when {
                            !AccountFieldRules.isValidEmail(email) ->
                                error = readI18n("account.emailInvalid", I18nType.RWPP)
                            else -> {
                                error = ""
                                sendingCode = true
                                scope.launch {
                                    runCatching {
                                        AccountSession.sendRegisterCode(AccountFieldRules.normalizeEmail(email))
                                    }.onSuccess {
                                        info = readI18n("account.codeSent", I18nType.RWPP)
                                        cooldown = 60
                                    }.onFailure { e ->
                                        error = (e as? AccountApiException)?.let { accountErrorText(it) }
                                            ?: e.message.orEmpty()
                                    }
                                    sendingCode = false
                                }
                            }
                        }
                    },
                )
            }
            RWSingleOutlinedTextField(
                label = readI18n("account.password", I18nType.RWPP),
                value = password,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                onValueChange = { password = it },
            )
            RWSingleOutlinedTextField(
                label = readI18n("account.confirmPassword", I18nType.RWPP),
                value = confirmPassword,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                onValueChange = { confirmPassword = it },
            )
            if (info.isNotBlank() && error.isBlank()) {
                AccountMessageBanner(info)
            }
            if (error.isNotBlank()) {
                AccountMessageBanner(error, isError = true)
            }
            AccountAuthActions(
                submitting = submitting,
                confirmLabel = readI18n("account.register", I18nType.RWPP),
                onCancel = dismiss,
                onConfirm = {
                    val name = AccountFieldRules.normalizeUsername(username)
                    val mail = AccountFieldRules.normalizeEmail(email)
                    error = when {
                        !AccountFieldRules.isValidUsername(name) ->
                            readI18n("account.usernameInvalid", I18nType.RWPP)
                        !AccountFieldRules.isValidPassword(password) ->
                            readI18n("account.passwordInvalid", I18nType.RWPP)
                        password != confirmPassword ->
                            readI18n("account.passwordMismatch", I18nType.RWPP)
                        !AccountFieldRules.isValidRegisterNickname(nickname) ->
                            readI18n("account.nicknameInvalid", I18nType.RWPP)
                        !AccountFieldRules.isValidEmail(mail) ->
                            readI18n("account.emailInvalid", I18nType.RWPP)
                        !AccountFieldRules.isValidCode(code) ->
                            readI18n("account.codeInvalid", I18nType.RWPP)
                        else -> ""
                    }
                    if (error.isNotBlank()) return@AccountAuthActions
                    submitting = true
                    scope.launch {
                        runCatching {
                            AccountSession.register(
                                RegisterRequest(
                                    username = name,
                                    password = password,
                                    nickname = nickname.trim().ifBlank { null },
                                    email = mail,
                                    code = code.trim(),
                                ),
                                password,
                            )
                            FriendsSession.refreshLists()
                        }.onSuccess {
                            submitting = false
                            dismiss()
                        }.onFailure { e ->
                            error = (e as? AccountApiException)?.let { accountErrorText(it) }
                                ?: e.message.orEmpty()
                            submitting = false
                        }
                    }
                },
            )
            TextButton(
                enabled = !submitting,
                onClick = onSwitchToLogin,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    readI18n("account.switchToLogin", I18nType.RWPP),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun AccountForgotDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onBackToLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var email by remember(visible) { mutableStateOf("") }
    var code by remember(visible) { mutableStateOf("") }
    var password by remember(visible) { mutableStateOf("") }
    var confirm by remember(visible) { mutableStateOf("") }
    var error by remember(visible) { mutableStateOf("") }
    var info by remember(visible) { mutableStateOf("") }
    var submitting by remember(visible) { mutableStateOf(false) }
    var sendingCode by remember(visible) { mutableStateOf(false) }
    var cooldown by remember(visible) { mutableIntStateOf(0) }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = { if (!submitting) onDismiss() },
        enableDismiss = !submitting,
    ) { dismiss ->
        AccountAuthCard(scrollable = true) {
            AccountDialogHeader(
                title = readI18n("account.forgotTitle", I18nType.RWPP),
                subtitle = readI18n("account.forgotHint", I18nType.RWPP),
                icon = {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                },
            )
            RWSingleOutlinedTextField(
                label = readI18n("account.email", I18nType.RWPP),
                value = email,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                onValueChange = { email = it },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RWSingleOutlinedTextField(
                    label = readI18n("account.code", I18nType.RWPP),
                    value = code,
                    enabled = !submitting,
                    modifier = Modifier.weight(1f),
                    onValueChange = { if (it.length <= 6) code = it.filter { ch -> ch.isDigit() } },
                )
                AccountCompactButton(
                    label = if (cooldown > 0) {
                        readI18n("account.sendCodeWait", I18nType.RWPP, cooldown.toString())
                    } else {
                        readI18n("account.sendCode", I18nType.RWPP)
                    },
                    enabled = !submitting && !sendingCode && cooldown == 0,
                    onClick = {
                        if (!AccountFieldRules.isValidEmail(email)) {
                            error = readI18n("account.emailInvalid", I18nType.RWPP)
                        } else {
                            sendingCode = true
                            scope.launch {
                                runCatching {
                                    AccountSession.sendResetCode(AccountFieldRules.normalizeEmail(email))
                                }.onSuccess {
                                    info = readI18n("account.codeSent", I18nType.RWPP)
                                    error = ""
                                    cooldown = 60
                                }.onFailure { e ->
                                    error = (e as? AccountApiException)?.let { accountErrorText(it) }
                                        ?: e.message.orEmpty()
                                }
                                sendingCode = false
                            }
                        }
                    },
                )
            }
            RWSingleOutlinedTextField(
                label = readI18n("account.newPassword", I18nType.RWPP),
                value = password,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                onValueChange = { password = it },
            )
            RWSingleOutlinedTextField(
                label = readI18n("account.confirmPassword", I18nType.RWPP),
                value = confirm,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                onValueChange = { confirm = it },
            )
            if (info.isNotBlank() && error.isBlank()) {
                AccountMessageBanner(info)
            }
            if (error.isNotBlank()) {
                AccountMessageBanner(error, isError = true)
            }
            AccountAuthActions(
                submitting = submitting,
                confirmLabel = readI18n("account.resetPassword", I18nType.RWPP),
                onCancel = dismiss,
                onConfirm = {
                    val mail = AccountFieldRules.normalizeEmail(email)
                    error = when {
                        !AccountFieldRules.isValidEmail(mail) ->
                            readI18n("account.emailInvalid", I18nType.RWPP)
                        !AccountFieldRules.isValidCode(code) ->
                            readI18n("account.codeInvalid", I18nType.RWPP)
                        !AccountFieldRules.isValidPassword(password) ->
                            readI18n("account.passwordInvalid", I18nType.RWPP)
                        password != confirm ->
                            readI18n("account.passwordMismatch", I18nType.RWPP)
                        else -> ""
                    }
                    if (error.isNotBlank()) return@AccountAuthActions
                    submitting = true
                    scope.launch {
                        runCatching {
                            AccountSession.resetPassword(mail, code.trim(), password)
                        }.onSuccess {
                            submitting = false
                            dismiss()
                            onBackToLogin()
                        }.onFailure { e ->
                            error = (e as? AccountApiException)?.let { accountErrorText(it) }
                                ?: e.message.orEmpty()
                            submitting = false
                        }
                    }
                },
            )
            TextButton(
                enabled = !submitting,
                onClick = onBackToLogin,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    readI18n("account.switchToLogin", I18nType.RWPP),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ChangeNicknameDialog(
    visible: Boolean,
    current: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var nickname by remember(visible) { mutableStateOf(current) }
    var error by remember(visible) { mutableStateOf("") }
    var submitting by remember(visible) { mutableStateOf(false) }

    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = { if (!submitting) onDismiss() },
        enableDismiss = !submitting,
    ) { dismiss ->
        AccountAuthCard(scrollable = false) {
            AccountDialogHeader(
                title = readI18n("account.changeNickname", I18nType.RWPP),
                subtitle = readI18n("account.multiplayerNameBound", I18nType.RWPP),
                icon = {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                },
            )
            RWSingleOutlinedTextField(
                label = readI18n("account.nickname", I18nType.RWPP),
                value = nickname,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Face, contentDescription = null) },
                onValueChange = { nickname = it },
            )
            if (error.isNotBlank()) {
                AccountMessageBanner(error, isError = true)
            }
            AccountAuthActions(
                submitting = submitting,
                confirmLabel = readI18n("common.ok", I18nType.RWPP),
                onCancel = dismiss,
                onConfirm = {
                    if (!AccountFieldRules.isValidChangeNickname(nickname)) {
                        error = readI18n("account.nicknameInvalid", I18nType.RWPP)
                        return@AccountAuthActions
                    }
                    submitting = true
                    scope.launch {
                        runCatching { AccountSession.changeNickname(nickname.trim()) }
                            .onSuccess {
                                submitting = false
                                dismiss()
                            }.onFailure { e ->
                                error = (e as? AccountApiException)?.let { accountErrorText(it) }
                                    ?: e.message.orEmpty()
                                submitting = false
                            }
                    }
                },
            )
        }
    }
}

@Composable
private fun AccountAuthActions(
    submitting: Boolean,
    confirmLabel: String,
    confirmIcon: @Composable (() -> Unit)? = null,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.padding(vertical = 10.dp).size(36.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            AccountPrimaryButton(
                label = confirmLabel,
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = confirmIcon,
            )
        }
        TextButton(enabled = !submitting, onClick = onCancel) {
            Text(
                readI18n("common.cancel", I18nType.RWPP),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

/** Hero 区头像：真实头像 + 在线点，透明圆形点击层（涟漪裁成圆形且不裁掉右下角状态点）。 */
@Composable
private fun AccountHeroAvatar(
    displayName: String,
    userId: Long,
    hasAvatar: Boolean,
    onClick: () -> Unit,
) {
    Box {
        AccountAvatarBox(
            displayName,
            size = 84.dp,
            avatar = AccountAvatar(userId, hasAvatar, AccountSession.avatarVersion),
            online = true,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .clickable(onClick = onClick),
        )
    }
}

/** 隐私区块的开关行：标签 + material3 Switch。 */
@Composable
private fun AccountPresenceSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { onToggle() },
        )
    }
}

/** 积分流水弹窗：分页拉取，不常驻会话状态。 */
@Composable
private fun PointLedgersDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    var page by remember(visible) { mutableIntStateOf(1) }
    var loading by remember(visible) { mutableStateOf(false) }
    var error by remember(visible) { mutableStateOf("") }
    var resp by remember(visible) { mutableStateOf<PointLedgersResponse?>(null) }

    LaunchedEffect(visible, page) {
        if (!visible) return@LaunchedEffect
        loading = true
        error = ""
        runCatching { AccountSession.fetchPointLedgers(page, 20, null) }
            .onSuccess { resp = it }
            .onFailure { e ->
                error = (e as? AccountApiException)?.let { accountErrorText(it) }
                    ?: e.message.orEmpty()
            }
        loading = false
    }

    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
    ) {
        AccountAuthCard(scrollable = false) {
            AccountDialogHeader(
                title = readI18n("account.pointLedgers", I18nType.RWPP),
                icon = {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                },
            )
            if (error.isNotBlank()) {
                AccountMessageBanner(error, isError = true)
            }
            val ledgers = resp?.ledgers.orEmpty()
            if (loading && ledgers.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp).size(32.dp).align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (ledgers.isEmpty()) {
                Text(
                    readI18n("account.ledgerEmpty", I18nType.RWPP),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(ledgers.size, key = { ledgers[it].id }) { index ->
                        val ledger = ledgers[index]
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    ledger.pointName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    ledger.createdAt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                )
                                if (ledger.remark.isNotBlank()) {
                                    Text(
                                        ledger.remark,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    if (ledger.changeAmount >= 0) "+${ledger.changeAmount}" else ledger.changeAmount.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (ledger.changeAmount >= 0) {
                                        Color(95, 190, 95)
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    ledger.balanceAfter.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }
            val totalPages = resp?.totalPages ?: 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    enabled = page > 1 && !loading,
                    onClick = { page -= 1 },
                ) {
                    Text(
                        readI18n("account.ledgerPrev", I18nType.RWPP),
                        color = if (page > 1) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        },
                    )
                }
                Text(
                    readI18n("account.ledgerPage", I18nType.RWPP, page.toString(), totalPages.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                TextButton(
                    enabled = page < totalPages && !loading,
                    onClick = { page += 1 },
                ) {
                    Text(
                        readI18n("account.ledgerNext", I18nType.RWPP),
                        color = if (page < totalPages) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        },
                    )
                }
            }
        }
    }
}

/** 头像弹窗：上传新头像（客户端预检大小与格式）/ 删除头像（对话框内确认态）。 */
@Composable
private fun AccountAvatarDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirmingDelete by remember(visible) { mutableStateOf(false) }
    var busy by remember(visible) { mutableStateOf(false) }
    var error by remember(visible) { mutableStateOf("") }
    var info by remember(visible) { mutableStateOf("") }
    val hasAvatar = AccountSession.user?.hasAvatar == true

    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = { if (!busy) onDismiss() },
        enableDismiss = !busy,
    ) { dismiss ->
        AccountAuthCard(scrollable = false) {
            AccountDialogHeader(
                title = readI18n("account.avatarTitle", I18nType.RWPP),
                icon = {
                    Icon(
                        Icons.Default.Face,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                },
            )
            if (info.isNotBlank() && error.isBlank()) {
                AccountMessageBanner(info)
            }
            if (error.isNotBlank()) {
                AccountMessageBanner(error, isError = true)
            }
            if (confirmingDelete) {
                Text(
                    readI18n("account.avatarDeleteConfirm", I18nType.RWPP),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        enabled = !busy,
                        onClick = { confirmingDelete = false },
                    ) {
                        Text(
                            readI18n("common.cancel", I18nType.RWPP),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                error = ""
                                info = ""
                                runCatching { AccountSession.deleteAvatar() }
                                    .onSuccess {
                                        info = readI18n("account.avatarUpdated", I18nType.RWPP)
                                        confirmingDelete = false
                                        busy = false
                                        delay(600)
                                        dismiss()
                                    }.onFailure { e ->
                                        error = (e as? AccountApiException)?.let { accountErrorText(it) }
                                            ?: readI18n("account.avatarFailed", I18nType.RWPP)
                                        confirmingDelete = false
                                        busy = false
                                    }
                            }
                        },
                    ) {
                        Text(
                            readI18n("account.avatarDelete", I18nType.RWPP),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            } else {
                AccountCompactButton(
                    label = readI18n("account.avatarUpload", I18nType.RWPP),
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(
                            painter = painterResource(Res.drawable.file_open),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        error = ""
                        info = ""
                        appKoin.get<ExternalHandler>().openFileChooser { file ->
                            scope.launch {
                                val ext = file.extension.lowercase()
                                when {
                                    file.length() > 1_048_576L ->
                                        error = readI18n("account.avatarTooLarge", I18nType.RWPP)
                                    ext != "jpg" && ext != "jpeg" && ext != "png" ->
                                        error = readI18n("account.avatarInvalidFormat", I18nType.RWPP)
                                    else -> {
                                        busy = true
                                        runCatching {
                                            AccountSession.uploadAvatar(
                                                file.readBytes(),
                                                file.name,
                                                if (ext == "png") "image/png" else "image/jpeg",
                                            )
                                        }.onSuccess {
                                            info = readI18n("account.avatarUpdated", I18nType.RWPP)
                                            busy = false
                                            delay(600)
                                            dismiss()
                                        }.onFailure { e ->
                                            error = (e as? AccountApiException)?.let { accountErrorText(it) }
                                                ?: readI18n("account.avatarFailed", I18nType.RWPP)
                                            busy = false
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
                if (hasAvatar) {
                    TextButton(
                        enabled = !busy,
                        onClick = { confirmingDelete = true },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(
                            readI18n("account.avatarDelete", I18nType.RWPP),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp).align(Alignment.CenterHorizontally),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 更换绑定邮箱弹窗：新邮箱 + 验证码（60s 冷却）；成功后服务端使所有 Token 失效，本地会话已清空。 */
@Composable
private fun ChangeEmailDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var email by remember(visible) { mutableStateOf("") }
    var code by remember(visible) { mutableStateOf("") }
    var error by remember(visible) { mutableStateOf("") }
    var info by remember(visible) { mutableStateOf("") }
    var submitting by remember(visible) { mutableStateOf(false) }
    var sendingCode by remember(visible) { mutableStateOf(false) }
    var cooldown by remember(visible) { mutableIntStateOf(0) }
    var done by remember(visible) { mutableStateOf(false) }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = { if (!submitting) onDismiss() },
        enableDismiss = !submitting,
    ) { dismiss ->
        AccountAuthCard(scrollable = true) {
            AccountDialogHeader(
                title = readI18n("account.changeEmailTitle", I18nType.RWPP),
                subtitle = readI18n("account.changeEmailHint", I18nType.RWPP),
                icon = {
                    Icon(
                        Icons.Default.Email,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                },
            )
            if (done) {
                AccountMessageBanner(readI18n("account.emailChangedRelogin", I18nType.RWPP))
                AccountPrimaryButton(
                    label = readI18n("common.ok", I18nType.RWPP),
                    onClick = dismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                RWSingleOutlinedTextField(
                    label = readI18n("account.newEmail", I18nType.RWPP),
                    value = email,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    onValueChange = { email = it },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RWSingleOutlinedTextField(
                        label = readI18n("account.code", I18nType.RWPP),
                        value = code,
                        enabled = !submitting,
                        modifier = Modifier.weight(1f),
                        onValueChange = { if (it.length <= 6) code = it.filter { ch -> ch.isDigit() } },
                    )
                    AccountCompactButton(
                        label = if (cooldown > 0) {
                            readI18n("account.sendCodeWait", I18nType.RWPP, cooldown.toString())
                        } else {
                            readI18n("account.sendCode", I18nType.RWPP)
                        },
                        enabled = !submitting && !sendingCode && cooldown == 0,
                        onClick = {
                            when {
                                !AccountFieldRules.isValidEmail(email) ->
                                    error = readI18n("account.emailInvalid", I18nType.RWPP)
                                else -> {
                                    error = ""
                                    sendingCode = true
                                    scope.launch {
                                        runCatching {
                                            AccountSession.sendChangeEmailCode(AccountFieldRules.normalizeEmail(email))
                                        }.onSuccess {
                                            info = readI18n("account.codeSent", I18nType.RWPP)
                                            cooldown = 60
                                        }.onFailure { e ->
                                            error = (e as? AccountApiException)?.let { accountErrorText(it) }
                                                ?: e.message.orEmpty()
                                        }
                                        sendingCode = false
                                    }
                                }
                            }
                        },
                    )
                }
                if (info.isNotBlank() && error.isBlank()) {
                    AccountMessageBanner(info)
                }
                if (error.isNotBlank()) {
                    AccountMessageBanner(error, isError = true)
                }
                AccountAuthActions(
                    submitting = submitting,
                    confirmLabel = readI18n("account.changeEmail", I18nType.RWPP),
                    onCancel = dismiss,
                    onConfirm = {
                        val mail = AccountFieldRules.normalizeEmail(email)
                        error = when {
                            !AccountFieldRules.isValidEmail(mail) ->
                                readI18n("account.emailInvalid", I18nType.RWPP)
                            !AccountFieldRules.isValidCode(code) ->
                                readI18n("account.codeInvalid", I18nType.RWPP)
                            else -> ""
                        }
                        if (error.isNotBlank()) return@AccountAuthActions
                        submitting = true
                        scope.launch {
                            runCatching {
                                AccountSession.changeEmail(mail, code.trim())
                            }.onSuccess {
                                submitting = false
                                done = true
                            }.onFailure { e ->
                                error = (e as? AccountApiException)?.let { accountErrorText(it) }
                                    ?: e.message.orEmpty()
                                submitting = false
                            }
                        }
                    },
                )
            }
        }
    }
}
