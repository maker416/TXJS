/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.account

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
 * - 游戏联机协议零改动；不修改 [AccountSession]。
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

    private var publishJob: Job? = null
    private var eventsBound = false

    /** 订阅断线事件：离房即停止公示并撤销记录（与 [io.github.rwpp.core.ModSyncController.init] 同款 MONITOR 订阅）。 */
    fun init() {
        if (eventsBound) return
        eventsBound = true
        GlobalEventChannel.filter(DisconnectEvent::class).subscribeAlways(priority = EventPriority.MONITOR) {
            stopPublishing()
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
        if (publishJob?.isActive == true) {
            scope.launch { runCatching { publishOnce() } }
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
        publishJob = scope.launch {
            logger.info("[ROOMID] 房间身份公示已启动（间隔 ${PUBLISH_INTERVAL_MS}ms）")
            while (isActive) {
                if (!publishOnce()) break
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    /** 停止公示循环，并尽力向服务端撤销公示记录。 */
    fun stopPublishing() {
        publishJob?.cancel()
        publishJob = null
        playerName = ""
        val keys = roomKeys
        roomKeys = emptyList()
        if (keys.isEmpty()) return
        scope.launch {
            runCatching {
                if (AccountSession.loggedIn && AccountSession.networkEnabled && !featureUnavailable) {
                    newClient().delete(resolveAppKey(), AccountSession.requireToken())
                    logger.info("[ROOMID] 已撤销房间身份公示")
                }
            }.onFailure {
                logger.debug("[ROOMID] 撤销公示失败：${it.message}")
            }
        }
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

    /** 单次公示；返回 false 表示循环应终止（401 / FeatureUnavailable）。 */
    private suspend fun publishOnce(): Boolean {
        if (!AccountSession.loggedIn || !AccountSession.networkEnabled) return true
        // 隐私：对陌生人隐身时不公示自己的房间身份
        if (AccountSession.presenceSettings?.hideFromStrangers == true) return true
        if (featureUnavailable) return false
        val keys = roomKeys
        val name = playerName
        if (keys.isEmpty() || name.isBlank()) return true
        return try {
            newClient().publish(keys, name, resolveAppKey(), AccountSession.requireToken())
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

    private fun resolveAppKey(): String {
        val prefs = runCatching { appKoin.get<AccountPreferences>() }.getOrNull()
        return resolveAccountAppKey(prefs?.appKey.orEmpty())
    }

    private fun newClient(): RoomIdentityClient {
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
