/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.account

import androidx.compose.runtime.mutableStateMapOf
import io.github.rwpp.appKoin
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.DEFAULT_MOD_SYNC_API_URLS
import io.github.rwpp.config.MultiplayerPreferences
import io.github.rwpp.config.resolveAccountAppKey
import io.github.rwpp.event.EventPriority
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.DisconnectEvent
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.Player
import io.github.rwpp.logger
import io.github.rwpp.net.Net
import io.github.rwpp.net.RoomDescription
import io.github.rwpp.net.account.PublicUser
import io.github.rwpp.net.roomid.RoomIdEntry
import io.github.rwpp.net.roomid.RoomIdFeatureUnavailableException
import io.github.rwpp.net.roomid.RoomIdUnauthorizedException
import io.github.rwpp.net.roomid.RoomIdentityClient
import io.github.rwpp.net.roomid.identityKeysForAddress
import io.github.rwpp.net.roomid.identityKeysForRoomDescription
import io.github.rwpp.net.roomid.prioritizeIdentityKeys
import io.github.rwpp.net.sync.CODE_PREFIX
import io.github.rwpp.net.sync.SID_PREFIX
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 房间内成员名片解析结果（点击成员 → 个人名片的数据源）。 */
sealed interface RoomIdentityResult {
    /** 点的是自己。 */
    data object Self : RoomIdentityResult

    /** 本机未登录账号。 */
    data object NotLoggedIn : RoomIdentityResult

    /** 目标是 AI 玩家（无账号概念，只展示游戏区）。 */
    data object AiPlayer : RoomIdentityResult

    /** relaymod 未提供身份公示功能（全部镜像 404/503，会话内降级）。 */
    data object FeatureUnavailable : RoomIdentityResult

    /** 命中唯一账号（[online] 查询失败时按离线）。 */
    data class Found(val user: PublicUser, val online: Boolean) : RoomIdentityResult

    /** 无匹配：对方未登录账号或未使用支持公示的客户端。 */
    data object NotFound : RoomIdentityResult

    /** 同名候选多于一个，身份不可信，需用户自行甄别。 */
    data class Ambiguous(val users: List<PublicUser>) : RoomIdentityResult

    /** 查询失败（网络错误等），可重试。 */
    data class Error(val message: String) : RoomIdentityResult
}

/**
 * 房间身份公示控制器：「等待房间内点击成员 → 个人名片 → 加好友」的带外机制。
 *
 * - 登录客户端在房内时每 25s 向 relaymod `/api/v1/roomid/publish` 公示
 *   `{room_keys, player_name}`（头带 `X-App-Key` + `Authorization: Bearer`，服务端转发 UAS 核验）；
 * - 点击成员时按 `room_key + player_name` 调 `lookup`，命中得账号三元组，
 *   再调 UAS 公开接口（lookup / presence）补齐名片数据；加好友走 [FriendsSession.sendRequest]。
 * - 与模组同步共用 `MultiplayerPreferences.modSyncApiUrls` 多镜像配置；
 *   404/503（旧版 relay / 未配置该功能）会话内降级为 [featureUnavailable]，不影响其它功能。
 * - 游戏联机协议零改动；账号隐私沿用 [AccountSession] 的设置。
 */
object RoomIdentityController {
    /** 公示周期（服务端 TTL 90s，25s 心跳留有充足余量）。 */
    private const val PUBLISH_INTERVAL_MS = 25_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前公示的候选 key（已按 sid > code > addr 优先级截断至 8 个）。 */
    @Volatile
    private var roomKeys: List<String> = emptyList()

    /** 公示用的玩家名（进房时快照，`lastNetworkPlayerName`）。 */
    @Volatile
    private var playerName: String = ""

    /** 会话内降级标记：全部镜像 404/503 后置位，本次运行不再尝试公示。 */
    @Volatile
    var featureUnavailable: Boolean = false
        private set

    private val publishMutex = Mutex()

    private data class PublishedIdentity(
        val session: AccountSessionSnapshot,
        val client: RoomIdentityClient,
        val appKey: String,
    )
    private var publishedIdentity: PublishedIdentity? = null

    @Volatile
    internal var clientOverride: RoomIdentityClient? = null

    private var publishJob: Job? = null
    private var stopJob: Job? = null
    private var eventsBound = false

