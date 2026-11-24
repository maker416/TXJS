/*
 * Copyright 2023-2025 RWPP contributors
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.net.ConnectionFailureReason
import io.github.rwpp.net.sync.direct.DirectJoinEndpoint
import io.github.rwpp.net.sync.direct.DirectModManifest
import io.github.rwpp.net.sync.direct.DirectResumeGrant
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectResumeSessionStoreTest {
    private val endpoint = DirectJoinEndpoint("192.0.2.2", 5123)
    private val otherEndpoint = DirectJoinEndpoint("192.0.2.3", 5123)
    private val owner = DirectResumeAccount.registered("https://account.example", 8, "installation")
    private val scope = scope()
    private val lobby = DirectModManifest(4, "176", "room:8", "ready", true, 123, emptyList(), "server-instance", "lobby", 3)
    private val match = lobby.copy(scope = "room:9", lobby = false, phase = "match")
    private val transport = DirectModSyncControllerTest.FakeTransport()

    @Test
    fun coldStartRequiresOriginalAccountPlatformAppServerAndPeer() {
        val vault = TestVault()
        val grant = grant()
        DirectResumeSessionStore { vault }.store(scope, endpoint, lobby, grant)
        assertTrue(grant.secret.all { it == 0.toByte() })
        val restored = DirectResumeSessionStore { vault }
        restored.retainOwner(owner) { true }
        val wrongScopes = listOf(
            scope(owner = DirectResumeAccount.registered("https://different.example", 8, "installation")),
            scope(owner = DirectResumeAccount.registered("https://account.example", 9, "installation")),
            scope(owner = DirectResumeAccount.registered("https://account.example", 8, "new-generation")),
            scope(owner = DirectResumeAccount.guest("installation")),
            scope(platform = "https://different.example"),
            scope(app = "app-2"),
            scope(server = "room-2"),
            DirectResumeScope(scope.account, null, null, null),
        )
        for (wrongScope in wrongScopes) assertMissing(restored.open(wrongScope, endpoint, match, transport))
        assertMissing(restored.open(scope, otherEndpoint, match, transport))
        assertIs<DirectResumeLookup.Ready>(restored.open(scope, endpoint, match, transport)).connection.close()
    }

    @Test
    fun accountBoundaryRejectsLateGrantsAndRemovesPersistedPreviousOwner() {
        val vault = TestVault()
        var current = true
        val oldScope = scope(current = { current })
        val rotatedScope = scope(owner = DirectResumeAccount.guest("new-generation"))
        val store = DirectResumeSessionStore { vault }
        store.store(oldScope, endpoint, lobby, grant())
        current = false
        val late = grant()
        store.store(oldScope, endpoint, lobby, late)
        assertTrue(late.secret.all { it == 0.toByte() })
        assertMissing(store.open(oldScope, endpoint, match, transport))
        store.retainOwner(rotatedScope.account.owner) { true }
        assertNull(vault.bytes)
        store.store(rotatedScope, endpoint, lobby, grant())
        assertIs<DirectResumeLookup.Ready>(DirectResumeSessionStore { vault }.open(rotatedScope, endpoint, match, transport)).connection.close()
    }

    @Test
    fun transientReadFailurePreservesWarmAndExistingGrants() {
        val vault = TestVault()
        DirectResumeSessionStore { vault }.store(scope, endpoint, lobby, grant())
        val restored = DirectResumeSessionStore { vault }
        vault.failRead = true
        restored.store(scope, otherEndpoint, lobby, grant())
        assertEquals(1, vault.writes)
        assertIs<DirectResumeLookup.Ready>(restored.open(scope, otherEndpoint, match, transport)).connection.close()
        vault.failRead = false
        assertIs<DirectResumeLookup.Ready>(restored.open(scope, endpoint, match, transport)).connection.close()
        val cold = DirectResumeSessionStore { vault }
        assertIs<DirectResumeLookup.Ready>(cold.open(scope, endpoint, match, transport)).connection.close()
        assertIs<DirectResumeLookup.Ready>(cold.open(scope, otherEndpoint, match, transport)).connection.close()
    }

    @Test
    fun corruptedVaultCanStoreANewGrant() {
        val vault = TestVault().apply { corrupt = true }
        DirectResumeSessionStore { vault }.store(scope, endpoint, lobby, grant())
        assertIs<DirectResumeLookup.Ready>(
            DirectResumeSessionStore { vault }.open(scope, endpoint, match, transport),
        ).connection.close()
    }

    @Test
    fun rejectsChangedInstanceRoomAndContentAndClearsTheStaleGrant() {
        val changes = listOf(
            match.copy(serverInstance = "other-instance"),
            match.copy(scope = "other-room:9"),
            match.copy(scope = "room:10"),
            match.copy(hostUnitsChecksum = 456),
        )
        for (changed in changes) {
            val vault = TestVault()
            DirectResumeSessionStore { vault }.store(scope, endpoint, lobby, grant())
            val restored = DirectResumeSessionStore { vault }
            assertEquals(ConnectionFailureReason.RESUME_EXPIRED,
                assertIs<DirectResumeLookup.Unavailable>(restored.open(scope, endpoint, changed, transport)).reason)
            assertMissing(DirectResumeSessionStore { vault }.open(scope, endpoint, match, transport))
        }
    }

    @Test
    fun lobbyReportsObservedMatchEndedExactlyOnce() {
        val vault = TestVault()
        val store = DirectResumeSessionStore { vault }
        store.store(scope, endpoint, lobby, grant())
        assertFalse(store.observeLobby(scope, endpoint, lobby))
        assertFalse(store.observeLobby(scope, endpoint, lobby))
        assertIs<DirectResumeLookup.Ready>(store.open(scope, endpoint, match, transport)).connection.close()
        val restored = DirectResumeSessionStore { vault }
        val returned = lobby.copy(scope = "room:10")
        assertTrue(restored.observeLobby(scope, endpoint, returned))
        assertFalse(DirectResumeSessionStore { vault }.observeLobby(scope, endpoint, returned))
    }

    private fun scope(
        owner: String = this.owner,
        platform: String = "https://platform.example",
        app: String = "app-1",
        server: String = "room-1",
        current: () -> Boolean = { true },
    ) = DirectResumeScope(DirectResumeAccount(owner, current), platform, app, server)

    private fun grant() = DirectResumeGrant(lobby.serverInstance, ByteArray(32) { 7 })

    private fun assertMissing(result: DirectResumeLookup) =
        assertEquals(ConnectionFailureReason.RESUME_MISSING, assertIs<DirectResumeLookup.Unavailable>(result).reason)

    private class TestVault : DirectResumeVault {
        var bytes: ByteArray? = null
            private set
        var failRead = false
        var corrupt = false
        var writes = 0
            private set

        override fun read(): ByteArray? {
            if (failRead) throw IOException("Vault is temporarily locked")
            if (corrupt) throw DirectResumeCorruptException("Vault was corrupted")
            return bytes?.copyOf()
        }

        override fun write(bytes: ByteArray) {
            writes++
            this.bytes = bytes.copyOf()
        }

        override fun delete() {
            corrupt = false
            bytes = null
        }
    }
}
