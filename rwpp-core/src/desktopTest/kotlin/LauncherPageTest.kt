/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LauncherPageTest {
    @AfterTest
    fun reset() { SwingUtilities.invokeAndWait { resetNavigation(LauncherPage.MainMenu) } }

    @Test
    fun childReturnsToParentAndDuplicatePushIsIgnored() {
        SwingUtilities.invokeAndWait {
            resetNavigation(LauncherPage.MainMenu)
            navigateTo(LauncherPage.SinglePlayer)
            navigateTo(LauncherPage.Room)
            navigateTo(LauncherPage.Room)
            closePage(LauncherPage.SinglePlayer)
            assertEquals(LauncherPage.Room, launcherPage)
            navigateBack()
            assertEquals(LauncherPage.SinglePlayer, launcherPage)
            navigateBack()
            assertEquals(LauncherPage.MainMenu, launcherPage)
        }
    }

    @Test
    fun forcedNavigationDropsTheOldStack() {
        SwingUtilities.invokeAndWait {
            navigateTo(LauncherPage.Settings)
            navigateTo(LauncherPage.Themes)
            resetNavigation(LauncherPage.Multiplayer)
            navigateBack()
            assertEquals(LauncherPage.MainMenu, launcherPage)
        }
    }

    @Test
    fun backgroundMutationFailsBeforeChangingNavigation() {
        assertFailsWith<IllegalStateException> { navigateTo(LauncherPage.Room) }
        assertFailsWith<IllegalStateException> { navigateBack() }
        assertFailsWith<IllegalStateException> { resetNavigation(LauncherPage.Room) }
        assertFailsWith<IllegalStateException> { closePage(LauncherPage.Room) }
    }
}