    /**
     * 房内真人玩家的名片可用性（行尾「查看名片」按钮状态数据源），key 为 trim 后的玩家名：
     * true=已公示可获取（按钮点亮）；false=确认获取不到（对方未登录账号或非极速版客户端）；
     * 无记录=未知（尚未查询或查询失败）。按钮只在 true 时点亮，其余状态点击仅提示原因。
     */
    val cardAvailability = mutableStateMapOf<String, Boolean>()

    /** 订阅断线事件：离房即停止公示并撤销记录（与 [io.github.rwpp.core.ModSyncController.init] 同款 MONITOR 订阅）。 */
    fun init() {
        if (eventsBound) return
        eventsBound = true
        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways(Dispatchers.Main.immediate, priority = EventPriority.MONITOR) {
            stopPublishing()?.join()
        }
    }

    /**
     * 进房成功时调用（LoadingView 成功块）：暂存候选 key 与玩家名快照。
     * [address] 为本次加入地址（短码 → `code:`，直连 IP/域名 → `addr:`），
     * [desc] 非空时补充列表房间的 `sid:`/`code:` key。房主的 quickHost 指令串产不出 key，
     * 其 key 靠进房后 [addRoomCodeKey] 从 roomDetails 短码补充。
     */
    fun onJoiningRoom(address: String, desc: RoomDescription?) {
        playerName = resolveDisplayName()
        val keys = buildList {
            desc?.let { addAll(identityKeysForRoomDescription(it)) }
            addAll(identityKeysForAddress(address))
        }
        roomKeys = prioritizeIdentityKeys(keys)
        // 新房清空上一房间的可用性标记，避免旧房同名玩家的缓存误导按钮状态
        cardAvailability.clear()
        logger.info("[ROOMID] 进房候选 key：$roomKeys（玩家名 $playerName）")
    }

    /** 补充短码 key（房间短码就绪时调用）并立即重发一次 publish。 */
    fun addRoomCodeKey(code: String) {
        val c = code.trim()
        if (c.isEmpty()) return
        addKeys(listOf("$CODE_PREFIX${c.uppercase()}"))
    }

    /** 补充 server_id 别名 key（发布到列表成功后调用）并立即重发一次 publish。 */
    fun addServerIdKey(serverId: String) {
        val s = serverId.trim()
        if (s.isEmpty()) return
        addKeys(listOf("$SID_PREFIX$s"))
    }

    private fun addKeys(newKeys: List<String>) {
        val merged = prioritizeIdentityKeys(roomKeys + newKeys)
        if (merged == roomKeys) return
        roomKeys = merged
        // 让新 key 尽快生效；未在发布中（未登录/单人房）则跳过，由 startPublishing 后的首个周期兜底
        val publishing = publishJob
        if (publishing?.isActive == true) {
            scope.launch(publishing) { runCatching { publishOnce() } }
        }
    }

