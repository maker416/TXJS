/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.platform.GamePortalBridge
import io.github.rwpp.platform.GamePortalBrowserDispatcher
import io.github.rwpp.platform.gamePortalOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GamePortalBrowserBridgeTest {
    private class Binding : GamePortalBridge {
        override val origin = "https://game.example"
        override val generation = "page-session-1"
        var active = true
        var calls = 0
        var changes = 0
        var block: CompletableDeferred<Unit>? = null
        override fun isActive() = active
        override fun onDocumentChanged() { changes++ }
        override suspend fun request(method: String, params: JsonObject): JsonObject {
            calls++; block?.await()
            return buildJsonObject { put("account_id", "101"); put("nickname", "玩家") }
        }
    }
    private val request = """{"v":1,"id":"r-1","method":"getPlayer","params":{}}"""

    @Test fun exactOriginAndMainFrameAreRequired() = runTest {
        assertEquals("https://game.example", gamePortalOrigin("https://GAME.EXAMPLE:443/portal?x=1#game"))
        assertEquals("http://[::1]:18082", gamePortalOrigin("http://[::1]:18082/game"))
        assertEquals("http://127.1.2.3:18082", gamePortalOrigin("http://127.1.2.3:18082/game"))
        assertNull(gamePortalOrigin("http://127.attacker.example/"))
        assertNull(gamePortalOrigin("http://public.example/"))
        assertNull(gamePortalOrigin("https://user:password@game.example/"))
        val binding = Binding(); val dispatcher = GamePortalBrowserDispatcher(binding)
        val epoch = dispatcher.beginDocument("https://game.example/portal?x=1#view")
        assertNull(dispatcher.dispatch(request, "https://evil.example/", true, epoch))
        assertNull(dispatcher.dispatch(request, "https://game.example/", false, epoch))
        assertNull(dispatcher.dispatch(request, "https://game.example:444/", true, epoch))
        assertEquals(0, binding.calls)
        assertTrue(dispatcher.dispatch(request, "https://game.example/portal", true, epoch)!!.contains("\"account_id\":\"101\""))
    }
    @Test fun navigationAndLogoutDiscardLateIdentityCodes() = runTest {
        val binding = Binding(); val dispatcher = GamePortalBrowserDispatcher(binding)
        val epoch = dispatcher.beginDocument("https://game.example/one")
        val gate = CompletableDeferred<Unit>(); binding.block = gate
        val pending = async { dispatcher.dispatch(request, "https://game.example/one", true, epoch) }
        testScheduler.runCurrent(); assertEquals(1, binding.calls)
        dispatcher.beginDocument("https://game.example/two"); gate.complete(Unit)
        assertNull(pending.await()); assertEquals(2, binding.changes)
        binding.active = false
        assertFalse(dispatcher.trusted("https://game.example/two", true))
        assertNull(dispatcher.dispatch(request, "https://game.example/two", true, dispatcher.epoch()))
    }
    @Test fun unknownMethodsForgedAuthorityAndMalformedPayloadsCannotReachBusiness() = runTest {
        val binding = Binding(); val dispatcher = GamePortalBrowserDispatcher(binding)
        val epoch = dispatcher.beginDocument("https://game.example/")
        for (payload in listOf("""{"v":1,"id":{},"method":"getPlayer","params":{}}""", """{"v":1,"id":"x","method":"getToken","params":{}}""", """{"v":1,"id":"y","method":"joinServer","params":{"endpoint":"evil:5123"}}""")) {
            val result = dispatcher.dispatch(payload, "https://game.example/", true, epoch)
            assertTrue(result == null || result.contains("\"ok\":false"))
        }
        assertEquals(0, binding.calls)
        dispatcher.revoke(); assertNull(dispatcher.dispatch(request, "https://game.example/", true, epoch))
    }
}
