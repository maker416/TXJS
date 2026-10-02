/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.AccountSessionSnapshot
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.ModPlaytimePreferences
import io.github.rwpp.config.resolveModPlaytimeApiUrl
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.DisconnectEvent
import io.github.rwpp.event.events.QuitGameEvent
import io.github.rwpp.event.events.ReturnMainMenuEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.ModPlaytimeClock
import io.github.rwpp.game.mod.ModPlaytimeHash
import io.github.rwpp.game.mod.ModPlaytimeState
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import io.github.rwpp.net.playtime.ModPlaytimeClient
import io.github.rwpp.net.playtime.ModPlaytimeHttpException
import io.github.rwpp.net.playtime.ModPlaytimeReport
import io.github.rwpp.net.playtime.PendingModPlaytimeReport
import io.github.rwpp.net.playtime.PlayedMod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 只读采样引擎状态，不修改引擎线程契约。作用域独立于 App 组合/Activity，游戏画面期间继续运行。
 * 名称/路径在开局冻结，哈希只在 IO 算一次；累计值进持久重试队列，服务端按差额幂等累计。
 */
object ModPlaytimeController : KoinComponent {
    private const val REPORT_INTERVAL_NANOS = 30_000_000_000L
    private const val MAX_PENDING = 256
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val persistenceMutex = Mutex()
    private var started = false
    private var current: Match? = null
    private val pending = mutableListOf<PendingModPlaytimeReport>()
    private val hashJobs = mutableSetOf<Job>()
    private var dirtyVersion = 0L
    private var persistedVersion = -1L
    @Volatile internal var hashOverride: ((File) -> String)? = null

    private class Match(
        val owner: AccountSessionSnapshot,
        val userId: Long,
        val fingerprint: String,
        val apiUrl: String,
        val generation: Long,
        val id: String = UUID.randomUUID().toString(),
        val clock: ModPlaytimeClock = ModPlaytimeClock(),
        var mods: List<PlayedMod>? = null,
        var lastReportNanos: Long = 0,
        var ended: Boolean = false,
        var abandoned: Boolean = false,
    )

    fun start(game: Game) {
        synchronized(lock) {
            if (started) return
            started = true
            // 上一进程已经结束。重发其最后一个已落盘累计值，并关闭该 session。
            recoverPending(get<ModPlaytimePreferences>().pendingReports)
        }
        GlobalEventChannel.filter(QuitGameEvent::class).subscribeAlways { finishMatch(System.nanoTime()) }
        GlobalEventChannel.filter(ReturnMainMenuEvent::class).subscribeAlways { finishMatch(System.nanoTime()) }
        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways { finishMatch(System.nanoTime()) }
        scope.launch {
            var lastSamplingError: String? = null
            while (isActive) {
                try {
                    val identity = AccountSession.playtimeIdentityOrNull()
                    val url = resolveModPlaytimeApiUrl(get<ModPlaytimePreferences>().apiUrl)
                    // 未登录或服务未配置时不读模组、更不计算哈希。
                    val (state, sources) = if (identity != null && url.isNotBlank()) {
                        withContext(Dispatchers.Main.immediate) {
                            val sampled = game.getModPlaytimeState()
                            // Mod 是引擎 getter 的包装。Main 内复制字符串，再交给 IO 哈希，不能跨线程追读。
                            sampled to sampled.mods.map { it.name to it.path }
                        }
                    } else ModPlaytimeState() to emptyList()
                    sample(identity, url, state, System.nanoTime(), sources)
                    lastSamplingError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 采样失败不把未知状态补记为游玩。
                    synchronized(lock) { current?.clock?.sample(System.nanoTime(), false) }
                    if (lastSamplingError != e.message) logger.warn("模组游玩时间采样失败：{}", e.message)
                    lastSamplingError = e.message
                }
                delay(1000)
            }
        }
        scope.launch {
            var previousError: String? = null
            while (isActive) {
                try {
                    persistIfDirty()
                    previousError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (previousError != e.message) logger.warn("模组游玩时间暂未落盘：{}", e.message)
                    previousError = e.message
                }
                delay(1000)
            }
        }
        scope.launch { deliverPending() }
    }

    /** 登录切换/登出开始即调用，旧身份的未交付记录不会借用新 token 上报。 */
    fun onAccountInvalidated() {
        synchronized(lock) {
            current?.abandoned = true
            current = null
            pending.clear()
            dirtyVersion++
        }
    }

