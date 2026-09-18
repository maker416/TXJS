/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.ui.FakeAccountSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeAccountSessionTest {

    @AfterTest
    fun reset() {
        FakeAccountSession.applyLoggedOut()
        // lastIdentifier is kept after sign-out; tests should not leak loggedIn
    }

    @Test
    fun signInUsesAccountAsDisplayName() {
        FakeAccountSession.applyLoggedOut()
        FakeAccountSession.signIn("修玉")
        assertTrue(FakeAccountSession.loggedIn)
        assertEquals("修玉", FakeAccountSession.identifier)
        assertEquals("修玉", FakeAccountSession.displayName)
        assertEquals("修玉", FakeAccountSession.lastIdentifier)
    }

    @Test
    fun signOutClearsSessionButKeepsLastIdentifier() {
        FakeAccountSession.signIn("修玉")
        FakeAccountSession.signOut()
        assertFalse(FakeAccountSession.loggedIn)
        assertEquals("", FakeAccountSession.identifier)
        assertEquals("", FakeAccountSession.displayName)
        assertEquals("修玉", FakeAccountSession.lastIdentifier)
    }
}
