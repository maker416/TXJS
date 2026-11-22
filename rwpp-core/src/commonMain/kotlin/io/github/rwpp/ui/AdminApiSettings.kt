/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.rwpp.account.AccountSession
import io.github.rwpp.appKoin
import io.github.rwpp.config.*
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.OnlinePresenceTracker
import org.koin.compose.koinInject

internal class AdminApiDraft(
    private val settings: Settings,
    private val account: AccountPreferences,
    private val multiplayer: MultiplayerPreferences,
    private val playtime: ModPlaytimePreferences,
    private val feedback: FeedbackPreferences,
    private val onlinePresence: OnlinePresencePreferences,
) {
    var accountApiUrl by mutableStateOf(account.apiUrl)
    var forumUrl by mutableStateOf(account.forumUrl)
    var roomListApiUrls by mutableStateOf(multiplayer.roomListApiUrls)
    var openPlatformApiUrl by mutableStateOf(settings.openPlatformApiUrl)
    var modSyncApiUrls by mutableStateOf(multiplayer.modSyncApiUrls)
    var playtimeApiUrl by mutableStateOf(playtime.apiUrl)
    var onlinePresenceApiUrl by mutableStateOf(onlinePresence.apiUrl)
    var feedbackApiUrl by mutableStateOf(feedback.apiUrl)

    val invalidOpenPlatformUrl: Boolean
        get() = openPlatformApiUrl.isNotBlank() && normalizeOpenPlatformUrl(openPlatformApiUrl) == null

    fun apply() {
        AccountSession.setApiUrlForSession(accountApiUrl)
        account.forumUrl = forumUrl.trim()
        multiplayer.roomListApiUrls = roomListApiUrls.trim().ifBlank { DEFAULT_ROOM_LIST_API_URLS }
        multiplayer.modSyncApiUrls = modSyncApiUrls.trim().ifBlank { DEFAULT_MOD_SYNC_API_URLS }
        playtime.apiUrl = playtimeApiUrl.trim()
        feedback.apiUrl = feedbackApiUrl.trim()
        onlinePresence.apiUrl = onlinePresenceApiUrl.trim()
        appKoin.getOrNull<OnlinePresenceTracker>()?.refreshEndpoint()
        settings.openPlatformApiUrl = normalizeOpenPlatformUrl(openPlatformApiUrl).orEmpty()
        setOpenPlatformUrlForSession(settings.openPlatformApiUrl)
    }
}

@Composable
internal fun rememberAdminApiDraft(): AdminApiDraft {
    val settings = koinInject<Settings>()
    val account = koinInject<AccountPreferences>()
    val multiplayer = koinInject<MultiplayerPreferences>()
    val playtime = koinInject<ModPlaytimePreferences>()
    val feedback = koinInject<FeedbackPreferences>()
    val onlinePresence = koinInject<OnlinePresencePreferences>()
    return remember { AdminApiDraft(settings, account, multiplayer, playtime, feedback, onlinePresence) }
}

@Composable
internal fun AdminApiSettings(
    draft: AdminApiDraft?,
    onUnlock: () -> Unit,
) {
    var showPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var incorrect by remember { mutableStateOf(false) }
    TextButton(onClick = { showPassword = true }, enabled = draft == null) {
        Text(readI18n("settings.adminApi"))
    }
    if (showPassword) {
        AlertDialog(
            onDismissRequest = { showPassword = false; password = ""; incorrect = false },
            title = { Text(readI18n("settings.adminVerify")) },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; incorrect = false },
                    label = { Text(readI18n("settings.adminPassword")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = incorrect,
                    supportingText = { if (incorrect) Text(readI18n("settings.adminIncorrect")) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (password == "114514") {
                        onUnlock()
                        password = ""
                        showPassword = false
                        incorrect = false
                    } else incorrect = true
                }) { Text(readI18n("settings.adminConfirm")) }
            },
            dismissButton = {
                TextButton(onClick = { showPassword = false; password = ""; incorrect = false }) { Text(readI18n("settings.adminCancel")) }
            },
        )
    }
    if (draft == null) return
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(readI18n("settings.adminApiHint"), style = MaterialTheme.typography.bodySmall)
        ApiAddressField(readI18n("settings.adminAccountApi"), draft.accountApiUrl) { draft.accountApiUrl = it }
        ApiAddressField(readI18n("settings.adminForumUrl"), draft.forumUrl) { draft.forumUrl = it }
        ApiAddressField(readI18n("settings.adminRoomListApi"), draft.roomListApiUrls) { draft.roomListApiUrls = it }
        OutlinedTextField(
            value = draft.openPlatformApiUrl,
            onValueChange = { draft.openPlatformApiUrl = it },
            label = { Text(readI18n("settings.adminOpenPlatformApi")) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = draft.invalidOpenPlatformUrl,
            supportingText = {
                val key = if (draft.invalidOpenPlatformUrl) "settings.adminOpenPlatformApiInvalid" else "settings.adminOpenPlatformApiHint"
                Text(readI18n(key))
            },
        )
        ApiAddressField(readI18n("settings.adminModSyncApi"), draft.modSyncApiUrls) { draft.modSyncApiUrls = it }
        ApiAddressField(readI18n("settings.adminPlaytimeApi"), draft.playtimeApiUrl) { draft.playtimeApiUrl = it }
        ApiAddressField(readI18n("settings.adminOnlinePresenceApi"), draft.onlinePresenceApiUrl) { draft.onlinePresenceApiUrl = it }
        ApiAddressField(readI18n("settings.adminFeedbackApi"), draft.feedbackApiUrl) { draft.feedbackApiUrl = it }
    }
}

@Composable
private fun ApiAddressField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}
