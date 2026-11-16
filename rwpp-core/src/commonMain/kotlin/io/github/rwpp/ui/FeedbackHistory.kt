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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.AccountSessionSnapshot
import io.github.rwpp.net.feedback.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun feedbackState(status: String): String = when (status) {
    "open" -> "待处理"
    "investigating" -> "排查中"
    "resolved" -> "已解决"
    else -> status
}

/** 身份 key 的宿主在切号时销毁整个列表，迟到响应另外核验原会话快照。 */
@Composable
internal fun FeedbackHistoryPanel(
    endpoint: String,
    owner: String,
    session: AccountSessionSnapshot?,
    appKey: String,
    client: FeedbackClient,
    history: FeedbackHistoryStore,
) {
    var accountMode by remember { mutableStateOf(session != null) }
    var offset by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reports by remember { mutableStateOf(emptyList<AccountFeedback>()) }
    var statuses by remember { mutableStateOf(emptyList<Pair<String, FeedbackStatus?>>()) }
    var total by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<AccountFeedback?>(null) }
    var loadedPage by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    fun current() = session == null || AccountSession.isCurrentSession(session)
    LaunchedEffect(endpoint, accountMode, offset, refresh) {
        loading = true
        error = ""
        val pageKey = accountMode to offset
        if (loadedPage != pageKey) {
            selected = null
            reports = emptyList()
            statuses = emptyList()
            total = 0
        }
        try {
            if (accountMode && session != null) {
                val result = client.mine(endpoint, FeedbackCredentials(appKey, session.token), offset)
                if (current()) { reports = result.items; total = result.total; loadedPage = pageKey }
            } else {
                val ids = withContext(Dispatchers.IO) { history.list(endpoint, "") }
                val page = ids.drop(offset).take(20)
                val cached = statuses.toMap()
                if (current()) {
                    statuses = page.map { it.id to cached[it.id] }
                    total = ids.size
                    loadedPage = pageKey
                }
                for (entry in page) {
                    val state = try { client.status(endpoint, entry.id) }
                    catch (e: CancellationException) { throw e }
                    catch (e: FeedbackException) {
                        if (e.statusCode == 404) null else throw e
                    }
                    if (current()) statuses = statuses.map { if (it.first == entry.id) entry.id to state else it }
                }
                if (current()) total = ids.size
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (current()) error = e.message ?: "反馈记录暂时无法读取。" }
        finally { if (current()) loading = false }
    }
    if (session != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(accountMode, { accountMode = true; offset = 0 }, label = { Text("账号历史") })
            FilterChip(!accountMode, { accountMode = false; offset = 0 }, label = { Text("本机匿名记录") })
        }
    } else {
        Text("本机匿名记录", style = MaterialTheme.typography.titleMedium)
        Text("匿名记录只在这台设备保存编号。清除应用数据后无法恢复，请妥善保存编号。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { UI.showAccountView = true }) { Text("登录查看账号历史") }
    }
    if (accountMode) Text("只显示当前账号提交的反馈，匿名记录不会自动关联账号。", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { refresh++ }, enabled = !loading) { Text("刷新反馈记录") }
    if (loading) Text("正在读取反馈记录…", color = MaterialTheme.colorScheme.primary)
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    if (!loading && error.isEmpty() && reports.isEmpty() && statuses.isEmpty()) Text("暂无反馈记录。")
    for (report in reports) {
        OutlinedCard(onClick = { selected = if (selected?.id == report.id) null else report }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(report.title, fontWeight = FontWeight.SemiBold)
                Text("${feedbackState(report.status)} · ${report.receivedAt}", color = MaterialTheme.colorScheme.primary)
                Text("编号：${report.id}", style = MaterialTheme.typography.bodySmall)
                if (report.publicReply.isNotBlank()) Text("开发者回复：${report.publicReply}")
                if (selected?.id == report.id) {
                    Text(report.description)
                    if (report.contact.isNotBlank()) Text("联系方式：${report.contact}")
                    if (report.updatedAt.isNotBlank()) Text("最近更新：${report.updatedAt}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    for ((id, state) in statuses) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("编号：$id", style = MaterialTheme.typography.bodySmall)
                Text(state?.let { feedbackState(it.status) } ?: "尚未确认接收，请保留原反馈表单重试。", color = MaterialTheme.colorScheme.primary)
                if (state != null) {
                    Text("接收时间：${state.receivedAt}", style = MaterialTheme.typography.bodySmall)
                    if (state.publicReply.isNotBlank()) Text("开发者回复：${state.publicReply}")
                    if (state.updatedAt.isNotBlank()) Text("最近更新：${state.updatedAt}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    val pageSize = if (accountMode) 50 else 20
    if (total > 0) {
        Text("共 $total 条 · 第 ${offset / pageSize + 1} 页", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ offset = (offset - pageSize).coerceAtLeast(0) }, enabled = !loading && offset > 0) { Text("上一页") }
            OutlinedButton({ offset += pageSize }, enabled = !loading && offset + pageSize < total) { Text("下一页") }
        }
    }
}
