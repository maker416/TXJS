/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopMenuBackgroundEngineTest {
    @Test fun returningToLauncherPreservesTheMatchForContinue() {
        assertEquals(MenuBackgroundFrameDecision(), menuBackgroundFrameDecision(
            gameVisible = false, hasMap = true, menuMap = false, connected = false,
            renderRequested = true, canLoadMap = true,
        ))
    }

    @Test fun idleLauncherCanRecreateTheOriginalDemoAfterDisconnect() {
        assertEquals(MenuBackgroundFrameDecision(loadBackground = true), menuBackgroundFrameDecision(
            gameVisible = false, hasMap = false, menuMap = false, connected = false,
            renderRequested = true, canLoadMap = true,
        ))
    }

    @Test fun aPendingJoinOrReloadDoesNotCreateTeamsOrLoadAMap() {
        assertEquals(MenuBackgroundFrameDecision(), menuBackgroundFrameDecision(
            gameVisible = false, hasMap = false, menuMap = false, connected = false,
            renderRequested = true, canLoadMap = false,
        ))
    }

    @Test fun anImageOrHiddenLauncherStopsTheStandaloneDemoSimulation() {
        assertEquals(MenuBackgroundFrameDecision(), menuBackgroundFrameDecision(
            gameVisible = false, hasMap = true, menuMap = true, connected = false,
            renderRequested = false, canLoadMap = true,
        ))
    }

    @Test fun hidingTheBackgroundDoesNotStopAnExistingRoomNetworkPump() {
        assertEquals(MenuBackgroundFrameDecision(renderOriginal = true), menuBackgroundFrameDecision(
            gameVisible = false, hasMap = true, menuMap = true, connected = true,
            renderRequested = false, canLoadMap = false,
        ))
    }

    @Test fun aConnectedRoomWithoutAMapNeverGetsReplacedWithDemoTeams() {
        assertEquals(MenuBackgroundFrameDecision(renderOriginal = true), menuBackgroundFrameDecision(
            gameVisible = false, hasMap = false, menuMap = false, connected = true,
            renderRequested = true, canLoadMap = true,
        ))
    }

    @Test fun returningFromAMultiplayerMatchPreservesItsNetworkLoopAndMap() {
        assertEquals(MenuBackgroundFrameDecision(renderOriginal = true), menuBackgroundFrameDecision(
            gameVisible = false, hasMap = true, menuMap = false, connected = true,
            renderRequested = true, canLoadMap = true,
        ))
    }

    @Test fun playingAlwaysUsesTheOriginalRendererEvenIfMenuRequestArrivesLate() {
        assertEquals(MenuBackgroundFrameDecision(renderOriginal = true), menuBackgroundFrameDecision(
            gameVisible = true, hasMap = true, menuMap = false, connected = false,
            renderRequested = true, canLoadMap = true,
        ))
    }
}
