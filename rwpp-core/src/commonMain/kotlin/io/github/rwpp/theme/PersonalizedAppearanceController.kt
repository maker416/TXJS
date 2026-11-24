/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rwpp.account.AccountSession
import io.github.rwpp.account.AccountSessionSnapshot
import io.github.rwpp.appKoin
import io.github.rwpp.app.LauncherVisibility
import io.github.rwpp.app.whileVisible
import io.github.rwpp.config.resolveAccountAppKey
import io.github.rwpp.config.AccountPreferences
import io.github.rwpp.config.resolveOpenPlatformUrl
import io.github.rwpp.core.RoomSnapshotStore
import io.github.rwpp.game.ConnectingPlayer
import io.github.rwpp.game.Game
import io.github.rwpp.game.Player
import io.github.rwpp.net.appearance.AppearanceClient
import io.github.rwpp.net.appearance.GameTestIdentity
import io.github.rwpp.net.appearance.IdentityChatCodec
import io.github.rwpp.net.appearance.VerifiedAppearanceRoster
import io.github.rwpp.net.appearance.VerifiedIdentityChallenge
import io.github.rwpp.net.appearance.VerifiedGameServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 仅消费官方登记游戏节点的真实连接核验，不复用昵称公示或 connectHexId 作为账号证明。
 * 游戏线上只交换有期限的句柄；UAS Bearer 只经 AppearanceClient 发往固定官方 HTTPS 服务。
 */
object PersonalizedAppearanceController {
    private class Transport(val socket: Socket, val endpoint: String, @Volatile var testIdentity: GameTestIdentity?)
    private data class Reply(val nonce: String, val challenge: String, val observer: String)
    private data class Binding(
        val player: Player, val slot: Int, val name: String, val connectionId: String,
        val until: Long, val variants: Map<String, PersonalizedVariantSpec>,
    )
    private data class PlayerCut(val player: Player, val slot: Int, val name: String)
    private data class Presentation(
        val transport: Transport, val theme: ArtThemeController.LoadedTheme, val epoch: Long,
        val bindings: List<Binding>,
    )

    private val transport = AtomicReference<Transport?>()
    private val pendingTestIdentity = AtomicReference<GameTestIdentity?>()
    private val registeredTransport = AtomicReference<Transport?>()
    private val readyTransport = AtomicReference<Transport?>()
    private val nonce = AtomicReference<String?>()
    private val reply = AtomicReference<Reply?>()
    private val acceptedNonce = AtomicReference<String?>()
    private val epoch = AtomicLong()
    private val random = SecureRandom()
    private val invalidationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var presentation by mutableStateOf<Presentation?>(null)
    private var now by mutableLongStateOf(0L)

    /** The join transaction owns this scope until the engine has registered its native socket. */
    internal suspend fun <T> withTestIdentity(identity: GameTestIdentity?, connect: suspend () -> T): T {
        if (identity == null) return connect()
        pendingTestIdentity.set(identity)
        try {
            return connect()
        } finally {
            pendingTestIdentity.compareAndSet(identity, null)
        }
    }

    /** 网络注入回调只捕获 Socket 事实；不在游戏线程做 DNS、HTTP 或 UI 状态写入。 */
    fun onGameSocketConnected(socket: Socket) {
        if (!socket.isConnected || socket.isClosed || transport.get()?.socket === socket) return
        val host = socket.inetAddress?.hostAddress ?: return
        val endpoint = if (':' in host) "[$host]:${socket.port}" else "$host:${socket.port}"
        transport.set(Transport(socket, endpoint, pendingTestIdentity.get()))
        registeredTransport.set(null)
        readyTransport.set(null)
        nonce.set(null)
        reply.set(null)
        acceptedNonce.set(null)
        epoch.incrementAndGet()
    }