    private fun sample(identity: Pair<AccountSessionSnapshot, Long>?, url: String, state: ModPlaytimeState, now: Long,
        capturedSources: List<Pair<String, String>>? = null,
    ) {
        var toHash: Pair<Match, List<Pair<String, String>>>? = null
        synchronized(lock) {
            val old = current
            if (old != null && (identity == null || old.owner != identity.first || old.apiUrl != url)) {
                old.abandoned = true
                current = null
                pending.removeAll { it.report.sessionId == old.id }
                dirtyVersion++
            }
            if (!state.reliable) {
                current?.clock?.sample(now, false)
                return@synchronized
            }
            if (state.hasMatch && current?.generation?.let { it != state.generation } == true) {
                finishMatch(now)
            }
            if (!state.hasMatch) {
                current?.let { match ->
                    match.clock.sample(now, false)
                    match.ended = true
                    enqueue(match)
                }
                current = null
                return@synchronized
            }
            if (current == null && identity != null && url.isNotBlank()) {
                val sources = (capturedSources ?: state.mods.map { it.name to it.path }).map {
                    it.first.trim() to it.second
                }.filter {
                    it.first.isNotBlank() && it.first.codePointCount(0, it.first.length) <= 255 &&
                        it.first.none { c -> c.isISOControl() }
                }.distinct()
                if (sources.isNotEmpty()) {
                    val match = Match(identity.first, identity.second, tokenFingerprint(identity.first.token), url,
                        state.generation, lastReportNanos = now)
                    current = match
                    toHash = match to sources
                }
            }
            current?.let { match ->
                match.clock.sample(now, state.active)
                if (now - match.lastReportNanos >= REPORT_INTERVAL_NANOS) {
                    enqueue(match)
                    match.lastReportNanos = now
                }
            }
        }
        toHash?.let { (match, sources) ->
            val hash = hashOverride ?: ModPlaytimeHash::sha256
            val job = scope.launch(Dispatchers.IO) {
                val cache = runCatching { get<NetworkModCache>() }.getOrNull()
                val mods = sources.mapNotNull { (name, path) ->
                    try {
                        // 激活的网络缓存有经过校验的传输身份；保留房主原名称，避免缓存文件名拆出另一模组。
                        val descriptor = cache?.descriptorForManagedPath(path)
                        PlayedMod(descriptor?.name ?: name, descriptor?.normalizedSha256 ?: hash(File(path)))
                    }
                    catch (e: Exception) {
                        logger.warn("无法识别模组内容，跳过游玩统计 {}：{}", name, e.message)
                        null
                    }
                }.distinct().sortedWith(compareBy<PlayedMod> { it.name }.thenBy { it.sha256 }).take(128)
                val stillAuthenticated = AccountSession.isCurrentSession(match.owner)
                synchronized(lock) {
                    if (!match.abandoned && stillAuthenticated) {
                        match.mods = mods
                        if (match.ended) enqueue(match)
                    }
                }
            }
            synchronized(lock) { hashJobs += job }
            job.invokeOnCompletion { synchronized(lock) { hashJobs -= job } }
        }
    }

    private fun finishMatch(now: Long) {
        synchronized(lock) {
            current?.let { match ->
                match.clock.sample(now, false)
                match.ended = true
                enqueue(match)
            }
            current = null
        }
    }

    private fun recoverPending(items: List<PendingModPlaytimeReport>) {
        synchronized(lock) {
            pending += items.takeLast(MAX_PENDING).map { it.copy(report = it.report.copy(ended = true)) }
            dirtyVersion++
        }
    }

    private fun acknowledge(item: PendingModPlaytimeReport?) {
        synchronized(lock) {
            // 请求中可能已更新成更大的累计值；按完整对象移除，保留尚未确认的更新。
            pending.remove(item)
            dirtyVersion++
        }
    }

    private fun nextPending(identity: Pair<AccountSessionSnapshot, Long>?, configuredUrl: String): PendingModPlaytimeReport? {
        if (identity == null || configuredUrl.isBlank()) return null
        val fingerprint = tokenFingerprint(identity.first.token)
        synchronized(lock) {
            while (pending.isNotEmpty()) {
                val item = pending.first()
                if (item.userId == identity.second && item.tokenFingerprint == fingerprint && item.apiUrl == configuredUrl) return item
                pending.removeAt(0)
                dirtyVersion++
            }
            return null
        }
    }

