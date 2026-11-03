/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.net.sync.direct.DirectJoinEndpoint
import io.github.rwpp.net.sync.direct.DirectModLease
import io.github.rwpp.net.sync.direct.DirectModManifest
import io.github.rwpp.net.sync.direct.DirectModSyncErrorCode
import io.github.rwpp.net.sync.direct.DirectModSyncServerException
import io.github.rwpp.net.sync.direct.DirectModSyncTransport
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DirectJoinReservationTest {
    @Test
    fun lostReserveResponseRetriesOriginalCredentialAndReleasesItAtTheEnd() = runTest(StandardTestDispatcher()) {
        val transport = FakeTransport().apply {
            reserveReply = { attempt ->
                if (attempt == 1) throw IOException("reply lost after reservation was created")
                lease()
            }
        }
        val reservation = acquire(transport)
        try {
            assertEquals(2, transport.reserves.size)
            assertContentEquals(transport.reserves[0].token, transport.reserves[1].token,
                "A lost reply must not allocate a second reservation credential")
        } finally {
            reservation.close()
        }
        assertEquals(1, transport.releases.size)
        assertContentEquals(transport.reserves.first().token, transport.releases.single().token)
        assertTrue(transport.tokenReferences.all { token -> token.all { it == 0.toByte() } })
    }

    @Test
    fun exhaustedReserveRetriesStillReleaseThePossiblyCreatedReservation() = runTest(StandardTestDispatcher()) {
        val transport = FakeTransport().apply { reserveReply = { throw IOException("reply lost") } }
        assertFailsWith<IOException> { acquire(transport) }
        assertEquals(3, transport.reserves.size)
        transport.reserves.forEach { assertContentEquals(transport.reserves.first().token, it.token) }
        assertEquals(1, transport.releases.size)
        assertContentEquals(transport.reserves.first().token, transport.releases.single().token)
        assertTrue(transport.tokenReferences.all { token -> token.all { it == 0.toByte() } })
    }

    @Test
    fun cancellingReserveRetryReleasesTheCredentialBeforeTheNextAttempt() = runTest(StandardTestDispatcher()) {
        val transport = FakeTransport().apply { reserveReply = { throw IOException("reply lost") } }
        val owner = launch {
            val reservation = acquire(transport)
            try { awaitCancellation() } finally { reservation.close() }
        }
        runCurrent()
        assertEquals(1, transport.reserves.size)
        owner.cancelAndJoin()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, transport.reserves.size)
        assertEquals(1, transport.releases.size)
        assertContentEquals(transport.reserves.single().token, transport.releases.single().token)
    }

    @Test
    fun reservationAlreadyExpiredBeforeItsReplyArrivesIsReleasedWithoutStartingHeartbeat() = runTest(StandardTestDispatcher()) {
        val transport = FakeTransport().apply {
            reserveReply = { delay(10_000); lease(ttlMillis = 10_000, renewAfterMillis = 2_000) }
        }
        assertFailsWith<IOException> { acquire(transport) }
        assertEquals(1, transport.reserves.size)
        assertEquals(1, transport.releases.size)
        assertContentEquals(transport.reserves.single().token, transport.releases.single().token)
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(transport.renewals.isEmpty(), "A stale grant cannot start a new reservation lifetime")
    }

    @Test
    fun heartbeatRunsThroughoutLongDownloadAndReload() = runTest(StandardTestDispatcher()) {
        var phase = "download"
        val renewalPhases = mutableListOf<String>()
        val transport = FakeTransport().apply {
            renewReply = { renewalPhases += phase; lease() }
        }
        val reservation = acquire(transport)
        val operation = async {
            reservation.protect {
                delay(70_000)
                phase = "reload"
                delay(70_000)
                "ready"
            }
        }
        try {
            runCurrent()
            advanceTimeBy(70_000)
            runCurrent()
            assertFalse(operation.isCompleted)
            assertTrue(renewalPhases.count { it == "download" } >= 2)
            advanceTimeBy(70_000)
            runCurrent()
            assertEquals("ready", operation.await(), "Renewal must extend the original 90 second lease")
            assertTrue(renewalPhases.count { it == "reload" } >= 2)
            transport.renewals.forEach { assertContentEquals(transport.reserves.single().token, it.token) }
        } finally {
            operation.cancelAndJoin()
            reservation.close()
        }
    }

    @Test
    fun temporaryRenewalFailureRetriesWhileTheCurrentLeaseStillProtectsTheOperation() = runTest(StandardTestDispatcher()) {
        val transport = FakeTransport().apply {
            renewReply = { attempt ->
                if (attempt == 1) throw IOException("temporary connection loss")
                lease()
            }
        }
        val reservation = acquire(transport)
        val operation = async { reservation.protect { delay(100_000); "ready" } }
        try {
            runCurrent()
            advanceTimeBy(31_000)
            runCurrent()
            assertEquals(2, transport.renewals.size)
            assertFalse(operation.isCompleted)
            advanceTimeBy(69_000)
            runCurrent()
            assertEquals("ready", operation.await())
        } finally {
            operation.cancelAndJoin()
            reservation.close()
        }
    }

    @Test
    fun explicitLeaseRevocationCancelsProtectedWorkAndPreventsFurtherWork() = runTest(StandardTestDispatcher()) {
        val transport = FakeTransport().apply {
            renewReply = { throw DirectModSyncServerException(DirectModSyncErrorCode.LEASE_EXPIRED) }
        }
        val reservation = acquire(transport)
        var cancelled = false
        val operation = async {
            assertFailsWith<DirectModSyncServerException> {
                reservation.protect {
                    try { awaitCancellation() } finally { cancelled = true }
                }
            }
        }
        try {
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(operation.isCompleted, "A definitive server rejection must stop the protected operation")
            assertEquals(DirectModSyncErrorCode.LEASE_EXPIRED, operation.await().code)
            assertTrue(cancelled)
            var started = false
            assertFailsWith<DirectModSyncServerException> { reservation.protect { started = true } }
            assertFalse(started)
        } finally {
            operation.cancelAndJoin()
            reservation.close()
        }
        assertEquals(1, transport.releases.size)
    }

    @Test
    fun unreachableServerCannotKeepProtectedWorkAlivePastTheLease() = runTest(StandardTestDispatcher()) {
        val transport = FakeTransport().apply {
            reserveReply = { lease(ttlMillis = 10_000, renewAfterMillis = 2_000) }
            renewReply = { throw IOException("server unreachable") }
        }
        val reservation = acquire(transport)
        var cancelled = false
        val operation = async {
            assertFailsWith<IOException> {
                reservation.protect {
                    try { awaitCancellation() } finally { cancelled = true }
                }
            }
        }
        try {
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(operation.isCompleted, "Network retries must end before the reservation can lapse")
            operation.await()
            assertTrue(cancelled)
        } finally {
            operation.cancelAndJoin()
            reservation.close()
        }
    }

    @Test
    fun slowRenewalCannotKeepProtectedWorkAlivePastTheConfirmedLease() = runTest(StandardTestDispatcher()) {
        val transport = FakeTransport().apply {
            reserveReply = { lease(ttlMillis = 10_000, renewAfterMillis = 2_000) }
            renewReply = { delay(60_000); lease() }
        }
        val reservation = acquire(transport)
        var cancelled = false
        val operation = async {
            assertFailsWith<IOException> {
                reservation.protect {
                    try { awaitCancellation() } finally { cancelled = true }
                }
            }
        }
        try {
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, transport.renewals.size, "Renewal is still waiting for its first response")
            assertTrue(operation.isCompleted, "The expiry watchdog must run independently of the renewal socket")
            operation.await()
            assertTrue(cancelled)
        } finally {
            operation.cancelAndJoin()
            reservation.close()
        }
        assertEquals(1, transport.releases.size)
    }

    @Test
    fun admissionWaitsThroughReservedAndClaimedThenReleasesTheAdmittedCredential() = runTest(StandardTestDispatcher()) {
        var state = "reserved"
        val observed = mutableListOf<String>()
        val transport = FakeTransport().apply {
            renewReply = { observed += state; lease(state) }
        }
        val reservation = acquire(transport)
        val admission = async { reservation.awaitAdmission() }
        try {
            runCurrent()
            assertFalse(admission.isCompleted, "TCP connection without registration is not admission")
            state = "claimed"
            advanceTimeBy(300)
            runCurrent()
            assertFalse(admission.isCompleted, "A claim is not a registered player")
            state = "admitted"
            advanceTimeBy(300)
            runCurrent()
            assertTrue(admission.isCompleted)
            admission.await()
            assertEquals(listOf("reserved", "claimed", "admitted"), observed)
        } finally {
            admission.cancelAndJoin()
            reservation.close()
        }
        // RELEASE remains necessary after admission: it cleans the credential, not the engine player.
        assertEquals(1, transport.releases.size)
        assertContentEquals(transport.reserves.single().token, transport.releases.single().token)
        val renewalsAfterClose = transport.renewals.size
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(renewalsAfterClose, transport.renewals.size)
    }

    @Test
    fun missingAdmissionConfirmationTimesOutInsteadOfOpeningAnUnconfirmedRoom() = runTest(StandardTestDispatcher()) {
        val transport = FakeTransport().apply { renewReply = { lease("claimed") } }
        val reservation = acquire(transport)
        try {
            val error = assertFailsWith<IOException> { reservation.awaitAdmission() }
            assertTrue(error.message.orEmpty().contains("did not confirm"))
        } finally {
            reservation.close()
        }
        assertEquals(1, transport.releases.size)
    }

    @Test
    fun concurrentCloseReleasesOnceAndStopsHeartbeatEvenWhenReleaseFails() = runTest(StandardTestDispatcher()) {
        val transport = FakeTransport().apply {
            releaseReply = { delay(100); throw IOException("release reply lost") }
        }
        val reservation = acquire(transport)
        coroutineScope { repeat(3) { launch { reservation.close() } } }
        reservation.close()
        assertEquals(1, transport.releases.size)
        assertContentEquals(transport.reserves.single().token, transport.releases.single().token)
        assertTrue(transport.tokenReferences.all { token -> token.all { it == 0.toByte() } })
        advanceTimeBy(90_000)
        runCurrent()
        assertTrue(transport.renewals.isEmpty())
        var started = false
        assertFailsWith<IllegalStateException> { reservation.protect { started = true } }
        assertFalse(started)
    }

    private suspend fun TestScope.acquire(transport: FakeTransport): DirectJoinReservation =
        DirectJoinReservation.acquire(DirectJoinEndpoint("127.0.0.1"), transport, SCOPE,
            nowNanos = { testScheduler.currentTime * 1_000_000 })

    private data class CredentialCall(val scope: String, val token: ByteArray)

    private class FakeTransport : DirectModSyncTransport {
        val reserves = mutableListOf<CredentialCall>()
        val renewals = mutableListOf<CredentialCall>()
        val releases = mutableListOf<CredentialCall>()
        val tokenReferences = mutableListOf<ByteArray>()
        var reserveReply: suspend (Int) -> DirectModLease = { lease() }
        var renewReply: suspend (Int) -> DirectModLease = { lease() }
        var releaseReply: suspend () -> Unit = {}

        override suspend fun getManifest(): DirectModManifest = error("Reservation must not request manifests")
        override suspend fun download(descriptor: NetworkModDescriptor, stagingDirectory: File?) =
            error("Reservation must not download mods")

        override suspend fun reserve(scope: String, token: ByteArray): DirectModLease {
            assertEquals(SCOPE, scope)
            reserves += CredentialCall(scope, token.copyOf())
            tokenReferences += token
            return reserveReply(reserves.size)
        }

        override suspend fun renew(scope: String, token: ByteArray): DirectModLease {
            assertEquals(SCOPE, scope)
            renewals += CredentialCall(scope, token.copyOf())
            tokenReferences += token
            return renewReply(renewals.size)
        }

        override suspend fun release(scope: String, token: ByteArray) {
            assertEquals(SCOPE, scope)
            releases += CredentialCall(scope, token.copyOf())
            tokenReferences += token
            releaseReply()
        }
    }

    private companion object {
        const val SCOPE = "room-scope"

        fun lease(state: String = "reserved", ttlMillis: Long = 90_000, renewAfterMillis: Long = 30_000) =
            DirectModLease(SCOPE, ttlMillis, renewAfterMillis, state, 1, 10, if (state == "admitted") 0 else 1)
    }
}