    /**
     * 幂等启动 25s 周期公示循环。单人/沙盒房调用方须先过滤（`GameRoom.isSinglePlayerGame`）。
     * 每个周期前置检查：已登录、[AccountSession.networkEnabled]、未开启「对陌生人隐身」、
     * key 集合非空、未标记 [featureUnavailable]；公示失败仅记日志，
     * 401 记 warn 并停循环，FeatureUnavailable 置标记并停循环。
     */
    fun startPublishing() {
        if (publishJob?.isActive == true) return
        val pendingStop = stopJob
        publishJob = scope.launch {
            pendingStop?.join()
            logger.info("[ROOMID] 房间身份公示已启动（间隔 ${PUBLISH_INTERVAL_MS}ms）")
            while (isActive) {
                if (!publishOnce()) break
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    /** 停止公示循环，并尽力向服务端撤销公示记录。 */
    @Synchronized
    fun stopPublishing(): Job? {
        val stoppedPublish = publishJob
        stoppedPublish?.cancel()
        publishJob = null
        playerName = ""
        cardAvailability.clear()
        val keys = roomKeys
        roomKeys = emptyList()
        if (keys.isEmpty() && stoppedPublish == null) return stopJob
        val previousStop = stopJob
        return scope.launch(start = CoroutineStart.LAZY) {
            previousStop?.join()
            stoppedPublish?.join()
            runCatching {
                publishMutex.withLock { revokePublishedIdentity() }
            }.onFailure {
                logger.debug("[ROOMID] 撤销公示失败：${it.message}")
            }
        }.also { stopJob = it; it.start() }
    }

    /**
     * 解析房间内某个玩家的账号身份（名片弹窗数据源）。
     * 查询期间抛 [CancellationException] 时向上传播（弹窗关闭即取消）。
     */
    suspend fun resolvePlayer(player: Player, room: GameRoom): RoomIdentityResult {
        if (player == room.localPlayer) return RoomIdentityResult.Self
        if (player.isAI) return RoomIdentityResult.AiPlayer
        if (!AccountSession.loggedIn || !AccountSession.networkEnabled) return RoomIdentityResult.NotLoggedIn
        if (featureUnavailable) return RoomIdentityResult.FeatureUnavailable
        val keys = roomKeys
        // 本端没有任何 key 意味着自己不可能公示成功，对方亦然（同一房间同一推导逻辑），直接按未命中处理
        if (keys.isEmpty()) return RoomIdentityResult.NotFound
        return try {
            val entries = newClient().lookup(keys, player.name.trim(), resolveAppKey(), AccountSession.requireToken())
            when (entries.size) {
                0 -> RoomIdentityResult.NotFound
                1 -> enrich(entries[0])?.let { (user, online) -> RoomIdentityResult.Found(user, online) }
                    ?: RoomIdentityResult.NotFound
                else -> {
                    val users = entries.mapNotNull { lookupUser(it.username) }
                    if (users.isEmpty()) RoomIdentityResult.NotFound else RoomIdentityResult.Ambiguous(users)
                }
            }
        } catch (e: RoomIdFeatureUnavailableException) {
            featureUnavailable = true
            RoomIdentityResult.FeatureUnavailable
        } catch (e: RoomIdUnauthorizedException) {
            RoomIdentityResult.Error(e.message ?: "unauthorized")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RoomIdentityResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 预取房内真人玩家（除自己外）的名片可用性，写入 [cardAvailability]，驱动行尾「查看名片」按钮置灰。
     * 由房间视图的周期循环调用；单个玩家查询失败保持未知（下轮再试），不做整批失败处理。
     * 自己永远可打开名片（[resolvePlayer] 返回 Self），不参与预取。
     */
    suspend fun prefetchPlayerCards(room: GameRoom) {
        val self = room.localPlayer
        val names = room.getPlayers()
            .filter { !it.isAI && it != self }
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        // 清理已离房玩家的标记
        cardAvailability.keys.filter { it !in names }.forEach { cardAvailability.remove(it) }
        // 未登录 / 功能降级时不做判定：按钮保持可点，由名片弹窗解释全局状态（去登录 / 服务端未升级）
        if (!AccountSession.loggedIn || !AccountSession.networkEnabled || featureUnavailable) return
        val keys = roomKeys
        if (keys.isEmpty()) {
            // 同 resolvePlayer 口径：本端无 key 即双方都不可能查到，全部按获取不到处理
            names.forEach { cardAvailability[it] = false }
            return
        }
        val client = newClient()
        val appKey = resolveAppKey()
        val token = AccountSession.requireToken()
        for (name in names) {
            val entries = try {
                client.lookup(keys, name, appKey, token)
            } catch (e: RoomIdFeatureUnavailableException) {
                featureUnavailable = true
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 网络错误等：该玩家保持未知（按钮可点），下一轮再试
                continue
            }
            cardAvailability[name] = entries.isNotEmpty()
        }
    }

    /** 单条命中：UAS lookup 拿 [PublicUser]，presence 拿在线状态（失败按离线）。 */
    private suspend fun enrich(entry: RoomIdEntry): Pair<PublicUser, Boolean>? {
        val user = lookupUser(entry.username) ?: return null
        val online = runCatching {
            AccountSession.client().getPresence(AccountSession.requireToken(), user.id).online
        }.getOrDefault(false)
        return user to online
    }

    /** UAS 公开接口按 username 查账号；失败（含用户不存在）返回 null。 */
    private suspend fun lookupUser(username: String): PublicUser? =
        runCatching {
            AccountSession.client().lookup(AccountSession.requireToken(), username)
        }.getOrNull()

    /** 隐身设置保存后立即撤销；与正在进行的公示串行，避免撤销后旧 PUT 又写回记录。 */
    internal suspend fun onPresenceSettingsChanged() {
        try {
            publishMutex.withLock {
                if (AccountSession.presenceSettings?.hideFromStrangers != false) revokePublishedIdentity()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 隐私设置已保存；保留撤销凭据，下次公示周期继续重试删除。
            logger.warn("[ROOMID] 隐身后的公示撤销失败：${e.message}")
        }
    }

    private suspend fun revokePublishedIdentity() {
        val published = publishedIdentity ?: return
        try {
            published.client.delete(published.appKey, published.session.token)
        } catch (e: RoomIdUnauthorizedException) {
            // 旧账号 Token 已失效，撤销无法重试；由服务端 90s TTL 清理，不能阻断新账号公示。
            publishedIdentity = null
            logger.warn("[ROOMID] 旧公示撤销凭据已失效，等待服务端 TTL 清理")
            return
        }
        publishedIdentity = null
        logger.info("[ROOMID] 已撤销房间身份公示")
    }

    /** 单次公示；返回 false 表示循环应终止（401 / FeatureUnavailable）。 */
    internal suspend fun publishOnce(): Boolean = publishMutex.withLock {
        if (featureUnavailable) return@withLock false
        try {
            val session = AccountSession.sessionSnapshotOrNull()
            if (session == null) {
                if (AccountSession.networkEnabled) revokePublishedIdentity()
                return@withLock true
            }
            if (publishedIdentity?.session?.let { it != session } == true) revokePublishedIdentity()
            val keys = roomKeys
            val name = playerName
            if (keys.isEmpty() || name.isBlank()) return@withLock true
            // 自动恢复登录不会打开账号页；未知隐私先加载，加载失败不得当作允许公示。
            if (AccountSession.presenceSettings == null) AccountSession.refreshPresenceSettings()
            if (!AccountSession.isCurrentSession(session)) return@withLock true
            if (AccountSession.presenceSettings?.hideFromStrangers != false) {
                revokePublishedIdentity()
                return@withLock true
            }
            val published = PublishedIdentity(session, newClient(), resolveAppKey())
            // 先登记撤销凭据，覆盖服务端已写入但响应丢失/请求取消的情况。
            publishedIdentity = published
            published.client.publish(keys, name, published.appKey, session.token)
            if (!AccountSession.isCurrentSession(session) ||
                AccountSession.presenceSettings?.hideFromStrangers != false
            ) revokePublishedIdentity()
            true
        } catch (e: RoomIdUnauthorizedException) {
            logger.warn("[ROOMID] 公示鉴权失败（token 无效），停止发布")
            false
        } catch (e: RoomIdFeatureUnavailableException) {
            featureUnavailable = true
            logger.warn("[ROOMID] 服务端未提供身份公示功能（全部镜像 404/503），会话内降级")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("[ROOMID] 公示失败：${e.message}")
            true
        }
    }

    internal suspend fun resetForTests() {
        stopPublishing()?.join()
        publishMutex.withLock {
            publishedIdentity = null
            clientOverride = null
            featureUnavailable = false
        }
    }

    private fun resolveAppKey(): String {
        val prefs = runCatching { appKoin.get<AccountPreferences>() }.getOrNull()
        return resolveAccountAppKey(prefs?.appKey.orEmpty())
    }

    private fun newClient(): RoomIdentityClient {
        clientOverride?.let { return it }
        val prefs = runCatching { appKoin.get<MultiplayerPreferences>() }.getOrNull()
        val baseUrls = (prefs?.modSyncApiUrls ?: DEFAULT_MOD_SYNC_API_URLS)
            .split(';')
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
        return RoomIdentityClient(baseUrls, appKoin.get<Net>().client)
    }

    /** 与 [io.github.rwpp.core.ModSyncController] 的 resolveDisplayName 同款口径。 */
    private fun resolveDisplayName(): String {
        val raw = runCatching {
            appKoin.get<ConfigIO>().getGameConfig<String?>("lastNetworkPlayerName")
        }.getOrNull().orEmpty().trim()
        val name = raw.ifBlank { "Player" }
        return if (name.length <= 64) name else name.take(64)
    }
}
