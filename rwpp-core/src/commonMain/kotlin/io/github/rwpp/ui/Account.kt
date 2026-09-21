/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.account.AccountApiException
import io.github.rwpp.net.account.AccountFieldRules
import io.github.rwpp.net.account.RegisterRequest
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
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
    var profileBanner by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        AccountSession.restoreIfNeeded()
        if (AccountSession.loggedIn) {
            runCatching { FriendsSession.refreshLists() }
        }
    }

    LaunchedEffect(loggedIn) {
        if (loggedIn && AccountSession.networkEnabled) {
            while (true) {
                delay(8_000)
                runCatching { FriendsSession.refreshLists() }
            }
        }
    }

    val showLoading = (refreshing || restoring) && loggedIn

    ExpandedCard(
        modifier = Modifier.verticalScroll(rememberScrollState()).autoClearFocus()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            ExitButton(onExit)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isSmall) 14.dp else 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(36.dp))
                Text(
                    readI18n("account.title", I18nType.RWPP),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(if (isSmall) 14.dp else 20.dp))

                when {
                    showLoading -> AccountLoadingState()
                    loggedIn && user != null -> AccountLoggedInState(
                        isSmall = isSmall,
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

                Spacer(Modifier.height(24.dp))
            }
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
    username: String,
    nickname: String,
    email: String?,
    displayName: String,
    banner: String,
    onRefresh: () -> Unit,
    onChangeNickname: () -> Unit,
    onLogout: () -> Unit,
) {
    AccountProfileHeroCard {
        if (isSmall) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AccountAvatarBox(displayName, size = 84.dp)
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
                AccountAvatarBox(displayName, size = 84.dp)
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

    Spacer(Modifier.height(18.dp))
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
    Spacer(Modifier.height(8.dp))
    BorderCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
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

    Spacer(Modifier.height(14.dp))

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
