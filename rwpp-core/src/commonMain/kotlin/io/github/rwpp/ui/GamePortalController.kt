/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.content.PortalExpansionHandle
import io.github.rwpp.content.PortalExpansionKey
import io.github.rwpp.net.ConnectionFailure
import io.github.rwpp.net.games.*
import io.github.rwpp.platform.GamePortalBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class PortalContentConsent(val game: OnlineGame, val required: Boolean) {
    val decision = CompletableDeferred<Boolean>()
}

internal class PortalJoinAttempt(val requestId: String, val serverId: String, val document: Long) {
    var preparingJob: Job? = null
    var nativeJob: Job? = null
    var ready: OnlineGameJoinRequest? = null
    var expected: OnlineGame? = null
    var useArt: Boolean = true
}

internal data class GamePortalUiState(
    val attempt: PortalJoinAttempt? = null,
    val ready: OnlineGameJoinRequest? = null,
    val consent: PortalContentConsent? = null,
    val error: String? = null,
    val failure: ConnectionFailure? = null,
    val cancelling: Boolean = false,
)

/** One game, account snapshot and browser lifetime. No bearer credential is part of its public state. */
internal class GamePortalController(
    private val approved: OnlineGame,
    private val currentSession: () -> Boolean,
    private val refresh: suspend () -> OnlineGame?,
    private val player: suspend () -> JsonObject,
    private val authorize: suspend (String) -> JsonObject,
    private val needsConsent: suspend (OnlineGame) -> Boolean,
    private val prepare: suspend (OnlineGame, Boolean) -> Long,
    private val cancelPrepared: suspend (Long) -> Unit,
    private val cancelNative: () -> Unit,
    private val scope: CoroutineScope,
    private val expansionJoinBlock: () -> String? = { null },
    private val preloadedExpansion: () -> PortalExpansionHandle? = { null },
    private val onExpansionJoinBlocked: (String) -> Unit = {},
    private val onExpansionChanged: () -> Unit = {},
) : GamePortalBridge {
    override val origin: String = requireNotNull(onlineGamePortalOrigin(approved.portalUrl))
    override val generation: String = UUID.randomUUID().toString()
    @Volatile private var active = true
    private val document = AtomicLong()
    private var cleanup: Job? = null
    private var emit: (String, JsonObject) -> Unit = { _, _ -> }
    private val expansionKey = PortalExpansionKey.from(approved)
    private data class Receipt(val serverId: String, val state: String, val message: String? = null)
    private val receipts = LinkedHashMap<Pair<Long, String>, Receipt>()
    private val mutable = MutableStateFlow(GamePortalUiState())
    val state: StateFlow<GamePortalUiState> = mutable

    fun bindEvents(emitter: (String, JsonObject) -> Unit) { emit = emitter }
    override fun isActive(): Boolean = active && currentSession()
    override fun onDocumentChanged() {
        val next = document.incrementAndGet()
        scope.launch(Dispatchers.Main.immediate) {
            if (mutable.value.attempt?.document?.let { it < next } == true) cancelJoin()
        }
    }
    private fun checkActive() { if (!isActive()) throw GamePortalException("session_expired") }

    private suspend fun fresh(): OnlineGame {
        checkActive()
        val game = refresh()
        checkActive()
        if (game == null || game.appId != approved.appId || game.portalUrl != approved.portalUrl ||
            game.portalRevision != approved.portalRevision || !isOnlineGamePortalUrl(game.portalUrl)) {
            throw GamePortalException("portal_changed")
        }
        return game
    }

    override suspend fun request(method: String, params: JsonObject): JsonObject = withContext(Dispatchers.Main.immediate) {
        checkActive()
        when (method) {
            "getPlayer" -> { fresh(); player().also { checkActive() } }
            "authorize" -> {
                val nonce = params.text("nonce").orEmpty()
                if (!GamePortalClient.validNonce(nonce)) throw GamePortalException("invalid_nonce")
                fresh()
                authorize(nonce).also { checkActive() }
            }
            "getServers" -> buildJsonObject {
                val game = fresh()
                putJsonArray("servers") { game.servers.forEach { server -> add(buildJsonObject {
                    put("server_id", server.serverId); put("name", server.name)
                    put("available", game.available && server.online && server.available); put("online", server.online)
                    put("current_players", server.currentPlayers); put("max_players", server.maxPlayers)
                    put("closed_message", server.closedMessage)
                }) } }
            }
            "joinServer" -> startJoin(params)
            "cancelJoin" -> {
                val id = params.text("request_id") ?: throw GamePortalException("invalid_request")
                val attempt = mutable.value.attempt
                if (attempt == null || attempt.requestId != id) {
                    val receipt = receipts[document.get() to id] ?: throw GamePortalException("join_request_mismatch")
                    return@withContext receipt.json(id)
                }
                if (attempt.requestId != id) throw GamePortalException("join_request_mismatch")
                cancelJoin()
                cleanup?.join()
                checkActive()
                (receipts[attempt.document to id] ?: Receipt(attempt.serverId, "cancelled")).json(id)
            }
            else -> throw GamePortalException("unsupported_method")
        }
    }

    private fun startJoin(params: JsonObject): JsonObject {
        val serverId = params.text("server_id").orEmpty()
        val requestId = if ("request_id" in params) params.text("request_id") ?: throw GamePortalException("invalid_request")
            else UUID.randomUUID().toString()
        if (!serverId.matches(Regex("[A-Za-z0-9_-]{1,128}")) || !requestId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            throw GamePortalException("invalid_request")
        }
        val key = document.get() to requestId
        receipts[key]?.let { receipt ->
            if (receipt.serverId != serverId) throw GamePortalException("join_request_mismatch")
            return receipt.json(requestId)
        }
        if (mutable.value.attempt != null) throw GamePortalException("join_in_progress")
        receipts.keys.removeAll { it.first != document.get() }
        if (receipts.size >= 256) throw GamePortalException("too_many_requests")
        val attempt = PortalJoinAttempt(requestId, serverId, document.get())
        expansionJoinBlock()?.let { reason ->
            onExpansionJoinBlocked(reason)
            event(attempt, "failed", reason)
            return requireNotNull(receipts[key]).json(requestId)
        }
        mutable.value = GamePortalUiState(attempt = attempt)
        event(attempt, "preparing")
        attempt.preparingJob = scope.launch {
            var token: Long? = null
            try {
                cleanup?.join()
                val game = fresh()
                validateExpansion(game)
                if (!game.available || game.servers.none { it.serverId == serverId && it.online && it.available }) throw GamePortalException("server_unavailable")
                var useArt = true
                if (needsConsent(game)) {
                    val consent = PortalContentConsent(game, game.expansionBinding != null)
                    mutable.value = mutable.value.copy(consent = consent)
                    useArt = consent.decision.await()
                    if (!useArt && consent.required) throw GamePortalException("content_declined")
                    mutable.value = mutable.value.copy(consent = null)
                }
                val ready = prepareOnlineGameJoin(game, serverId, useArt, { fresh().also(::validateExpansion) }, prepare)
                    .copy(preloadedExpansion = preloadedExpansion())
                token = ready.themeToken
                checkActive()
                if (!isCurrent(attempt)) throw CancellationException("Portal join superseded")
                attempt.ready = ready
                attempt.expected = game
                attempt.useArt = useArt
                mutable.value = mutable.value.copy(ready = ready, consent = null)
                event(attempt, "joining")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (isActive() && mutable.value.attempt === attempt) {
                    val code = when (error) {
                        is GamePortalException -> error.code
                        is OnlineGameChangedException -> "game_changed"
                        else -> "join_failed"
                    }
                    event(attempt, if (code == "content_declined") "cancelled" else "failed", code)
                    mutable.value = if (code == "expansion_sync_required") GamePortalUiState() else GamePortalUiState(error = code)
                }
            } finally {
                if (attempt.ready == null && token != null) withContext(NonCancellable) { cancelPrepared(token) }
            }
        }
        return requireNotNull(receipts[key]).json(requestId)
    }

    private fun validateExpansion(game: OnlineGame) {
        if (expansionKey != PortalExpansionKey.from(game)) {
            onExpansionChanged()
            throw GamePortalException("expansion_sync_required")
        }
        expansionJoinBlock()?.let { throw GamePortalException(it) }
    }

    suspend fun attachNative(attempt: PortalJoinAttempt, job: Job) = withContext(Dispatchers.Main.immediate) {
        if (!isCurrent(attempt)) throw CancellationException("Portal join superseded")
        attempt.nativeJob = job
    }
    suspend fun validateJoin(attempt: PortalJoinAttempt) = withContext(Dispatchers.Main.immediate) {
        if (!isCurrent(attempt)) throw CancellationException("Portal join superseded")
        val expected = attempt.expected ?: throw GamePortalException("join_not_active")
        val ready = attempt.ready ?: throw GamePortalException("join_not_active")
        val game = fresh()
        if (!isCurrent(attempt)) throw CancellationException("Portal join superseded")
        validateExpansion(game)
        if (!game.available || game.servers.singleOrNull { it.serverId == attempt.serverId && it.online && it.available }?.endpoint != ready.endpoint ||
            game.expansionBinding != expected.expansionBinding || attempt.useArt && game.artBinding != expected.artBinding) {
            throw GamePortalException("game_changed")
        }
    }
    fun isCurrent(attempt: PortalJoinAttempt): Boolean = isActive() && !mutable.value.cancelling &&
        mutable.value.attempt === attempt && attempt.document == document.get()

    fun finished(attempt: PortalJoinAttempt, success: Boolean, failure: ConnectionFailure? = null) {
        if (!isCurrent(attempt)) return
        event(attempt, if (success) "joined" else "failed", if (success) null else "join_failed")
        mutable.value = GamePortalUiState(error = if (success) null else "join_failed", failure = failure)
    }

    fun dismissError() { mutable.value = mutable.value.copy(error = null, failure = null) }

    fun cancelJoin() {
        val attempt = mutable.value.attempt ?: return
        if (mutable.value.cancelling) return
        mutable.value.consent?.decision?.cancel()
        mutable.value = mutable.value.copy(consent = null, cancelling = true)
        attempt.preparingJob?.cancel()
        attempt.nativeJob?.cancel()
        cancelNative()
        receipts[attempt.document to attempt.requestId] = Receipt(attempt.serverId, "cancelling")
        cleanup = scope.launch(NonCancellable) {
            try {
                attempt.preparingJob?.join()
                attempt.nativeJob?.join()
                attempt.ready?.themeToken?.let { cancelPrepared(it) }
                event(attempt, "cancelled")
                if (mutable.value.attempt === attempt) mutable.value = GamePortalUiState()
            } catch (_: Exception) {
                event(attempt, "failed", "cleanup_failed")
                // Keep admission blocked if engine/content restoration could not finish.
                if (mutable.value.attempt === attempt) mutable.value = mutable.value.copy(error = "cleanup_failed")
            }
        }
    }

    fun close() {
        active = false
        cancelJoin()
        emit = { _, _ -> }
    }

    suspend fun awaitJoinCleanup() {
        val native = mutable.value.attempt?.nativeJob
        cleanup?.join()
        native?.join()
    }

    private fun event(attempt: PortalJoinAttempt, status: String, message: String? = null) {
        receipts[attempt.document to attempt.requestId] = Receipt(attempt.serverId, status, message)
        if (!isActive() || attempt.document != document.get()) return
        emit("joinStateChanged", buildJsonObject {
            put("request_id", attempt.requestId); put("server_id", attempt.serverId); put("state", status)
            message?.let { put("message", it) }
        })
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun Receipt.json(id: String) = buildJsonObject {
        put("request_id", id); put("server_id", serverId); put("state", state)
        message?.let { put("message", it) }
    }
}