    /** 不启动轮询/请求的测试入口；经过与生产相同的采样、哈希、结算和队列方法。 */
    internal fun sampleForTests(state: ModPlaytimeState, nowNanos: Long, apiUrl: String = "http://localhost") =
        sample(AccountSession.playtimeIdentityOrNull(), apiUrl, state, nowNanos)
    internal fun finishMatchForTests(nowNanos: Long) = finishMatch(nowNanos)
    internal fun pendingForTests(): List<PendingModPlaytimeReport> = synchronized(lock) { pending.toList() }
    internal fun currentForTests(): String? = synchronized(lock) { current?.id }
    internal suspend fun awaitHashingForTests() { synchronized(lock) { hashJobs.toList() }.forEach { it.join() } }
    internal fun nextPendingForTests(configuredUrl: String = "http://localhost"): PendingModPlaytimeReport? =
        nextPending(AccountSession.playtimeIdentityOrNull(), configuredUrl)
    internal fun acknowledgeForTests(item: PendingModPlaytimeReport) = acknowledge(item)
    internal fun recoverForTests(items: List<PendingModPlaytimeReport>) = recoverPending(items)
    internal fun resetForTests() {
        onAccountInvalidated()
        hashOverride = null
    }

    /** 持有 lock；同 session 始终替换为最新累计值，失败重试不生成额外时长。 */
    private fun enqueue(match: Match) {
        val mods = match.mods ?: return
        if (mods.isEmpty() || match.abandoned || match.clock.elapsedSeconds == 0L) return
        val item = PendingModPlaytimeReport(match.userId, match.fingerprint, match.apiUrl,
            ModPlaytimeReport(match.id, match.clock.elapsedSeconds, mods, match.ended))
        pending.removeAll { it.report.sessionId == match.id }
        pending += item
        if (pending.size > MAX_PENDING) pending.removeAt(0)
        dirtyVersion++
    }

    private suspend fun deliverPending() {
        var backoffMillis = 1000L
        var nextAttemptNanos = 0L
        var previousError: String? = null
        while (scope.isActive) {
            var attemptedItem: PendingModPlaytimeReport? = null
            try {
                persistIfDirty()
                val identity = AccountSession.playtimeIdentityOrNull()
                val configuredUrl = resolveModPlaytimeApiUrl(get<ModPlaytimePreferences>().apiUrl)
                val item = nextPending(identity, configuredUrl)
                if (identity == null || item == null) {
                    delay(1000)
                    continue
                }
                if (System.nanoTime() < nextAttemptNanos) { delay(1000); continue }
                // 再次验证代次。网络调用始终携带捕获的 token，从不读取随后登录的新 token。
                if (!AccountSession.isCurrentSession(identity.first) ||
                    resolveModPlaytimeApiUrl(get<ModPlaytimePreferences>().apiUrl) != item.apiUrl
                ) continue
                attemptedItem = item
                ModPlaytimeClient(item.apiUrl, get<Net>().client).report(identity.first.token, item.report)
                acknowledge(item)
                backoffMillis = 1000L
                nextAttemptNanos = 0L
                previousError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is IllegalArgumentException ||
                    e is ModPlaytimeHttpException && e.status in listOf(400, 401, 403, 409, 413, 422)
                ) {
                    // 拒绝/无效 token 不无限重试；下一局仍可按当前会话继续上报。
                    acknowledge(attemptedItem)
                }
                if (previousError != e.message) logger.warn("模组游玩时间上报暂未成功：{}", e.message)
                previousError = e.message
                nextAttemptNanos = System.nanoTime() + backoffMillis * 1_000_000L
                backoffMillis = (backoffMillis * 2).coerceAtMost(60_000L)
            }
            delay(1000)
        }
    }

    private suspend fun persistIfDirty() = persistenceMutex.withLock {
        val versionAndItems = synchronized(lock) {
            if (persistedVersion == dirtyVersion) return@withLock
            dirtyVersion to pending.toList()
        }
        val preferences = get<ModPlaytimePreferences>()
        preferences.pendingReports = versionAndItems.second
        // 平台针对这个类型在统一保存锁内序列化当前对象，不能用旧 apiUrl 的 copy 覆盖设置页。
        withContext(Dispatchers.IO) { get<ConfigIO>().saveConfig(preferences) }
        synchronized(lock) { persistedVersion = versionAndItems.first }
    }

    private fun tokenFingerprint(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
