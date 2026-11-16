/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.net.feedback

import io.github.rwpp.config.FeedbackHistoryEntry
import io.github.rwpp.config.FeedbackPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 提交前先保存回执 UUID；网络结果不确定时仍可恢复查询。
 * owner 为空代表本机匿名记录，非空只用于本地账号隔离，不代表服务端身份凭据。
 */
class FeedbackHistoryStore(
    private val preferences: FeedbackPreferences,
    private val persist: (FeedbackPreferences) -> Unit,
) {
    fun record(endpoint: String, owner: String, id: String) = synchronized(preferences) {
        require(id.matches(FEEDBACK_ID)) { "反馈编号格式不正确。" }
        require(owner.length <= 256) { "反馈账号分组无效。" }
        val entry = FeedbackHistoryEntry(normalizeEndpoint(endpoint), owner, id)
        val previous = preferences.history
        // 重试不改变既有记录或其顺序，也不需要重复写盘。
        if (previous.any { it == entry }) return@synchronized
        preferences.history = (listOf(entry) + previous).take(MAX_RECORDS)
        try {
            persist(preferences)
        } catch (error: Exception) {
            preferences.history = previous
            throw error
        }
    }

    fun list(endpoint: String, owner: String): List<FeedbackHistoryEntry> = synchronized(preferences) {
        val normalized = normalizeEndpoint(endpoint)
        preferences.history.filter { entry ->
            entry.endpoint == normalized && entry.owner == owner && entry.id.matches(FEEDBACK_ID)
        }.distinctBy { it.id }
    }

    private fun normalizeEndpoint(endpoint: String): String {
        val url = endpoint.trim().trimEnd('/').toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "反馈服务地址不正确。" }
        return url.toString().trimEnd('/')
    }

    private companion object {
        const val MAX_RECORDS = 1000
        val FEEDBACK_ID = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    }
}