    /** 原始包141保留服务器source=0事实，不能使用按昵称猜测归属的UI聊天回调。 */
    fun receiveChatPacket(socket: Socket?, payload: ByteArray): Boolean {
        val current = transport.get() ?: return false
        if (current.socket !== socket || current.socket.isClosed) return false
        val message = IdentityChatCodec.serverControlText(payload) ?: return false
        if (message == IdentityChatCodec.READY_NOTICE) {
            readyTransport.set(current)
            return true
        }
        return receiveControlMessage(message)
    }

    /** 只接收本轮随机 nonce 的回包；旧回复、其他玩家文本和未登记节点不能触发账号请求。 */
    private fun receiveControlMessage(message: String): Boolean {
        if (!message.startsWith("[RWJSID1] ") || message.length != 172) return false
        val parts = message.split(' ')
        if (parts.size != 4 || !AppearanceClient.CLIENT_NONCE.matches(parts[1]) ||
            !AppearanceClient.HEX_TOKEN.matches(parts[2]) || !AppearanceClient.HEX_TOKEN.matches(parts[3]) ||
            parts[1] != nonce.get() || transport.get()?.socket?.isClosed != false) return false
        if (acceptedNonce.compareAndSet(null, parts[1])) {
            reply.set(Reply(parts[1], parts[2], parts[3]))
        }
        return true
    }

    /** 登出/切号先同步作废迟到结果；下一轮在原真实连接执行 clear，再申请新的观察会话。 */
    fun onAccountInvalidated(clearTestIdentity: Boolean = true) {
        if (clearTestIdentity) {
            pendingTestIdentity.set(null)
            transport.get()?.testIdentity = null
        }
        epoch.incrementAndGet()
        nonce.set(null)
        reply.set(null)
        acceptedNonce.set(null)
        val previous = transport.get() ?: return
        invalidationScope.launch {
            if (transport.get() === previous && registeredTransport.get() === previous &&
                nonce.get() == null && !previous.socket.isClosed) {
                runCatching { appKoin.getOrNull<Game>()?.gameRoom?.sendChatMessage("/txjsid clear") }
            }
        }
    }

    fun variantFor(player: Player, slot: String): PersonalizedVariantSpec? {
        val value = presentation ?: return null
        if (value.epoch != epoch.get() || value.transport !== transport.get() ||
            value.transport.socket.isClosed || value.theme !== ArtThemeController.activeTheme ||
            !LauncherVisibility.visible.value) return null
        val timestamp = maxOf(now, System.currentTimeMillis() / 1000)
        return value.bindings.firstOrNull {
            it.player === player && it.slot == player.spawnPoint && it.name == player.name && it.until > timestamp
        }?.variants?.get(slot)
    }

