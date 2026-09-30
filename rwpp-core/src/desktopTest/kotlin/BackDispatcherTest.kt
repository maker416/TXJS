/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackDispatcherTest {
    @Test
    fun onlyTheTopEnabledHandlerReceivesOneBack() {
        val dispatcher = BackDispatcher { it() }
        val calls = mutableListOf<String>()
        var overlayOpen = true
        dispatcher.register({ true }) { calls.add("room") }
        dispatcher.register({ overlayOpen }) { calls.add("account") }
        assertTrue(dispatcher.dispatch())
        assertEquals(listOf("account"), calls)
        overlayOpen = false
        dispatcher.dispatch()
        assertEquals(listOf("account", "room"), calls)
    }

    @Test
    fun queuedBackDoesNotRunAfterPageExitOrDispose() {
        val queued = mutableListOf<() -> Unit>()
        val dispatcher = BackDispatcher { queued.add(it) }
        var enabled = true
        var calls = 0
        val handler = dispatcher.register({ enabled }) { calls++ }
        dispatcher.dispatch()
        enabled = false
        queued.removeFirst()()
        assertEquals(0, calls)
        enabled = true
        dispatcher.dispatch()
        dispatcher.unregister(handler)
        queued.removeFirst()()
        assertEquals(0, calls)
        assertFalse(dispatcher.dispatch())
    }
}
