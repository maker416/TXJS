/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.widget.UiSwitch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.app.LauncherVisibility
import io.github.rwpp.app.whileVisible
import io.github.rwpp.AppContext
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
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.RWSingleOutlinedTextField
import io.github.rwpp.widget.UiCircularProgressIndicator
import io.github.rwpp.widget.autoClearFocus
import io.github.rwpp.widget.desktopEscapeToBackDispatcher
import io.github.rwpp.widget.v2.ExpandedCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject

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
    var showLedgers by remember { mutableStateOf(false) }
    var profileBanner by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { AccountSession.restoreIfNeeded() }

    LaunchedEffect(loggedIn) {
        if (!loggedIn) showLedgers = false
        LauncherVisibility.visible.whileVisible {
            if (loggedIn && AccountSession.networkEnabled) {
                runCatching { AccountSession.refreshPresenceSettings() }
                runCatching { AccountSession.refreshPoints() }
                while (true) {
                    runCatching { FriendsSession.refreshLists() }
                    delay(FriendsSession.LIST_POLL_INTERVAL_MS)
                }
            }
        }
    }

    val showLoading = restoring && loggedIn

    // 个人中心文字密集，背景不透明度设下限：
    // 低透明度主题下页面会被游戏画面穿透（部分设备整页几乎全透明），这里保底可读
    val pageBackground = MaterialTheme.colorScheme.background.copy(
        alpha = (UI.backgroundTransparency + 0.2f).coerceAtLeast(0.88f),
    )
    val appContext = koinInject<AppContext>()
    val accountModalOpen = showNicknameDialog || showAvatarDialog || showChangeEmail ||
        showLogoutConfirm || showLedgers || authKind != null
    val pageFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        pageFocusRequester.requestFocus()
    }
    // 弹窗内输入框 Esc 关闭后焦点常留在已卸载节点，桌面第二次 Esc 无法回到 BackHandler。
    LaunchedEffect(accountModalOpen) {
        if (appContext.isDesktop() && !accountModalOpen) {
            pageFocusRequester.requestFocus()
        }
    }
    val pageBlankInteraction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(pageFocusRequester)
            .then(
                if (appContext.isDesktop()) {
                    Modifier.desktopEscapeToBackDispatcher(scope)
                } else {
                    Modifier
                },
            )
            .focusable(),
    ) {
        if (appContext.isDesktop()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = pageBlankInteraction,
                        indication = null,
                        onClick = { pageFocusRequester.requestFocus() },
                    ),
            )
        }
        if (!loggedIn && !showLoading) {
            AccountWelcomeView(
                onLogin = {
                    draftUsername = AccountSession.lastUsername
                    authKind = AccountAuthKind.Login
                },
                onRegister = {
                    draftUsername = AccountSession.lastUsername
                    authKind = AccountAuthKind.Register
                },
                onExit = onExit,
            )
        } else {
            ExpandedCard(
                modifier = Modifier.autoClearFocus(
                    restoreFocus = if (appContext.isDesktop()) pageFocusRequester else null,
                ),
                backgroundColor = pageBackground,
            ) {
                val refreshProfile: () -> Unit = refresh@{
                    if (refreshing || loggingOut) return@refresh
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
                }
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                    Text(
                        readI18n("account.centerTitle", I18nType.RWPP),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp, end = 112.dp),
                    )
                    IconButton(
                        onClick = refreshProfile,
                        enabled = !refreshing && !restoring && !loggingOut,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 56.dp),
                    ) {
                        if (refreshing) UiCircularProgressIndicator(Modifier.size(22.dp))
                        else Icon(Icons.Default.Refresh, readI18n("account.refresh", I18nType.RWPP))
                    }
                    ExitButton(onExit)
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        showLoading -> AccountLoadingState()
                        loggedIn && user != null -> AccountLoggedInState(
                            userId = user.id,
                            hasAvatar = user.hasAvatar,
                            username = user.username,
                            nickname = user.nickname,
                            email = user.email,
                            displayName = displayName,
                            banner = profileBanner.ifBlank { FriendsSession.listError },
                            onChangeNickname = { showNicknameDialog = true },
                            onChangeEmail = { showChangeEmail = true },
                            onAvatarClick = { showAvatarDialog = true },
                            onLogout = { showLogoutConfirm = true },
                            onPointLedgers = { showLedgers = true },
                        )
                        else -> AccountLoadingState()
                    }
                }
            }
        }

    PointLedgersDialog(visible = showLedgers, onDismiss = { showLedgers = false })

    AccountLoginDialog(
        inlineDesktop = appContext.isDesktop(),
        visible = authKind == AccountAuthKind.Login,
        username = draftUsername,
        onUsernameChange = { draftUsername = it },
        onDismiss = { if (authKind == AccountAuthKind.Login) authKind = null },
        onSwitchToRegister = { authKind = AccountAuthKind.Register },
        onForgot = { authKind = AccountAuthKind.Forgot },
    )

    AccountRegisterDialog(
        inlineDesktop = appContext.isDesktop(),
        visible = authKind == AccountAuthKind.Register,
        username = draftUsername,
        onUsernameChange = { draftUsername = it },
        onDismiss = { if (authKind == AccountAuthKind.Register) authKind = null },
        onSwitchToLogin = { authKind = AccountAuthKind.Login },
    )

    AccountForgotDialog(
        inlineDesktop = appContext.isDesktop(),
        visible = authKind == AccountAuthKind.Forgot,
        onDismiss = { if (authKind == AccountAuthKind.Forgot) authKind = null },
        onBackToLogin = { authKind = AccountAuthKind.Login },
    )

    ChangeNicknameDialog(
        inlineDesktop = appContext.isDesktop(),
        visible = showNicknameDialog,
        current = user?.nickname.orEmpty(),
        onDismiss = { showNicknameDialog = false },
    )

    AccountAvatarDialog(
        inlineDesktop = appContext.isDesktop(),
        visible = showAvatarDialog,
        onDismiss = { showAvatarDialog = false },
    )

    ChangeEmailDialog(
        inlineDesktop = appContext.isDesktop(),
        visible = showChangeEmail,
        onDismiss = { showChangeEmail = false },
    )

    AnimatedAlertDialog(
        inlineDesktop = appContext.isDesktop(),
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
                    UiCircularProgressIndicator(
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
        UiCircularProgressIndicator(
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
    userId: Long,
    hasAvatar: Boolean,
    username: String,
    nickname: String,
    email: String?,
    displayName: String,
    banner: String,
    onChangeNickname: () -> Unit,
    onChangeEmail: () -> Unit,
    onAvatarClick: () -> Unit,
    onLogout: () -> Unit,
    onPointLedgers: () -> Unit,
) {
    var selectedName by rememberSaveable(userId) { mutableStateOf(AccountCenterPage.Profile.name) }
    val selectedPage = AccountCenterPage.valueOf(selectedName)
    // 状态和请求作用域属于整个账号页，切换分类不能取消正在保存的隐私设置。
    val presenceSettings = AccountSession.presenceSettings
    val scope = rememberCoroutineScope()
    var privacyError by remember { mutableStateOf("") }
    // 乐观显示的本地覆盖值：切换后立即生效，请求完成（成败）后清掉回退到会话值
    var localHideStrangers by remember { mutableStateOf<Boolean?>(null) }
    var localHideFriends by remember { mutableStateOf<Boolean?>(null) }
    val hideStrangers = localHideStrangers ?: (presenceSettings?.hideFromStrangers ?: false)
    val hideFriends = localHideFriends ?: (presenceSettings?.hideFromFriends ?: false)

    AccountCenterLayout(
        selectedPage = selectedPage,
        onSelectPage = { selectedName = it.name },
        displayName = displayName,
        username = username,
        banner = banner,
        avatar = { AccountHeroAvatar(displayName, userId, hasAvatar, onAvatarClick, compact = true) },
    ) { compact ->
        when (selectedPage) {
            AccountCenterPage.Profile -> {
                if (compact) AccountProfileHeroCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AccountHeroAvatar(displayName, userId, hasAvatar, onAvatarClick, compact = true)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(displayName, style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("@$username", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                BorderCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AccountInfoRow(Icons.Default.Person, readI18n("account.username", I18nType.RWPP), username)
                        AccountInfoDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                AccountInfoRow(Icons.Default.Face, readI18n("account.nickname", I18nType.RWPP), nickname)
                            }
                            IconButton(onClick = onChangeNickname, modifier = Modifier.padding(end = 8.dp)) {
                                Icon(Icons.Default.Edit, readI18n("account.changeNickname", I18nType.RWPP))
                            }
                        }
                        AccountInfoDivider()
                        AccountInfoRow(
                            Icons.Default.Email,
                            readI18n("account.email", I18nType.RWPP),
                            email?.ifBlank { null } ?: readI18n("account.emailUnbound", I18nType.RWPP),
                        )
                    }
                }

                AccountActionTile(Icons.Default.Person, readI18n("account.avatarTitle", I18nType.RWPP),
                    onAvatarClick, modifier = Modifier.fillMaxWidth())
                AccountMessageBanner(readI18n("account.multiplayerNameBound", I18nType.RWPP), icon = Icons.Default.Info)
            }
            AccountCenterPage.Points -> {
                BorderCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val points = AccountSession.points
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
                                    onClick = onPointLedgers,
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
                                    onClick = onPointLedgers,
                                )
                            }
                        }
                    }
                }

            }
            AccountCenterPage.Privacy -> {
                BorderCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {

                        if (privacyError.isNotBlank()) {
                            Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                AccountMessageBanner(privacyError, isError = true)
                            }
                        }
                        AccountPresenceSwitchRow(
                            label = readI18n("account.hideFromStrangers", I18nType.RWPP),
                            checked = hideStrangers,
                            enabled = presenceSettings != null && localHideStrangers == null,
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
                            enabled = presenceSettings != null && localHideFriends == null,
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

            }
            AccountCenterPage.Security -> {
                BorderCard(modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)) {
                    AccountInfoRow(Icons.Default.Email, readI18n("account.email", I18nType.RWPP),
                        email?.ifBlank { null } ?: readI18n("account.emailUnbound", I18nType.RWPP))
                }
                AccountActionTile(Icons.Default.Email, readI18n("account.changeEmail", I18nType.RWPP),
                    onChangeEmail, modifier = Modifier.fillMaxWidth())
                AccountMessageBanner(readI18n("account.changeEmailHint", I18nType.RWPP))
                AccountActionTile(Icons.Default.ExitToApp, readI18n("account.logout", I18nType.RWPP),
                    onLogout, modifier = Modifier.fillMaxWidth(), danger = true)
            }
        }
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
) = AccountLoginDialog(visible, username, onUsernameChange, onDismiss, inlineDesktop = false, onSwitchToRegister, onForgot)

