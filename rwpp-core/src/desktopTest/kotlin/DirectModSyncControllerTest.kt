/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModCacheEntry
import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.net.ConnectionFailureReason
import io.github.rwpp.net.ConnectionFailureStage
import io.github.rwpp.net.sync.direct.DirectJoinEndpoint
import io.github.rwpp.net.sync.direct.DirectModManifest
import io.github.rwpp.net.sync.direct.DirectModLease
import io.github.rwpp.net.sync.direct.DirectModSyncErrorCode
import io.github.rwpp.net.sync.direct.DirectModSyncServerException
import io.github.rwpp.net.sync.direct.DirectModSyncTransport
import io.github.rwpp.net.sync.direct.DirectModSyncUnsupportedException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class DirectModSyncControllerTest {
    @Test
    fun onlyInitialUnsupportedAllowsRelayFallback() = runBlocking<Unit> {
        val transport = FakeTransport()
        transport.manifestResults += { throw DirectModSyncUnsupportedException("not direct") }
        val environment = FakeEnvironment()
        val result = controller(environment, transport).preJoinSync("127.0.0.1", LoadingContext {})
        assertIs<DirectJoinSyncResult.Unsupported>(result)
        assertTrue(environment.applied.isEmpty())
    }

    @Test
    fun recognizedServerErrorNeverBecomesUnsupported() = runBlocking<Unit> {
        val transport = FakeTransport()
        transport.manifestResults += { throw DirectModSyncServerException(DirectModSyncErrorCode.NOT_LOBBY) }
        val result = controller(FakeEnvironment(), transport).preJoinSync("127.0.0.1:5123", LoadingContext {})
        val failure = assertIs<DirectJoinSyncResult.Failed>(result).failure
        assertEquals(ConnectionFailureReason.NOT_LOBBY, failure.reason)
        assertEquals(ConnectionFailureStage.SYNC_MANIFEST, failure.stage)
    }

    @Test
    fun emptyManifestStillAppliesVanillaAndRefreshesChecksum() = runBlocking<Unit> {
        val manifest = manifest(emptyList())
        val transport = FakeTransport().also { repeat(3) { _ -> it.manifestResults += { manifest } } }
        val environment = FakeEnvironment()
        val result = controller(environment, transport).preJoinSync("localhost", LoadingContext {})
        assertIs<DirectJoinSyncResult.Applied>(result)
        assertEquals(listOf(emptyList()), environment.applied)
        assertTrue(environment.refreshed)
        assertEquals(3, transport.manifestCalls)
    }

    @Test
    fun incompatibleGameVersionStopsBeforeDownloadOrApply() = runBlocking<Unit> {
        val descriptor = NetworkModDescriptor.fromBytes("m", byteArrayOf(1))
        val transport = FakeTransport().also {
            it.manifestResults += { manifest(listOf(descriptor)).copy(gameVersion = "175") }
        }
        val environment = FakeEnvironment()
        val failure = assertIs<DirectJoinSyncResult.Failed>(
            controller(environment, transport).preJoinSync("localhost", LoadingContext {}),
        ).failure
        assertEquals(ConnectionFailureReason.DATA_MISMATCH, failure.reason)
        assertEquals(ConnectionFailureStage.SYNC_MANIFEST, failure.stage)
        assertTrue(transport.downloads.isEmpty())
        assertTrue(environment.applied.isEmpty())
        assertFalse(environment.refreshed)
    }

    @Test
    fun roomChangeDuringApplyStopsBeforeConnecting() = runBlocking<Unit> {
        val original = manifest(emptyList())
        val transport = FakeTransport().also {
            it.manifestResults += { original }
            it.manifestResults += { original }
            it.manifestResults += { original.copy(scope = "next-room") }
        }
        val failure = assertIs<DirectJoinSyncResult.Failed>(
            controller(FakeEnvironment(), transport).preJoinSync("localhost", LoadingContext {}),
        ).failure
        assertEquals(ConnectionFailureReason.MANIFEST_CHANGED, failure.reason)
        assertEquals(ConnectionFailureStage.SYNC_MANIFEST, failure.stage)
        assertEquals(3, transport.manifestCalls)
    }

    @Test
    fun serverStartingDuringApplyStopsBeforeConnecting() = runBlocking<Unit> {
        val original = manifest(emptyList())
        val transport = FakeTransport().also {
            it.manifestResults += { original }
            it.manifestResults += { original }
            it.manifestResults += { throw DirectModSyncServerException(DirectModSyncErrorCode.NOT_LOBBY) }
        }
        val failure = assertIs<DirectJoinSyncResult.Failed>(
            controller(FakeEnvironment(), transport).preJoinSync("localhost", LoadingContext {}),
        ).failure
        assertEquals(ConnectionFailureReason.NOT_LOBBY, failure.reason)
        assertEquals(ConnectionFailureStage.SYNC_MANIFEST, failure.stage)
    }

    @Test
    fun finalRecheckFailureDoesNotUsePreviousReadyManifest() = runBlocking<Unit> {
        val original = manifest(emptyList())
        val transport = FakeTransport().also {
            it.manifestResults += { original }
            it.manifestResults += { original }
            it.manifestResults += { throw DirectModSyncUnsupportedException("server disappeared") }
        }
        val failure = assertIs<DirectJoinSyncResult.Failed>(
            controller(FakeEnvironment(), transport).preJoinSync("localhost", LoadingContext {}),
        ).failure
        assertEquals(ConnectionFailureReason.SYNC_FAILED, failure.reason)
        assertEquals(ConnectionFailureStage.SYNC_MANIFEST, failure.stage)
    }

    @Test
    fun changedScopeAfterDownloadAbortsWithoutApplying() = runBlocking<Unit> {
        val descriptor = NetworkModDescriptor.fromBytes("m", byteArrayOf(1, 2, 3))
        val first = manifest(listOf(descriptor))
        val second = first.copy(scope = "new-room")
        val transport = FakeTransport().also { it.manifestResults += { first }; it.manifestResults += { second } }
        val environment = FakeEnvironment()
        val result = controller(environment, transport).preJoinSync("localhost", LoadingContext {})
        val failure = assertIs<DirectJoinSyncResult.Failed>(result).failure
        assertEquals(ConnectionFailureReason.MANIFEST_CHANGED, failure.reason)
        assertEquals(ConnectionFailureStage.SYNC_MANIFEST, failure.stage)
        assertEquals(listOf(descriptor), transport.downloads)
        assertTrue(environment.applied.isEmpty())
        assertFalse(environment.refreshed)
    }

    @Test
    fun checksumMismatchDoesNotRefreshHandshakeCache() = runBlocking<Unit> {
        val descriptor = NetworkModDescriptor.fromBytes("m", byteArrayOf(4))
        val value = manifest(listOf(descriptor)).copy(hostUnitsChecksum = 99)
        val transport = FakeTransport().also { it.manifestResults += { value }; it.manifestResults += { value } }
        val environment = FakeEnvironment().also { it.checksum = 100 }
        val result = controller(environment, transport).preJoinSync("localhost", LoadingContext {})
        val failure = assertIs<DirectJoinSyncResult.Failed>(result).failure
        assertEquals(ConnectionFailureReason.CHECKSUM_MISMATCH, failure.reason)
        assertEquals(ConnectionFailureStage.SYNC_APPLY, failure.stage)
        assertEquals(listOf(listOf(descriptor)), environment.applied)
        assertFalse(environment.refreshed)
    }

    @Test
    fun cancellationDuringDownloadStopsBeforeApply() = runBlocking<Unit> {
        val descriptor = NetworkModDescriptor.fromBytes("m", byteArrayOf(5))
        val value = manifest(listOf(descriptor))
        val transport = FakeTransport().also {
            it.manifestResults += { value }
            it.onDownload = { throw CancellationException("cancelled") }
        }
        val environment = FakeEnvironment()
        assertFailsWith<CancellationException> {
            controller(environment, transport).preJoinSync("localhost", LoadingContext {})
        }
        assertTrue(environment.applied.isEmpty())
        assertEquals(1, transport.manifestCalls)
    }

    @Test
    fun everyRecognizedDirectCodeKeepsItsStructuredReason() = runBlocking<Unit> {
        val reasons = mapOf(
            DirectModSyncErrorCode.NO_ROOM to ConnectionFailureReason.NO_ROOM,
            DirectModSyncErrorCode.NOT_LOBBY to ConnectionFailureReason.NOT_LOBBY,
            DirectModSyncErrorCode.PREPARING to ConnectionFailureReason.PREPARING,
            DirectModSyncErrorCode.NOT_FOUND to ConnectionFailureReason.NOT_FOUND,
            DirectModSyncErrorCode.BUSY to ConnectionFailureReason.BUSY,
            DirectModSyncErrorCode.BAD_REQUEST to ConnectionFailureReason.BAD_REQUEST,
            DirectModSyncErrorCode.INTERNAL_ERROR to ConnectionFailureReason.INTERNAL_ERROR,
            DirectModSyncErrorCode.ROOM_FULL to ConnectionFailureReason.ROOM_FULL,
            DirectModSyncErrorCode.LEASE_EXPIRED to ConnectionFailureReason.SYNC_FAILED,
            DirectModSyncErrorCode.LEASE_INVALID to ConnectionFailureReason.SYNC_FAILED,
        )
        reasons.forEach { (code, reason) ->
            val transport = FakeTransport().also {
                it.manifestResults += { throw DirectModSyncServerException(code) }
            }
            val failure = assertIs<DirectJoinSyncResult.Failed>(
                controller(FakeEnvironment(), transport).preJoinSync("localhost", LoadingContext {}),
            ).failure
            assertEquals(reason, failure.reason)
            assertEquals(ConnectionFailureStage.SYNC_MANIFEST, failure.stage)
        }
    }

    @Test
    fun downloadFailureUsesOperationStageWithoutGuessingDetails() = runBlocking<Unit> {
        val descriptor = NetworkModDescriptor.fromBytes("m", byteArrayOf(5))
        val transport = FakeTransport().also {
            it.manifestResults += { manifest(listOf(descriptor)) }
            it.onDownload = { throw IllegalStateException("opaque download detail") }
        }
        val failure = assertIs<DirectJoinSyncResult.Failed>(
            controller(FakeEnvironment(), transport).preJoinSync("localhost", LoadingContext {}),
        ).failure
        assertEquals(ConnectionFailureReason.SYNC_FAILED, failure.reason)
        assertEquals(ConnectionFailureStage.SYNC_DOWNLOAD, failure.stage)
        assertTrue(failure.details.contains("opaque download detail"))
    }

    @Test
    fun applyFailureUsesReloadReasonAndPreservesDetails() = runBlocking<Unit> {
        val value = manifest(emptyList())
        val transport = FakeTransport().also { it.manifestResults += { value }; it.manifestResults += { value } }
        val environment = FakeEnvironment().also { it.onApply = { throw IllegalStateException("opaque apply detail") } }
        val failure = assertIs<DirectJoinSyncResult.Failed>(
            controller(environment, transport).preJoinSync("localhost", LoadingContext {}),
        ).failure
        assertEquals(ConnectionFailureReason.RELOAD_FAILED, failure.reason)
        assertEquals(ConnectionFailureStage.SYNC_APPLY, failure.stage)
        assertTrue(failure.details.contains("opaque apply detail"))
        assertFalse(environment.refreshed)
    }

    @Test
    fun unsupportedAfterRecognizedManifestDoesNotFallBackToRelay() = runBlocking<Unit> {
        val transport = FakeTransport().also {
            it.manifestResults += { manifest(emptyList()) }
            it.manifestResults += { throw DirectModSyncUnsupportedException("disappeared") }
        }
        val failure = assertIs<DirectJoinSyncResult.Failed>(
            controller(FakeEnvironment(), transport).preJoinSync("localhost", LoadingContext {}),
        ).failure
        assertEquals(ConnectionFailureStage.SYNC_MANIFEST, failure.stage)
        assertEquals(ConnectionFailureReason.SYNC_FAILED, failure.reason)
    }

    @Test
    fun wrappedCancellationIsNotReportedAsFailure() = runBlocking<Unit> {
        val transport = FakeTransport().also {
            it.manifestResults += { throw IllegalStateException("wrapper", CancellationException("cancelled")) }
        }
        assertFailsWith<IllegalStateException> {
            controller(FakeEnvironment(), transport).preJoinSync("localhost", LoadingContext {})
        }
    }

    @Test
    fun recognizedManifestReadinessFailuresAreExplicit() = runBlocking<Unit> {
        listOf(
            manifest(emptyList()).copy(lobby = false) to ConnectionFailureReason.NOT_LOBBY,
            manifest(emptyList()).copy(status = "preparing") to ConnectionFailureReason.PREPARING,
        ).forEach { (value, reason) ->
            val transport = FakeTransport().also { it.manifestResults += { value } }
            val failure = assertIs<DirectJoinSyncResult.Failed>(
                controller(FakeEnvironment(), transport).preJoinSync("localhost", LoadingContext {}),
            ).failure
            assertEquals(reason, failure.reason)
            assertEquals(ConnectionFailureStage.SYNC_MANIFEST, failure.stage)
            assertEquals(1, transport.manifestCalls)
        }
    }

    @Test
    fun wrappedUnsupportedCancellationDoesNotFallBack() = runBlocking<Unit> {
        val transport = FakeTransport().also {
            it.manifestResults += { throw DirectModSyncUnsupportedException("wrapper", CancellationException("cancelled")) }
        }
        assertFailsWith<DirectModSyncUnsupportedException> {
            controller(FakeEnvironment(), transport).preJoinSync("localhost", LoadingContext {})
        }
    }

    @Test
    fun v2KeepsReservedSlotUntilCallerFinishesActualAdmission() = runBlocking<Unit> {
        val value = manifest(emptyList()).copy(protocolVersion = 2)
        val transport = FakeTransport().also { repeat(3) { _ -> it.manifestResults += { value } } }
        val environment = FakeEnvironment()
        val result = assertIs<DirectJoinSyncResult.Applied>(
            controller(environment, transport).preJoinSync("localhost", LoadingContext {}),
        )
        val reservation = checkNotNull(result.reservation)
        try {
            assertEquals(1, transport.reserveCalls)
            assertEquals(0, transport.releaseCalls)
            assertEquals(1, transport.renewCalls)
            assertEquals(listOf(emptyList()), environment.applied)
            transport.onRenew = { lease().copy(state = "admitted") }
            reservation.awaitAdmission()
        } finally {
            reservation.close()
        }
        assertEquals(1, transport.releaseCalls)
    }

    @Test
    fun fullRoomIsRejectedBeforeAnyDownloadOrApply() = runBlocking<Unit> {
        val descriptor = NetworkModDescriptor.fromBytes("m", byteArrayOf(1))
        val transport = FakeTransport().also {
            it.manifestResults += { manifest(listOf(descriptor)).copy(protocolVersion = 2) }
            it.onReserve = { throw DirectModSyncServerException(DirectModSyncErrorCode.ROOM_FULL) }
        }
        val environment = FakeEnvironment()
        val failure = assertIs<DirectJoinSyncResult.Failed>(
            controller(environment, transport).preJoinSync("localhost", LoadingContext {}),
        ).failure
        assertEquals(ConnectionFailureReason.ROOM_FULL, failure.reason)
        assertEquals(1, transport.reserveCalls)
        assertTrue(transport.downloads.isEmpty())
        assertTrue(environment.applied.isEmpty())
    }

    @Test
    fun failedApplyReleasesV2Reservation() = runBlocking<Unit> {
        val value = manifest(emptyList()).copy(protocolVersion = 2)
        val transport = FakeTransport().also { repeat(2) { _ -> it.manifestResults += { value } } }
        val environment = FakeEnvironment().also { it.onApply = { error("reload failed") } }
        assertIs<DirectJoinSyncResult.Failed>(controller(environment, transport).preJoinSync("localhost", LoadingContext {}))
        assertEquals(1, transport.reserveCalls)
        assertEquals(1, transport.releaseCalls)
    }

    @Test
    fun cancelledDownloadReleasesV2Reservation() = runBlocking<Unit> {
        val descriptor = NetworkModDescriptor.fromBytes("m", byteArrayOf(1))
        val transport = FakeTransport().also {
            it.manifestResults += { manifest(listOf(descriptor)).copy(protocolVersion = 2) }
            it.onDownload = { throw CancellationException("cancelled") }
        }
        assertFailsWith<CancellationException> {
            controller(FakeEnvironment(), transport).preJoinSync("localhost", LoadingContext {})
        }
        assertEquals(1, transport.releaseCalls)
    }

    private fun controller(environment: FakeEnvironment, transport: FakeTransport) = DirectModSyncController(environment) { _, _ -> transport }

    private fun manifest(mods: List<NetworkModDescriptor>) = DirectModManifest(1, "176", "scope", "ready", true, -1, mods)

    private fun lease() = DirectModLease("scope", 90_000, 30_000, "reserved", 1, 10, 1)

    private class FakeTransport : DirectModSyncTransport {
        val manifestResults = mutableListOf<() -> DirectModManifest>()
        val downloads = mutableListOf<NetworkModDescriptor>()
        var manifestCalls = 0
        var reserveCalls = 0
        var renewCalls = 0
        var releaseCalls = 0
        var onReserve: () -> DirectModLease = { DirectModLease("scope", 90_000, 30_000, "reserved", 1, 10, 1) }
        var onRenew: () -> DirectModLease = { DirectModLease("scope", 90_000, 30_000, "reserved", 1, 10, 1) }
        var onDownload: (NetworkModDescriptor) -> Unit = {}
        override suspend fun getManifest(): DirectModManifest = manifestResults[manifestCalls++].invoke()
        override suspend fun download(descriptor: NetworkModDescriptor, stagingDirectory: File?) {
            downloads += descriptor
            onDownload(descriptor)
        }
        override suspend fun reserve(scope: String, token: ByteArray): DirectModLease { reserveCalls++; return onReserve() }
        override suspend fun renew(scope: String, token: ByteArray): DirectModLease { renewCalls++; return onRenew() }
        override suspend fun release(scope: String, token: ByteArray) { releaseCalls++ }
    }

    private class FakeEnvironment : DirectModSyncEnvironment {
        override val cache: NetworkModCache = EmptyCache
        val applied = mutableListOf<List<NetworkModDescriptor>>()
        var checksum = 0
        var refreshed = false
        var onApply: () -> Unit = {}
        override fun getAllMods(): List<Mod> = emptyList()
        override suspend fun applyMods(descriptors: List<NetworkModDescriptor>) {
            onApply()
            applied += descriptors
        }
        override fun getUnitsChecksum(): Int = checksum
        override fun refreshHandshakeChecksumCache() { refreshed = true }
    }

    private object EmptyCache : NetworkModCache {
        override fun prepareStartup() {}
        override fun find(descriptor: NetworkModDescriptor): NetworkModCacheEntry? = null
        override fun storeVerified(descriptor: NetworkModDescriptor, bytes: ByteArray): NetworkModCacheEntry = error("unused")
        override fun activate(descriptor: NetworkModDescriptor): NetworkModCacheEntry = error("unused")
        override fun descriptorForManagedPath(path: String): NetworkModDescriptor? = null
    }
}
