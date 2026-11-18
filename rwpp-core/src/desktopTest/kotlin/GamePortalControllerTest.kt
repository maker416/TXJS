/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.expansion.ExpansionArtBinding
import io.github.rwpp.expansion.ExpansionBinding
import io.github.rwpp.net.games.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GamePortalControllerTest {
    private val original = OnlineGame("11111111-1111-1111-1111-111111111111", "Reviewed", available = true,
        servers = listOf(OnlineGameServer("main", "Main", "127.0.0.1:5123", available = true, online = true)),
        portalUrl = "https://game.example/portal", portalRevision = 3)
    private var game = original
    private var current = true
    private var consent = false
    private var art: Boolean? = null
    private var prepares = 0
    private var cancelNative = 0
    private val cancelledTokens = mutableListOf<Long>()
    private val events = mutableListOf<JsonObject>()

    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun teardown() { Dispatchers.resetMain() }

    private fun controller(scope: CoroutineScope, readPlayer: suspend () -> JsonObject = { buildJsonObject { put("account_id", "200") } },
                           prepare: suspend (OnlineGame, Boolean) -> Long = { _, useArt -> art = useArt; prepares++; 7 }) =
        GamePortalController(original, { current }, { game }, readPlayer,
            { buildJsonObject { put("code", "a".repeat(64)) } }, { consent }, prepare,
            { cancelledTokens += it }, { cancelNative++ }, scope).also { it.bindEvents { _, data -> events += data } }

    private fun join(id: String = "request-one", server: String = "main") = buildJsonObject { put("server_id", server); put("request_id", id) }

    @Test fun websiteSeesApprovedServerMetadataWithoutSocketAddress() = runTest {
        val subject = controller(this)
        val data = subject.request("getServers", buildJsonObject {})
        val server = data["servers"]!!.jsonArray.single().jsonObject
        assertEquals("main", server["server_id"]!!.jsonPrimitive.content)
        assertFalse(data.toString().contains("127.0.0.1"))
        assertFalse(server.containsKey("endpoint"))
        game = game.copy(available = false)
        assertFalse(subject.request("getServers", buildJsonObject {})["servers"]!!.jsonArray.single().jsonObject["available"]!!.jsonPrimitive.boolean)
        subject.request("joinServer", join())
        advanceUntilIdle()
        assertEquals("server_unavailable", subject.state.value.error)
        assertEquals(0, prepares)
    }

    @Test fun optionalAppearanceDeclineContinuesWithDefaultAndCancelMatchesRequest() = runTest {
        consent = true
        val subject = controller(this)
        subject.request("joinServer", join())
        runCurrent()
        assertNotNull(subject.state.value.consent).decision.complete(false)
        advanceUntilIdle()
        assertEquals(false, art)
        assertEquals("127.0.0.1:5123", subject.state.value.ready?.endpoint)
        assertFailsWith<GamePortalException> { subject.request("joinServer", join("another")) }
        assertFailsWith<GamePortalException> { subject.request("cancelJoin", buildJsonObject { put("request_id", "wrong") }) }
        subject.request("cancelJoin", buildJsonObject { put("request_id", "request-one") })
        advanceUntilIdle()
        assertNull(subject.state.value.attempt)
        assertEquals(listOf(7L), cancelledTokens)
        assertEquals(1, cancelNative)
        assertEquals("cancelled", events.last()["state"]!!.jsonPrimitive.content)
        assertEquals("cancelled", subject.request("joinServer", join())["state"]!!.jsonPrimitive.content)
        advanceUntilIdle()
        assertEquals(1, prepares)
        assertFailsWith<GamePortalException> { subject.request("joinServer", join(server = "another-server")) }
    }

    @Test fun requiredExpansionCannotBypassPermissionWithDefaultAppearance() = runTest {
        game = original.copy(expansionBinding = ExpansionBinding("1.0", "a".repeat(64), 4096,
            ExpansionArtBinding("22222222-2222-2222-2222-222222222222", "1.0", "b".repeat(64))))
        consent = true
        val subject = controller(this)
        subject.request("joinServer", join())
        runCurrent()
        assertTrue(assertNotNull(subject.state.value.consent).required)
        subject.state.value.consent!!.decision.complete(false)
        advanceUntilIdle()
        assertEquals("content_declined", subject.state.value.error)
        assertNull(subject.state.value.ready)
        assertEquals(0, prepares)
        assertEquals("cancelled", events.last()["state"]!!.jsonPrimitive.content)
    }

    @Test fun approvalChangesAndForeignServerIdsCannotGrantAdmission() = runTest {
        val subject = controller(this)
        game = original.copy(portalRevision = 4)
        assertEquals("portal_changed", assertFailsWith<GamePortalException> { subject.request("getPlayer", buildJsonObject {}) }.code)
        game = original
        subject.request("joinServer", join(server = "foreign"))
        advanceUntilIdle()
        assertEquals("server_unavailable", subject.state.value.error)
        assertEquals(0, prepares)
    }

    @Test fun logoutDuringIdentityRequestDropsTheLateResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val subject = controller(this, readPlayer = { gate.await(); buildJsonObject { put("account_id", "200") } })
        val result = async { runCatching { subject.request("getPlayer", buildJsonObject {}) } }
        runCurrent()
        current = false
        gate.complete(Unit)
        val error = assertIs<GamePortalException>(result.await().exceptionOrNull())
        assertEquals("session_expired", error.code)
        assertFalse(subject.isActive())
    }

    @Test fun reloadCancelsOldAttemptAndLateNativeSuccessCannotOpenRoom() = runTest {
        val subject = controller(this)
        subject.request("joinServer", join())
        advanceUntilIdle()
        val attempt = assertNotNull(subject.state.value.attempt)
        assertTrue(subject.isCurrent(attempt))
        subject.onDocumentChanged()
        advanceUntilIdle()
        assertFalse(subject.isCurrent(attempt))
        subject.finished(attempt, true)
        assertNull(subject.state.value.ready)
        assertEquals("joining", events.last()["state"]!!.jsonPrimitive.content) // Old-document cancellation is not sent to the new page.
        subject.close()
        assertFailsWith<GamePortalException> { subject.request("getServers", buildJsonObject {}) }
    }

    @Test fun contentFailureStaysOnOriginalPortalAndNeverSilentlyUsesDefault() = runTest {
        val subject = controller(this, prepare = { _, useArt -> assertTrue(useArt); throw java.io.IOException("private detail") })
        subject.request("joinServer", join())
        advanceUntilIdle()
        assertEquals("join_failed", subject.state.value.error)
        assertTrue(subject.isActive())
        assertNull(subject.state.value.ready)
        assertFalse(events.toString().contains("private detail"))
    }

    @Test fun businessRequestIdReplaysShareCurrentAndTerminalReceipt() = runTest {
        val subject = controller(this)
        subject.request("joinServer", join())
        advanceUntilIdle()
        assertEquals("joining", subject.request("joinServer", join())["state"]!!.jsonPrimitive.content)
        assertEquals(1, prepares)
        val attempt = assertNotNull(subject.state.value.attempt)
        subject.finished(attempt, true)
        assertEquals("joined", subject.request("joinServer", join())["state"]!!.jsonPrimitive.content)
        assertEquals(1, prepares)
        assertFailsWith<GamePortalException> { subject.request("joinServer", join(server = "foreign")) }
    }

    @Test fun cancellationIsFinalOnlyAfterNativeCleanupCompletes() = runTest {
        val subject = controller(this)
        subject.request("joinServer", join())
        advanceUntilIdle()
        val attempt = assertNotNull(subject.state.value.attempt)
        val released = CompletableDeferred<Unit>()
        val native = launch { try { awaitCancellation() } finally { withContext(NonCancellable) { released.await() } } }
        runCurrent()
        subject.attachNative(attempt, native)
        subject.cancelJoin()
        runCurrent()
        assertTrue(subject.state.value.cancelling)
        assertNotNull(subject.state.value.attempt)
        assertFailsWith<GamePortalException> { subject.request("joinServer", join("new-id")) }
        assertNotEquals("cancelled", events.last()["state"]!!.jsonPrimitive.content)
        released.complete(Unit)
        advanceUntilIdle()
        assertNull(subject.state.value.attempt)
        assertEquals("cancelled", events.last()["state"]!!.jsonPrimitive.content)
    }
}
