/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rwpp.AppContext
import io.github.rwpp.account.AccountSession
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.DEFAULT_ONLINE_CHANNEL
import io.github.rwpp.config.FeedbackPreferences
import io.github.rwpp.config.resolveFeedbackApiUrl
import io.github.rwpp.config.resolveAccountApiUrl
import io.github.rwpp.config.resolveAccountAppKey
import io.github.rwpp.diagnostics.RuntimeLogs
import io.github.rwpp.net.feedback.FeedbackClient
import io.github.rwpp.net.feedback.FeedbackReport
import io.github.rwpp.net.feedback.FeedbackCredentials
import io.github.rwpp.net.feedback.FeedbackHistoryStore
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.projectVersion
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.v2.ExpandedCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.time.OffsetDateTime
import java.util.UUID
import java.security.MessageDigest

@Composable
fun FeedbackView(initialHistory: Boolean = false, onExit: () -> Unit) {
    val appContext = koinInject<AppContext>()
    val preferences = koinInject<FeedbackPreferences>()
    val accountPreferences = koinInject<AccountPreferences>()
    val configIO = koinInject<ConfigIO>()
    val endpoint = resolveFeedbackApiUrl(preferences.apiUrl)
    val session = AccountSession.sessionSnapshotOrNull()
    val account = AccountSession.user
    val appKey = resolveAccountAppKey(accountPreferences.appKey)
    val owner = if (session != null && account != null) MessageDigest.getInstance("SHA-256")
        .digest("${resolveAccountApiUrl(accountPreferences.apiUrl)}|$appKey|${account.id}".toByteArray())
        .joinToString("") { "%02x".format(it) } else ""
    val token = AccountSession.token
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var requestId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    var receipt by rememberSaveable { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(initialHistory) }
    var useAccount by rememberSaveable(owner) { mutableStateOf(owner.isNotEmpty()) }
    var uploadJob by remember { mutableStateOf<Job?>(null) }
    var summary by remember { mutableStateOf("正在读取日志…") }
    val scope = rememberCoroutineScope()
    val client = remember { FeedbackClient() }
    val history = remember(preferences, configIO) { FeedbackHistoryStore(preferences, configIO::saveConfig) }
    LaunchedEffect(endpoint, token, owner) {
        // 切号和登出立即作废上传回写；已预存的原账号/匿名回执不会串到新账号。
        uploadJob?.cancel()
        uploadJob = null
        busy = false
        receipt = ""
        status = ""
        requestId = UUID.randomUUID().toString()
    }
    LaunchedEffect(Unit) {
        summary = withContext(Dispatchers.IO) {
            val files = RuntimeLogs.recentFiles()
            if (files.isEmpty()) "暂无可用日志，请重启应用后再试。"
            else "将附带最近七天内的 ${files.size} 天日志（约 ${files.sumOf { it.length } / 1024} KiB），包含今天。"
        }
    }
    // Leaving disposes the form's coroutine scope and cancels the active HTTP call.
    BackHandler(true, onExit)
    ExpandedCard(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Box {
            ExitButton(onExit)
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("问题反馈", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 48.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showHistory, onClick = { showHistory = false }, label = { Text("提交反馈") })
                    FilterChip(selected = showHistory, onClick = { showHistory = true }, label = { Text("反馈记录") })
                }
                if (showHistory) {
                    key(endpoint, token, owner) {
                        FeedbackHistoryPanel(endpoint, owner, session, appKey, client, history)
                    }
                    return@Column
                }
                Text("请描述发生了什么、出现问题前的操作和大致时间，方便我们结合日志排查。")
                if (session == null) {
                    Text("未登录也可提交，本机会保存反馈编号以便查询状态。登录后提交可在其他设备查看账号历史。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { UI.showAccountView = true }, enabled = !busy) { Text("登录账号") }
                } else {
                    Row {
                        Checkbox(useAccount, { useAccount = it; requestId = UUID.randomUUID().toString() }, enabled = !busy)
                        Text("关联当前账号：${AccountSession.displayName}", modifier = Modifier.padding(top = 10.dp))
                    }
                }
                if (receipt.isNotEmpty()) {
                    Text("反馈已收到\n反馈编号：$receipt", color = MaterialTheme.colorScheme.primary)
                    Button(onClick = { receipt = ""; status = ""; title = ""; description = ""; requestId = UUID.randomUUID().toString() }) {
                        Text("再写一条反馈")
                    }
                } else {
                    OutlinedTextField(title, { title = it.take(120); requestId = UUID.randomUUID().toString() },
                        label = { Text("问题标题") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(description, { description = it.take(10000); requestId = UUID.randomUUID().toString() },
                        label = { Text("问题描述与复现步骤") }, minLines = 5, maxLines = 12,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(contact, { contact = it.take(200); requestId = UUID.randomUUID().toString() },
                        label = { Text("联系方式（选填，QQ 或邮箱）") }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        Checkbox(consent, { consent = it }, enabled = !busy)
                        Text("我同意随反馈发送最近七天的运行日志、应用版本和系统信息。日志可能包含房间地址、聊天内容及文件路径，请勿在反馈中填写密码。",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                    }
                    if (endpoint.isBlank()) Text("反馈服务暂未开放，可先通过说明页的官方交流群反馈。")
                    if (status.isNotEmpty()) Text(status, color = if (busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Button(enabled = !busy && consent && endpoint.isNotBlank() && title.isNotBlank() && description.isNotBlank(),
                        onClick = {
                            busy = true
                            status = "正在打包并上传日志，请稍候…"
                            val sessionSnapshot = if (useAccount) session else null
                            val uploadOwner = if (sessionSnapshot != null) owner else ""
                            val credentials = sessionSnapshot?.let { FeedbackCredentials(appKey, it.token) }
                            val uploadId = requestId
                            val uploadTitle = title.trim()
                            val uploadDescription = description.trim()
                            val uploadContact = contact.trim()
                            fun current(): Boolean = endpoint == resolveFeedbackApiUrl(preferences.apiUrl) &&
                                if (sessionSnapshot != null) AccountSession.isCurrentSession(sessionSnapshot)
                                else AccountSession.token == token
                            uploadJob = scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        // 必须先落盘 UUID，响应丢失或取消后仍可查询；落盘失败不发送日志。
                                        history.record(endpoint, uploadOwner, uploadId)
                                        RuntimeLogs.createArchive().use { archive ->
                                            client.submit(endpoint, FeedbackReport(uploadId, uploadTitle, uploadDescription, uploadContact,
                                                if (appContext.isAndroid()) "android" else "desktop",
                                                "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
                                                projectVersion, DEFAULT_ONLINE_CHANNEL, OffsetDateTime.now().toString(), archive.days), archive, credentials)
                                        }
                                    }
                                    if (current()) { receipt = result.id; status = "" }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    if (current()) status = error.message ?: "提交失败，请检查网络后重试。"
                                } finally { if (current()) busy = false }
                            }
                        }) { Text(if (busy) "正在提交…" else "提交反馈并上传日志") }
                }
            }
        }
    }
}
