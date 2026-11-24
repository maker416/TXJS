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
import java.security.SecureRandom
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class DirectResumeConnection internal constructor(
    val endpoint: DirectJoinEndpoint, private val manifest: DirectModManifest,
    private val transport: DirectModSyncTransport, private val secret: ByteArray,
    private val account: DirectResumeAccount,
) : AutoCloseable {
    private val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
    private var closed = false

    suspend fun openCredentials(): AutoCloseable {
        check(!closed)
        account.ensureCurrent()
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
                    account.ensureCurrent()
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
