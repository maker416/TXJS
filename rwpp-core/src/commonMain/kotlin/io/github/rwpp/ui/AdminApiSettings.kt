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
import io.github.rwpp.config.*
import io.github.rwpp.i18n.readI18n
import org.koin.compose.koinInject

@Composable
internal fun AdminApiSettings(unlocked: Boolean, onUnlock: () -> Unit) {
    var showPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var incorrect by remember { mutableStateOf(false) }
    TextButton(onClick = { showPassword = true }, enabled = !unlocked) {
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
    if (!unlocked) return
    val account = koinInject<AccountPreferences>()
    val multiplayer = koinInject<MultiplayerPreferences>()
    val playtime = koinInject<ModPlaytimePreferences>()
    val feedback = koinInject<FeedbackPreferences>()
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(readI18n("settings.adminApiHint"), style = MaterialTheme.typography.bodySmall)
        ApiAddressField(readI18n("settings.adminAccountApi"), account.apiUrl) { account.apiUrl = it }
        ApiAddressField(readI18n("settings.adminForumUrl"), account.forumUrl) { account.forumUrl = it }
        ApiAddressField(readI18n("settings.adminRoomListApi"), multiplayer.roomListApiUrls) { multiplayer.roomListApiUrls = it }
        ApiAddressField(readI18n("settings.adminModSyncApi"), multiplayer.modSyncApiUrls) { multiplayer.modSyncApiUrls = it }
        ApiAddressField(readI18n("settings.adminPlaytimeApi"), playtime.apiUrl) { playtime.apiUrl = it }
        ApiAddressField(readI18n("settings.adminFeedbackApi"), feedback.apiUrl) { feedback.apiUrl = it }
    }
}

@Composable
private fun ApiAddressField(label: String, initial: String, onChange: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it; onChange(it.trim()) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}