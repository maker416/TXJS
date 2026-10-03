/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import kotlin.test.*

class ForumBrowserHandoffTest {
    private val bootstrap = "https://forum.example.com/sso/client"
    private class Controller : EmbeddedBrowserController {
        val scripts = mutableListOf<Pair<String, String>>()
        var loaded = ""
        override fun goBack() = Unit
        override fun goForward() = Unit
        override fun reload() = Unit
        override fun loadUrl(url: String) { loaded = url }
        override fun submitForumHandoff(trustedUrl: String, handoff: String?) { scripts += forumHandoffScript(trustedUrl, handoff) to trustedUrl }
    }

    @Test fun handoffWaitsForExactTrustedPageAndIsSentOnce() {
        val state = EmbeddedBrowserState(bootstrap, "https://forum.example.com")
        val controller = Controller(); state.controller = controller
        state.submitClientLogin(bootstrap, "a".repeat(64))
        state.pageLoaded("https://evil.example.com/sso/client")
        state.pageLoaded("https://forum.example.com/sso/client?redirect=other")
        assertTrue(controller.scripts.isEmpty())
        state.pageLoaded(bootstrap)
        assertEquals(1, controller.scripts.size)
        assertEquals(bootstrap, controller.scripts.single().second)
        assertTrue(controller.scripts.single().first.contains("location.href==="))
        state.pageLoaded(bootstrap)
        assertEquals(1, controller.scripts.size)
        state.pageLoaded("https://forum.example.com/")
        assertFalse(state.clientAuthenticating)
    }

    @Test fun alreadyLoadedBootstrapAcceptsNativeResultAndGuestClearsIdentityThroughServer() {
        val state = EmbeddedBrowserState(bootstrap, "https://forum.example.com")
        val controller = Controller(); state.controller = controller
        state.pageLoaded(bootstrap)
        state.submitClientLogin(bootstrap, null)
        assertEquals(1, controller.scripts.size)
        assertTrue(controller.scripts.single().first.contains("rwForumClient(null)"))
    }

    @Test fun invalidHandoffAndFailedPageNeverExecuteCredentials() {
        val state = EmbeddedBrowserState(bootstrap)
        val controller = Controller(); state.controller = controller
        assertFailsWith<IllegalArgumentException> { state.submitClientLogin(bootstrap, "');evil();//") }
        state.submitClientLogin(bootstrap, "a".repeat(64))
        state.error = "HTTP 404"
        state.pageLoaded(bootstrap)
        assertTrue(controller.scripts.isEmpty())
    }

    @Test fun pageInitializationCannotEraseNativeLoginFailure() {
        val state = EmbeddedBrowserState(bootstrap)
        val controller = Controller(); state.controller = controller
        state.failClientLogin("HTTPS required")
        state.pageStarted()
        state.error = null
        state.pageLoaded(bootstrap)
        assertEquals("HTTPS required", state.clientLoginError)
        var retries = 0
        state.retryClientLogin = { retries++ }
        state.reload()
        assertEquals(1, retries)
        state.beginClientLogin()
        assertNull(state.clientLoginError)
    }

    @Test fun missingControllerDoesNotConsumeHandoff() {
        val state = EmbeddedBrowserState(bootstrap)
        state.submitClientLogin(bootstrap, "a".repeat(64))
        state.pageLoaded(bootstrap)
        val controller = Controller(); state.controller = controller
        assertEquals(1, controller.scripts.size)
    }

    @Test fun retryOrGuestReloadsAnAlreadyUsedBootstrap() {
        val state = EmbeddedBrowserState(bootstrap)
        val controller = Controller(); state.controller = controller
        state.pageLoaded(bootstrap)
        state.submitClientLogin(bootstrap, "a".repeat(64))
        state.openAsGuest()
        assertEquals(bootstrap, controller.loaded)
        assertEquals(1, controller.scripts.size)
        state.pageStarted()
        state.pageLoaded(bootstrap)
        assertEquals(2, controller.scripts.size)
        assertTrue(controller.scripts.last().first.contains("rwForumClient(null)"))
    }
}
