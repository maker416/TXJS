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
import io.github.rwpp.net.sync.direct.DirectJoinEndpoint
import io.github.rwpp.net.sync.direct.DirectModManifest
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
        assertIs<DirectJoinSyncResult.Failed>(result)
    }

    @Test
    fun emptyManifestStillAppliesVanillaAndRefreshesChecksum() = runBlocking<Unit> {
        val manifest = manifest(emptyList())
        val transport = FakeTransport().also { it.manifestResults += { manifest }; it.manifestResults += { manifest } }
        val environment = FakeEnvironment()
        val result = controller(environment, transport).preJoinSync("localhost", LoadingContext {})
        assertIs<DirectJoinSyncResult.Applied>(result)
        assertEquals(listOf(emptyList()), environment.applied)
        assertTrue(environment.refreshed)
    }

    @Test
    fun changedScopeAfterDownloadAbortsWithoutApplying() = runBlocking<Unit> {
        val descriptor = NetworkModDescriptor.fromBytes("m", byteArrayOf(1, 2, 3))
        val first = manifest(listOf(descriptor))
        val second = first.copy(scope = "new-room")
        val transport = FakeTransport().also { it.manifestResults += { first }; it.manifestResults += { second } }
        val environment = FakeEnvironment()
        val result = controller(environment, transport).preJoinSync("localhost", LoadingContext {})
        assertIs<DirectJoinSyncResult.Failed>(result)
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
        assertIs<DirectJoinSyncResult.Failed>(result)
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

    private fun controller(environment: FakeEnvironment, transport: FakeTransport) = DirectModSyncController(environment) { _, _ -> transport }

    private fun manifest(mods: List<NetworkModDescriptor>) = DirectModManifest(1, "1.15", "scope", "ready", true, -1, mods)

    private class FakeTransport : DirectModSyncTransport {
        val manifestResults = mutableListOf<() -> DirectModManifest>()
        val downloads = mutableListOf<NetworkModDescriptor>()
        var manifestCalls = 0
        var onDownload: (NetworkModDescriptor) -> Unit = {}
        override suspend fun getManifest(): DirectModManifest = manifestResults[manifestCalls++].invoke()
        override suspend fun download(descriptor: NetworkModDescriptor, stagingDirectory: File?) {
            downloads += descriptor
            onDownload(descriptor)
        }
    }

    private class FakeEnvironment : DirectModSyncEnvironment {
        override val cache: NetworkModCache = EmptyCache
        val applied = mutableListOf<List<NetworkModDescriptor>>()
        var checksum = 0
        var refreshed = false
        override fun getAllMods(): List<Mod> = emptyList()
        override suspend fun applyMods(descriptors: List<NetworkModDescriptor>) { applied += descriptors }
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
