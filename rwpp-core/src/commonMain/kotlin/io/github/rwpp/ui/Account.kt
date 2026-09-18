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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.game.Game
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.login
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject

private const val MOCK_LOADING_DELAY_MS = 450L
private const val MOCK_LOGOUT_DELAY_MS = 280L

private enum class AccountAuthKind {
    Login,
    Register,
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
    val configIO = koinInject<ConfigIO>()
    val game = koinInject<Game>()

    val loggedIn = FakeAccountSession.loggedIn
    val identifier = FakeAccountSession.identifier
    val displayName = FakeAccountSession.displayName

    var authKind by remember { mutableStateOf<AccountAuthKind?>(null) }
    var draftIdentifier by remember { mutableStateOf(FakeAccountSession.lastIdentifier) }
    var refreshing by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showApplyNameConfirm by remember { mutableStateOf(false) }
    var showNameSection by remember { mutableStateOf(false) }

    val showLoading = refreshing && loggedIn

    ExpandedCard(
        modifier = Modifier.verticalScroll(rememberScrollState()).autoClearFocus()
    ) {
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
                    readI18n("account.title", I18nType.RWPP),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(20.dp))

                when {
                    showLoading -> AccountLoadingState()
                    loggedIn -> AccountLoggedInState(
                        isSmall = isSmall,
                        identifier = identifier,
                        displayName = displayName,
                        loggingOut = loggingOut,
                        showNameSection = showNameSection,
                        onToggleNameSection = { showNameSection = !showNameSection },
                        onRefresh = {
                            if (refreshing || loggingOut) return@AccountLoggedInState
                            scope.launch {
                                refreshing = true
                                delay(MOCK_LOADING_DELAY_MS)
                                refreshing = false
                            }
                        },
                        onLogout = { showLogoutConfirm = true },
                        onApplyName = { showApplyNameConfirm = true },
                    )
                    else -> AccountLoggedOutState(
                        isSmall = isSmall,
                        onLogin = {
                            draftIdentifier = FakeAccountSession.lastIdentifier
                            authKind = AccountAuthKind.Login
                        },
                        onRegister = {
                            draftIdentifier = FakeAccountSession.lastIdentifier
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
        identifier = draftIdentifier,
        onIdentifierChange = { draftIdentifier = it },
        onDismiss = { if (authKind == AccountAuthKind.Login) authKind = null },
        onSwitchToRegister = { authKind = AccountAuthKind.Register },
    )

    AccountRegisterDialog(
        visible = authKind == AccountAuthKind.Register,
        identifier = draftIdentifier,
        onIdentifierChange = { draftIdentifier = it },
        onDismiss = { if (authKind == AccountAuthKind.Register) authKind = null },
        onSwitchToLogin = { authKind = AccountAuthKind.Login },
    )

    AnimatedAlertDialog(
        visible = showLogoutConfirm,
        onDismissRequest = { if (!loggingOut) showLogoutConfirm = false },
        enableDismiss = !loggingOut,
    ) { dismiss ->
        BorderCard(
            modifier = accountDialogCardModifier(),
            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    readI18n("account.logoutConfirmTitle", I18nType.RWPP),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    readI18n("account.logoutConfirmBody", I18nType.RWPP),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
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
                                    delay(MOCK_LOGOUT_DELAY_MS)
                                    FakeAccountSession.signOut()
                                    loggingOut = false
                                    showNameSection = false
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

    AnimatedAlertDialog(
        visible = showApplyNameConfirm,
        onDismissRequest = { showApplyNameConfirm = false },
    ) { dismiss ->
        BorderCard(
            modifier = accountDialogCardModifier(),
            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    readI18n("account.applyNameToMultiplayer", I18nType.RWPP),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    readI18n("account.applyNameConfirm", I18nType.RWPP),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = dismiss) {
                        Text(
                            readI18n("common.cancel", I18nType.RWPP),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    TextButton(
                        onClick = {
                            val name = FakeAccountSession.displayName
                            configIO.setGameConfig("lastNetworkPlayerName", name)
                            runCatching { game.setUserName(name) }
                            dismiss()
                        },
                    ) {
                        Text(
                            readI18n("common.ok", I18nType.RWPP),
                            color = MaterialTheme.colorScheme.primary,
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
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            readI18n("account.notLoggedInTitle", I18nType.RWPP),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            readI18n("account.notLoggedInBody", I18nType.RWPP),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(4.dp))
        val buttonMod = if (isSmall) {
            Modifier.fillMaxWidth().widthIn(max = 420.dp)
        } else {
            Modifier
        }
        RWTextButton(
            label = readI18n("account.login", I18nType.RWPP),
            modifier = buttonMod,
            leadingIcon = {
                Icon(
                    painter = painterResource(Res.drawable.login),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            },
            onClick = onLogin,
        )
        TextButton(onClick = onRegister, modifier = buttonMod) {
            Text(
                readI18n("account.register", I18nType.RWPP),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)),
        )
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
    identifier: String,
    displayName: String,
    loggingOut: Boolean,
    showNameSection: Boolean,
    onToggleNameSection: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onApplyName: () -> Unit,
) {
    val initial = displayName.firstOrNull()?.toString() ?: "?"

    if (isSmall) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AccountAvatar(initial)
            AccountIdentityTexts(displayName)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountAvatar(initial)
            AccountIdentityTexts(displayName)
        }
    }

    LargeDividingLine { 16.dp }

    BorderCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccountProfileRow(
                label = readI18n("account.identifier", I18nType.RWPP),
                value = identifier,
            )
            AccountProfileRow(
                label = readI18n("account.displayName", I18nType.RWPP),
                value = displayName,
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    if (isSmall) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RWTextButton(
                label = readI18n("account.refresh", I18nType.RWPP),
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
                onClick = onRefresh,
            )
            if (loggingOut) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
                    Text(
                        readI18n("account.logout", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RWTextButton(
                label = readI18n("account.refresh", I18nType.RWPP),
                onClick = onRefresh,
            )
            if (loggingOut) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = onLogout) {
                    Text(
                        readI18n("account.logout", I18nType.RWPP),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    TextButton(onClick = onToggleNameSection) {
        Icon(
            imageVector = if (showNameSection) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            readI18n("account.applyNameToMultiplayer", I18nType.RWPP),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (showNameSection) {
        Text(
            readI18n("account.multiplayerNameHint", I18nType.RWPP),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        RWTextButton(
            label = readI18n("account.applyNameToMultiplayer", I18nType.RWPP),
            modifier = if (isSmall) Modifier.fillMaxWidth() else Modifier,
            onClick = onApplyName,
        )
    }

    AccountFriendsSection(isSmall = isSmall)
}

@Composable
private fun AccountAvatar(initial: String) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AccountIdentityTexts(displayName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            displayName,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                readI18n("account.loggedIn", I18nType.RWPP),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun AccountProfileRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun AccountLoginDialog(
    visible: Boolean,
    identifier: String,
    onIdentifierChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSwitchToRegister: () -> Unit,
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
            Text(
                readI18n("account.loginTitle", I18nType.RWPP),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                readI18n("account.loginHint", I18nType.RWPP),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            RWSingleOutlinedTextField(
                label = readI18n("account.identifier", I18nType.RWPP),
                value = identifier,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                onValueChange = onIdentifierChange,
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
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            AccountAuthActions(
                submitting = submitting,
                confirmLabel = readI18n("account.login", I18nType.RWPP),
                confirmIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.login),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                    )
                },
                onCancel = dismiss,
                onConfirm = {
                    when {
                        identifier.isBlank() -> error = readI18n("account.identifierRequired", I18nType.RWPP)
                        password.isBlank() -> error = readI18n("account.passwordRequired", I18nType.RWPP)
                        else -> {
                            error = ""
                            submitting = true
                            scope.launch {
                                delay(MOCK_LOADING_DELAY_MS)
                                FakeAccountSession.signIn(identifier)
                                submitting = false
                                dismiss()
                            }
                        }
                    }
                },
            )
            TextButton(
                enabled = !submitting,
                onClick = onSwitchToRegister,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    readI18n("account.switchToRegister", I18nType.RWPP),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
fun AccountRegisterDialog(
    visible: Boolean,
    identifier: String,
    onIdentifierChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSwitchToLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isSmall = LocalWindowManager.current == WindowManager.Small
    var password by remember(visible) { mutableStateOf("") }
    var confirmPassword by remember(visible) { mutableStateOf("") }
    var error by remember(visible) { mutableStateOf("") }
    var submitting by remember(visible) { mutableStateOf(false) }

    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = { if (!submitting) onDismiss() },
        enableDismiss = !submitting,
    ) { dismiss ->
        AccountAuthCard(scrollable = isSmall) {
            Text(
                readI18n("account.registerTitle", I18nType.RWPP),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                readI18n("account.registerHint", I18nType.RWPP),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            RWSingleOutlinedTextField(
                label = readI18n("account.identifier", I18nType.RWPP),
                value = identifier,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                onValueChange = onIdentifierChange,
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
            RWSingleOutlinedTextField(
                label = readI18n("account.confirmPassword", I18nType.RWPP),
                value = confirmPassword,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                onValueChange = { confirmPassword = it },
            )
            if (error.isNotBlank()) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            AccountAuthActions(
                submitting = submitting,
                confirmLabel = readI18n("account.register", I18nType.RWPP),
                onCancel = dismiss,
                onConfirm = {
                    when {
                        identifier.isBlank() -> error = readI18n("account.identifierRequired", I18nType.RWPP)
                        password.isBlank() -> error = readI18n("account.passwordRequired", I18nType.RWPP)
                        password != confirmPassword ->
                            error = readI18n("account.passwordMismatch", I18nType.RWPP)
                        else -> {
                            error = ""
                            submitting = true
                            scope.launch {
                                delay(MOCK_LOADING_DELAY_MS)
                                FakeAccountSession.signIn(identifier)
                                submitting = false
                                dismiss()
                            }
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun accountDialogCardModifier(): Modifier {
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

@Composable
private fun AccountAuthCard(
    scrollable: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isSmall = LocalWindowManager.current == WindowManager.Small
    BorderCard(
        modifier = accountDialogCardModifier(),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
    ) {
        val scrollModifier = if (scrollable) {
            Modifier.verticalScroll(rememberScrollState())
        } else {
            Modifier
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(scrollModifier)
                .autoClearFocus()
                .padding(
                    horizontal = if (isSmall) 14.dp else 18.dp,
                    vertical = if (isSmall) 12.dp else 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (isSmall) 10.dp else 12.dp),
            content = content,
        )
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
    val isSmall = LocalWindowManager.current == WindowManager.Small
    val cancelButton = @Composable {
        TextButton(enabled = !submitting, onClick = onCancel) {
            Text(
                readI18n("common.cancel", I18nType.RWPP),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
    val confirmContent = @Composable {
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(if (isSmall) 32.dp else 40.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            RWTextButton(
                label = confirmLabel,
                modifier = if (isSmall) Modifier.fillMaxWidth() else Modifier,
                leadingIcon = confirmIcon,
                onClick = onConfirm,
            )
        }
    }
    if (isSmall) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            confirmContent()
            cancelButton()
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cancelButton()
            confirmContent()
        }
    }
}
