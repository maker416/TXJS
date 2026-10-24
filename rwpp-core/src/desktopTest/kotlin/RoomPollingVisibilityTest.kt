/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.app.LauncherVisibility
import io.github.rwpp.game.Game
import io.github.rwpp.game.GameRoom
import io.github.rwpp.ui.LauncherPage
import io.github.rwpp.ui.resetNavigation
import java.lang.reflect.Proxy
import javax.swing.SwingUtilities
import kotlin.test.*

class RoomPollingVisibilityTest {
    @AfterTest fun reset() {
        LauncherVisibility.setVisible(true)
        SwingUtilities.invokeAndWait { resetNavigation(LauncherPage.MainMenu) }
    }

    @Test fun onlyVisibleWaitingRoomSamplesAndHiddenStateNeverReadsEngine() {
        var started = false; var starting = false; var reads = 0
        val room = Proxy.newProxyInstance(GameRoom::class.java.classLoader, arrayOf(GameRoom::class.java)) { _, method, _ ->
            reads++
            when (method.name) {
                "isStartGame" -> started
                "isStartingGame" -> starting
                else -> error("Unexpected engine read: " + method.name)
            }
        } as GameRoom
        val game = Proxy.newProxyInstance(Game::class.java.classLoader, arrayOf(Game::class.java)) { _, method, _ ->
            if (method.name == "getGameRoom") room else error(method.name)
        } as Game
        SwingUtilities.invokeAndWait {
            resetNavigation(LauncherPage.Room)
            assertTrue(RoomSnapshotStore.shouldSample(game))
            started = true; assertFalse(RoomSnapshotStore.shouldSample(game)); started = false
            starting = true; assertFalse(RoomSnapshotStore.shouldSample(game)); starting = false
            LauncherVisibility.setVisible(false)
            val before = reads
            repeat(100) { RoomSnapshotStore.resample(game) }
            assertEquals(before, reads)
            LauncherVisibility.setVisible(true)
            assertTrue(RoomSnapshotStore.shouldSample(game))
            resetNavigation(LauncherPage.Multiplayer)
            assertFalse(RoomSnapshotStore.shouldSample(game))
        }
    }
}