@Composable
fun AccountLoginDialog(
    visible: Boolean,
    username: String,
    onUsernameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    inlineDesktop: Boolean,
    onSwitchToRegister: () -> Unit,
    onForgot: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember(visible) { mutableStateOf("") }
    var error by remember(visible) { mutableStateOf("") }
    var info by remember(visible) { mutableStateOf("") }
    var submitting by remember(visible) { mutableStateOf(false) }
    var useEmailCode by remember(visible) { mutableStateOf(false) }
    var email by remember(visible) { mutableStateOf("") }
    var code by remember(visible) { mutableStateOf("") }
    var sendingCode by remember(visible) { mutableStateOf(false) }
    var cooldown by remember(visible) { mutableIntStateOf(0) }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    AnimatedAlertDialog(
        inlineDesktop = inlineDesktop,
        visible = visible,
        onDismissRequest = { if (!submitting) onDismiss() },
        enableDismiss = !submitting,
    ) { dismiss ->
        AccountAccessCard(
            title = readI18n("account.access.loginTitle", I18nType.RWPP),
            subtitle = readI18n("account.access.loginSubtitle", I18nType.RWPP),
            busy = submitting,
            onClose = dismiss,
        ) {
            AccountAccessTabs(useEmailCode, !submitting) { selected ->
                if (selected && "@" in username.trim()) email = username.trim()
                useEmailCode = selected
                error = ""
                info = ""
            }
            if (useEmailCode) {
                AccountAccessField(
                    label = readI18n("account.email", I18nType.RWPP),
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
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
                    AccountAccessField(
                        label = readI18n("account.code", I18nType.RWPP),
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
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
                                error = ""
                                sendingCode = true
                                scope.launch {
                                    runCatching {
                                        AccountSession.sendLoginCode(AccountFieldRules.normalizeEmail(email))
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
                        },
                    )
                }
            } else {
                AccountAccessField(
                    label = readI18n("account.identifier", I18nType.RWPP),
                    value = username,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    onValueChange = onUsernameChange,
                )
                AccountAccessField(
                    label = readI18n("account.password", I18nType.RWPP),
                    value = password,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    visualTransformation = PasswordVisualTransformation(),
                    onValueChange = { password = it },
                )
            }
            if (info.isNotBlank() && error.isBlank()) {
                AccountMessageBanner(info)
            }
            if (error.isNotBlank()) {
                AccountMessageBanner(error, isError = true)
            }
            AccountAuthActions(
                compact = true,
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
                    if (useEmailCode) {
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
                                AccountSession.loginWithEmailCode(mail, code.trim())
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
                    } else {
                        val identifier = username.trim()
                        val byEmail = "@" in identifier
                        error = when {
                            identifier.isBlank() ->
                                readI18n("account.identifierRequired", I18nType.RWPP)
                            byEmail && !AccountFieldRules.isValidEmail(identifier) ->
                                readI18n("account.identifierInvalid", I18nType.RWPP)
                            !byEmail && !AccountFieldRules.isValidUsername(identifier) ->
                                readI18n("account.usernameInvalid", I18nType.RWPP)
                            password.isBlank() ->
                                readI18n("account.passwordRequired", I18nType.RWPP)
                            else -> ""
                        }
                        if (error.isNotBlank()) return@AccountAuthActions
                        submitting = true
                        scope.launch {
                            runCatching {
                                if (byEmail) {
                                    AccountSession.loginWithEmail(
                                        AccountFieldRules.normalizeEmail(identifier),
                                        password,
                                    )
                                } else {
                                    AccountSession.login(
                                        AccountFieldRules.normalizeUsername(identifier),
                                        password,
                                    )
                                }
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
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (!useEmailCode) {
                    TextButton(
                        enabled = !submitting,
                        onClick = onForgot,
                    ) {
                        Text(
                            readI18n("account.forgotPassword", I18nType.RWPP),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
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
) = AccountRegisterDialog(visible, username, onUsernameChange, onDismiss, inlineDesktop = false, onSwitchToLogin)

@Composable
fun AccountRegisterDialog(
    visible: Boolean,
    username: String,
    onUsernameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    inlineDesktop: Boolean,
    onSwitchToLogin: () -> Unit,
) {
    var step by remember(visible) { mutableIntStateOf(0) }
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
        inlineDesktop = inlineDesktop,
        visible = visible,
        onDismissRequest = { if (!submitting) onDismiss() },
        enableDismiss = !submitting,
    ) { dismiss ->
        AccountAccessCard(
            title = readI18n("account.access.registerTitle", I18nType.RWPP),
            subtitle = readI18n("account.access.registerSubtitle", I18nType.RWPP),
            busy = submitting,
            onClose = dismiss,
        ) {
            AccountAccessSteps(step, listOf(
                readI18n("account.access.profileStep", I18nType.RWPP),
                readI18n("account.access.emailStep", I18nType.RWPP),
                readI18n("account.access.passwordStep", I18nType.RWPP),
            ))
            when (step) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountAccessField(
                        label = readI18n("account.username", I18nType.RWPP),
                        value = username,
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        onValueChange = onUsernameChange,
                    )
                    AccountAccessField(
                        label = readI18n("account.nicknameOptional", I18nType.RWPP),
                        value = nickname,
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Face, contentDescription = null) },
                        onValueChange = { nickname = it },
                    )
                    Text(readI18n("account.access.usernameHelp", I18nType.RWPP),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                1 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountAccessField(
                        label = readI18n("account.email", I18nType.RWPP),
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
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
                        AccountAccessField(
                            label = readI18n("account.code", I18nType.RWPP),
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
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
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountAccessField(
                        label = readI18n("account.password", I18nType.RWPP),
                        value = password,
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        visualTransformation = PasswordVisualTransformation(),
                        onValueChange = { password = it },
                    )
                    AccountAccessField(
                        label = readI18n("account.confirmPassword", I18nType.RWPP),
                        value = confirmPassword,
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        visualTransformation = PasswordVisualTransformation(),
                        onValueChange = { confirmPassword = it },
                    )
                    Text(readI18n("account.access.passwordHelp", I18nType.RWPP),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (info.isNotBlank() && error.isBlank()) {
                AccountMessageBanner(info)
            }
            if (error.isNotBlank()) {
                AccountMessageBanner(error, isError = true)
            }
            AccountAuthActions(
                compact = true,
                submitting = submitting,
                confirmLabel = readI18n(if (step < 2) "account.access.next" else "account.register", I18nType.RWPP),
                onCancel = dismiss,
                onConfirm = {
                    if (step < 2) {
                        error = when {
                            step == 0 && !AccountFieldRules.isValidUsername(username) ->
                                readI18n("account.usernameInvalid", I18nType.RWPP)
                            step == 0 && !AccountFieldRules.isValidRegisterNickname(nickname) ->
                                readI18n("account.nicknameInvalid", I18nType.RWPP)
                            step == 1 && !AccountFieldRules.isValidEmail(email) ->
                                readI18n("account.emailInvalid", I18nType.RWPP)
                            step == 1 && !AccountFieldRules.isValidCode(code) ->
                                readI18n("account.codeInvalid", I18nType.RWPP)
                            else -> ""
                        }
                        if (error.isBlank()) { step += 1; info = "" }
                        return@AccountAuthActions
                    }

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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (step > 0) {
                    TextButton(enabled = !submitting, onClick = { step -= 1; error = ""; info = "" }) {
                        Text(readI18n("account.access.previous", I18nType.RWPP))
                    }
                }
                TextButton(
                    enabled = !submitting,
                    onClick = onSwitchToLogin,
                ) {
                    Text(
                        readI18n("account.switchToLogin", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AccountForgotDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onBackToLogin: () -> Unit,
    inlineDesktop: Boolean = false,
) {
    var step by remember(visible) { mutableIntStateOf(0) }
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
        inlineDesktop = inlineDesktop,
        visible = visible,
        onDismissRequest = { if (!submitting) onDismiss() },
        enableDismiss = !submitting,
    ) { dismiss ->
        AccountAccessCard(
            title = readI18n("account.access.forgotTitle", I18nType.RWPP),
            subtitle = readI18n("account.access.forgotSubtitle", I18nType.RWPP),
            busy = submitting,
            onClose = dismiss,
        ) {
            AccountAccessSteps(step, listOf(
                readI18n("account.access.emailStep", I18nType.RWPP),
                readI18n("account.access.passwordStep", I18nType.RWPP),
            ))
            if (step == 0) {
                AccountAccessField(
                    label = readI18n("account.email", I18nType.RWPP),
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
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
                    AccountAccessField(
                        label = readI18n("account.code", I18nType.RWPP),
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
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
            } else {
                AccountAccessField(
                    label = readI18n("account.newPassword", I18nType.RWPP),
                    value = password,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    onValueChange = { password = it },
                )
                AccountAccessField(
                    label = readI18n("account.confirmPassword", I18nType.RWPP),
                    value = confirm,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    onValueChange = { confirm = it },
                )
            }
            if (info.isNotBlank() && error.isBlank()) {
                AccountMessageBanner(info)
            }
            if (error.isNotBlank()) {
                AccountMessageBanner(error, isError = true)
            }
            AccountAuthActions(
                compact = true,
                submitting = submitting,
                confirmLabel = readI18n(if (step == 0) "account.access.next" else "account.resetPassword", I18nType.RWPP),
                onCancel = dismiss,
                onConfirm = {
                    if (step == 0) {
                        error = when {
                            !AccountFieldRules.isValidEmail(email) -> readI18n("account.emailInvalid", I18nType.RWPP)
                            !AccountFieldRules.isValidCode(code) -> readI18n("account.codeInvalid", I18nType.RWPP)
                            else -> ""
                        }
                        if (error.isBlank()) { step = 1; info = "" }
                        return@AccountAuthActions
                    }

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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (step > 0) {
                    TextButton(enabled = !submitting, onClick = { step = 0; error = ""; info = "" }) {
                        Text(readI18n("account.access.previous", I18nType.RWPP))
                    }
                }
                TextButton(
                    enabled = !submitting,
                    onClick = onBackToLogin,
                ) {
                    Text(
                        readI18n("account.switchToLogin", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangeNicknameDialog(
    visible: Boolean,
    current: String,
    onDismiss: () -> Unit,
    inlineDesktop: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var nickname by remember(visible) { mutableStateOf(current) }
    var error by remember(visible) { mutableStateOf("") }
    var submitting by remember(visible) { mutableStateOf(false) }

    AnimatedAlertDialog(
        inlineDesktop = inlineDesktop,
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
    compact: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (compact) {
            AccountAccessSubmit(confirmLabel, submitting, onConfirm)
        } else if (submitting) {
            UiCircularProgressIndicator(
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
        if (!compact) TextButton(enabled = !submitting, onClick = onCancel) {
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
    compact: Boolean = false,
) {
    Box {
        AccountAvatarBox(
            displayName,
            size = if (compact) 48.dp else 84.dp,
            avatar = AccountAvatar(userId, hasAvatar, AccountSession.avatarVersion),
            online = true,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .clickable(onClickLabel = readI18n("account.avatarTitle", I18nType.RWPP), onClick = onClick),
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
        UiSwitch(
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
                UiCircularProgressIndicator(
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
    inlineDesktop: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var confirmingDelete by remember(visible) { mutableStateOf(false) }
    var busy by remember(visible) { mutableStateOf(false) }
    var error by remember(visible) { mutableStateOf("") }
    var info by remember(visible) { mutableStateOf("") }
    val hasAvatar = AccountSession.user?.hasAvatar == true

    AnimatedAlertDialog(
        inlineDesktop = inlineDesktop,
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
                    UiCircularProgressIndicator(
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
    inlineDesktop: Boolean = false,
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
        inlineDesktop = inlineDesktop,
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
