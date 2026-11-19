/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.net.sync.direct.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.security.SecureRandom
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Process-only credentials; no account identity, address history or configuration persistence. */
object DirectResumeSessions {
    private class Stored(val manifest: DirectModManifest, val secret: ByteArray)
    private val sessions = LinkedHashMap<String, Stored>()
    private fun key(endpoint: DirectJoinEndpoint) = "${endpoint.host.lowercase()}:${endpoint.port}"

    @Synchronized fun store(endpoint: DirectJoinEndpoint, manifest: DirectModManifest, grant: DirectResumeGrant) {
        require(grant.serverInstance == manifest.serverInstance && grant.secret.size == 32)
        sessions.remove(key(endpoint))?.secret?.fill(0)
        while (sessions.size >= 32) sessions.remove(sessions.keys.first())?.secret?.fill(0)
        sessions[key(endpoint)] = Stored(manifest, grant.secret.copyOf())
        grant.secret.fill(0)
    }

    @Synchronized fun open(endpoint: DirectJoinEndpoint, manifest: DirectModManifest, transport: DirectModSyncTransport): DirectResumeConnection? {
        val value = sessions[key(endpoint)] ?: return null
        if (value.manifest.serverInstance != manifest.serverInstance || !sameRecoveryScope(value.manifest, manifest) ||
            value.manifest.contentRevision != manifest.contentRevision || value.manifest.artifact != manifest.artifact ||
            value.manifest.mods != manifest.mods || value.manifest.hostUnitsChecksum != manifest.hostUnitsChecksum) {
            sessions.remove(key(endpoint))?.secret?.fill(0)
            return null
        }
        if (manifest.protocolVersion != 4 || manifest.capabilities and 3 != 3 || manifest.phase != "match") return null
        return DirectResumeConnection(endpoint, manifest, transport, value.secret.copyOf())
    }

    private fun sameRecoveryScope(issued: DirectModManifest, match: DirectModManifest): Boolean {
        if (issued.scope == match.scope) return true
        if (!issued.lobby || match.phase != "match") return false
        // TServer keeps the room session ID and advances the generation when the match starts.
        val separator = issued.scope.lastIndexOf(':')
        if (separator <= 0) return false
        val lobbyGeneration = issued.scope.substring(separator + 1).toLongOrNull() ?: return false
        if (lobbyGeneration !in 0L until Long.MAX_VALUE) return false
        return match.scope == "${issued.scope.substring(0, separator)}:${lobbyGeneration + 1}"
    }

    @Synchronized fun clear() { sessions.values.forEach { it.secret.fill(0) }; sessions.clear() }
}

class DirectResumeConnection internal constructor(
    val endpoint: DirectJoinEndpoint, private val manifest: DirectModManifest,
    private val transport: DirectModSyncTransport, private val secret: ByteArray,
) : AutoCloseable {
    private val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
    private var closed = false

    suspend fun openCredentials(): AutoCloseable {
        check(!closed)
        return DirectJoinReservationCredentials.open(endpoint, secret, 4, resume = true,
            attemptNonceHex = nonce.joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    suspend fun awaitAdmission() {
        val handshakeStarted = TimeSource.Monotonic.markNow()
        var nonceClaimed = false
        try {
            withTimeout(30_000) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    check(!closed)
                    try {
                        when (transport.checkResume(manifest.scope, secret, nonce).state) {
                            "admitted" -> return@withTimeout
                            "claimed" -> nonceClaimed = true
                        }
                    } catch (error: DirectModSyncServerException) {
                        // Until native registration claims the nonce, TServer may report it as invalid.
                        val awaitingClaim = !nonceClaimed && handshakeStarted.elapsedNow() < 5.seconds
                        when (error.code) {
                            DirectModSyncErrorCode.NOT_READY -> Unit
                            DirectModSyncErrorCode.RESUME_INVALID -> if (!awaitingClaim) throw error
                            else -> throw error
                        }
                    }
                    delay(300)
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw java.net.SocketTimeoutException("Server did not confirm the recovered connection")
        }
    }

    override fun close() { if (!closed) { closed = true; secret.fill(0); nonce.fill(0) } }
    override fun toString() = "DirectResumeConnection(<redacted>)"
}