    /** App 持有唯一循环，可见性流取消当前 IO；恢复时重新校验快照和全部有效期。 */
    suspend fun run(game: Game) = coroutineScope {
        var current: Transport? = null
        var account: AccountSessionSnapshot? = null
        var cut = -1L
        var server: VerifiedGameServer? = null
        var lookupAt = 0L
        var helloAt = 0L
        var rosterAt = 0L
        var context: VerifiedIdentityChallenge? = null
        var observer: String? = null
        var rosterRevision = -1L
        var activeTheme: ArtThemeController.LoadedTheme? = null
        var resolution: io.github.rwpp.net.appearance.AppearanceResolution? = null
        var resolvedAccounts = emptySet<String>()
        var resolveAt = 0L
        val client = AppearanceClient(resolveOpenPlatformUrl())
        val clockJob = launch {
            LauncherVisibility.visible.whileVisible {
                while (true) {
                    now = System.currentTimeMillis() / 1000
                    delay(250)
                }
            }
        }
        try {
            LauncherVisibility.visible.whileVisible {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    now = System.currentTimeMillis() / 1000
                    val captured = transport.get()
                    val room = game.gameRoom
                    if (captured == null || captured.socket.isClosed || !AccountSession.networkEnabled ||
                        !RoomSnapshotStore.shouldSample(game) || room.isSinglePlayerGame || !room.isConnecting) {
                        presentation = null
                        delay(500)
                        continue
                    }
                    val session = AccountSession.sessionSnapshotOrNull()
                    if (current !== captured || cut != epoch.get() || account != session) {
                        if (current === captured && registeredTransport.get() === captured && account != null &&
                            (account != session || cut != epoch.get())) {
                            room.sendChatMessage("/txjsid clear")
                        }
                        current = captured
                        cut = epoch.get()
                        account = session
                        server = null
                        lookupAt = 0
                        helloAt = 0
                        rosterAt = 0
                        context = null
                        observer = null
                        rosterRevision = -1
                        resolution = null
                        resolvedAccounts = emptySet()
                        nonce.set(null)
                        reply.set(null)
                        acceptedNonce.set(null)
                        presentation = null
                    }
                    val attemptEpoch = cut
                    fun currentAttempt() = transport.get() === captured && !captured.socket.isClosed &&
                        epoch.get() == attemptEpoch && account == AccountSession.sessionSnapshotOrNull()
                    val testIdentity = captured.testIdentity?.takeIf {
                        it.accountToken == session?.token && it.access.validFor(resolveOpenPlatformUrl())
                    }
                    try {
                        if (now >= lookupAt) {
                            lookupAt = now + 15
                            val found = withContext(Dispatchers.IO) { client.lookupServerInfo(captured.endpoint, testIdentity) }
                            if (!currentAttempt()) continue
                            if (server != found) {
                                context = null
                                observer = null
                                rosterRevision = -1
                                resolution = null
                                helloAt = 0
                                presentation = null
                            }
                            server = found
                            registeredTransport.set(if (found == null) null else captured)
                        }
                        val expectedServer = server
                        if (expectedServer == null || !OnlineGameThemeController.permitsServer(expectedServer)) {
                            presentation = null
                            delay(500)
                            continue
                        }
                        if (readyTransport.get() !== captured) { delay(500); continue }
                        if (now >= helloAt) {
                            val value = ByteArray(16).also(random::nextBytes)
                                .joinToString("") { "%02x".format(it.toInt() and 255) }
                            nonce.set(value)
                            reply.set(null)
                            acceptedNonce.set(null)
                            helloAt = now + 15 // 未回应时有界重试；成功后延长至90秒。
                            room.sendChatMessage("/txjsid hello $value")
                        }
                        val received = reply.getAndSet(null)
                        if (received != null) {
                            val inspected = withContext(Dispatchers.IO) {
                                client.inspectChallenge(received.challenge, received.observer)
                            }
                            if (!currentAttempt()) continue
                            require(inspected.serverId == expectedServer.serverId && inspected.clientNonce == received.nonce &&
                                inspected.expiresAt > now && inspected.expiresAt <= now + 125)
                            context = inspected
                            observer = received.observer
                            rosterRevision = -1
                            rosterAt = 0
                            helloAt = now + 90
                            if (account != null && AccountSession.presenceSettings == null) {
                                AccountSession.refreshPresenceSettings()
                                if (!currentAttempt()) continue
                            }
                            val privacyAllows = AccountSession.presenceSettings?.hideFromStrangers == false
                            val login = account
                            if (login != null && privacyAllows) {
                                val ticket = withContext(Dispatchers.IO) {
                                    client.claim(received.challenge, received.nonce, expectedServer.serverId,
                                        resolveAccountAppKey(appKoin.getOrNull<AccountPreferences>()?.appKey.orEmpty()), login.token, testIdentity)
                                }
                                if (!currentAttempt()) continue
                                require(ticket.expiresAt > now && ticket.expiresAt <= now + 65)
                                room.sendChatMessage("/txjsid prove ${ticket.ticket}")
                            }
                        }
                        val identity = context
                        val readToken = observer
                        val theme = ArtThemeController.activeTheme
                        if (theme !== activeTheme) {
                            activeTheme = theme
                            presentation = null
                            resolution = null
                            resolveAt = 0
                        }
                        if (identity != null && readToken != null && now >= rosterAt) {
                            rosterAt = now + 5
                            // 回包只能绑定请求发起时已存在且始终未被替换的对象，防同名同槽重进继承旧结果。
                            val playerCut = RoomSnapshotStore.snapshot.value.players.map {
                                PlayerCut(it, it.spawnPoint, it.name)
                            }
                            val roster = withContext(Dispatchers.IO) { client.members(identity.roomInstanceId, readToken) }
                            if (!currentAttempt()) continue
                            require(validRoster(roster, identity, rosterRevision, now))
                            rosterRevision = roster.revision
                            val catalog = theme?.personalizedCatalog
                            if (theme == null || catalog == null || !OnlineGameThemeController.permitsAppearance(expectedServer, theme)) {
                                presentation = null
                                continue
                            }
                            val accounts = roster.members.map { it.accountId }.toSet()
                            if (accounts != resolvedAccounts || now >= resolveAt || resolution?.expiresAt?.let { it <= now } != false) {
                                val fetched = withContext(Dispatchers.IO) {
                                    client.resolve(theme.spec.personalization.platformPackId, catalog.catalogHash, accounts.toList(), expectedServer.gameAppId)
                                }
                                if (!currentAttempt() || theme !== ArtThemeController.activeTheme) continue
                                resolution = fetched
                                resolvedAccounts = accounts
                                resolveAt = now + 30
                            }
                            val selected = resolution ?: continue
                            if (selected.expiresAt <= now || selected.expiresAt > now + 65) {
                                presentation = null
                                continue
                            }
                            val players = RoomSnapshotStore.snapshot.value.players
                            val definitions = theme.spec.personalization.sanitized().slots
                            val assignments = selected.assignments.groupBy { it.accountId }
                            val bindings = roster.members.mapNotNull { member ->
                                val sampled = playerCut.singleOrNull { it.slot == member.slot && it.name == member.name }
                                    ?: return@mapNotNull null
                                val player = sampled.player
                                if (player.isAI || player === ConnectingPlayer || player.spawnPoint != sampled.slot ||
                                    player.name != sampled.name || players.count { it.spawnPoint == member.slot } != 1 ||
                                    players.none { it === player }) return@mapNotNull null
                                val variants = assignments[member.accountId].orEmpty().groupBy { it.slot }
                                    .mapNotNull { (slot, values) ->
                                        val choice = values.singleOrNull() ?: return@mapNotNull null
                                        definitions[slot]?.variants?.get(choice.variantId)?.let { slot to it }
                                    }.toMap()
                                Binding(player, member.slot, member.name, member.connectionId,
                                    minOf(roster.expiresAt, member.expiresAt, selected.expiresAt), variants)
                            }
                            presentation = Presentation(captured, theme, attemptEpoch, bindings)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 不记录凭据、挑战或响应正文；无法核验即使用当前主题默认内容。
                        presentation = null
                        context = null
                        observer = null
                        resolution = null
                        server = null
                        lookupAt = now + 5
                        helloAt = now + 5
                        nonce.set(null)
                        reply.set(null)
                        acceptedNonce.set(null)
                    }
                    delay(500)
                }
            }
        } finally {
            clockJob.cancel()
            presentation = null
        }
    }


    internal fun validRoster(roster: VerifiedAppearanceRoster, identity: VerifiedIdentityChallenge,
        previousRevision: Long, now: Long): Boolean =
        roster.serverId == identity.serverId && roster.roomInstanceId == identity.roomInstanceId &&
            roster.connectionId == identity.connectionId && roster.revision >= previousRevision &&
            roster.expiresAt > now && roster.expiresAt <= now + 20 && roster.members.size <= 256 &&
            roster.members.map { it.connectionId }.distinct().size == roster.members.size &&
            roster.members.map { it.slot }.distinct().size == roster.members.size &&
            roster.members.map { it.accountId }.distinct().size == roster.members.size &&
            roster.members.all { it.connectionId.isNotBlank() && it.slot in 0..1023 &&
                AppearanceClient.ACCOUNT_ID.matches(it.accountId) && it.name.length <= 128 &&
                it.expiresAt > now && it.expiresAt <= now + 125 }
}
