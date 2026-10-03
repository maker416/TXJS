/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.net.Net
import io.github.rwpp.net.account.ForumClientRevocation
import io.github.rwpp.net.account.ForumPreparedLogin
import io.github.rwpp.net.account.ForumProof
import io.github.rwpp.net.account.ForumSsoClient
import io.github.rwpp.net.account.ForumSsoStep
import io.github.rwpp.net.account.ForumSsoStepException
import io.github.rwpp.net.account.ForumSsoRequiresHttpsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/** 与网页生命周期无关的 native 撤销队列；切号响应必须经过账号会话代次检查。 */
object ForumSsoSession : KoinComponent {
    var generation by mutableStateOf(0L)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private fun prefs(): AccountPreferences? = runCatching { get<AccountPreferences>() }.getOrNull()
    private fun client(url: String) = ForumSsoClient(url, AccountSession.httpOverride ?: get<Net>().client)

    fun onAccountInvalidated() {
        generation++
        val pending = prefs()?.forumRevocations.orEmpty().toList()
        if (AccountSession.networkEnabled && pending.isNotEmpty()) scope.launch { mutex.withLock { revoke(pending) } }
    }

    private fun persist() { prefs()?.let { get<ConfigIO>().saveConfig(it) } }

    private suspend fun <T> atStep(step: ForumSsoStep, action: suspend () -> T): T = try {
        action()
    } catch (e: CancellationException) { throw e }
    catch (e: ForumSsoRequiresHttpsException) { throw e }
    catch (e: Exception) { throw ForumSsoStepException(step, e) }

    private suspend fun revoke(pending: List<ForumClientRevocation>) {
        for (record in pending) {
            try {
                client(record.url).revoke(record.key)
                prefs()?.let { it.forumRevocations = it.forumRevocations.filterNot { saved -> saved == record } }
                persist()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* 离线时保留凭证，进入论坛前必须再次尝试。 */ }
        }
    }

    internal suspend fun prepare(url: String, session: AccountSessionSnapshot?): ForumPreparedLogin? = mutex.withLock {
        val pending = prefs()?.forumRevocations.orEmpty().toList()
        revoke(pending)
        // 未成功撤销的同站旧会话不允许被新账号覆盖并遗忘。
        atStep(ForumSsoStep.REVOKE) {
            check(prefs()?.forumRevocations.orEmpty().none { it.url == client(url).baseUrl }) { "Previous forum session could not be revoked" }
        }
        if (session == null) return@withLock null
        check(AccountSession.isCurrentSession(session)) { "Account session changed" }
        val forum = client(url)
        val config = atStep(ForumSsoStep.CONFIG) { forum.config() }
        val proof = ForumProof.create()
        val ticket = atStep(ForumSsoStep.TICKET) { AccountSession.client().issueForumTicket(session.token, config.targetAppCode, proof.challenge) }
        check(AccountSession.isCurrentSession(session)) { "Account session changed" }
        val prepared = atStep(ForumSsoStep.PREPARE) { forum.prepare(ticket.ticket, proof.verifier) }
        val record = ForumClientRevocation(forum.baseUrl, prepared.logoutKey)
        val prefs = atStep(ForumSsoStep.SAVE) { prefs() ?: error("Forum login requires persistent account preferences") }
        prefs.forumRevocations = prefs.forumRevocations + record
        try {
            atStep(ForumSsoStep.SAVE) { persist() }
            if (!AccountSession.isCurrentSession(session)) {
                revoke(listOf(record))
                error("Account session changed")
            }
        } catch (e: Exception) {
            revoke(listOf(record))
            throw e
        }
        prepared
    }
}
