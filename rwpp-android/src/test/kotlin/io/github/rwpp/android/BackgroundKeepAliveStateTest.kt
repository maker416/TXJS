/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import io.github.rwpp.android.BackgroundKeepAliveState.ServicePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundKeepAliveStateTest {
    @Test
    fun lockScreenBeforeSocketConnectsRetainsTheServiceRequestedInForeground() {
        val state = BackgroundKeepAliveState().apply { foreground = true }
        state.beginConnection(1)
        assertTrue(state.needsService(networked = false, localSinglePlayer = false, nowMs = 0))
        val id = assertNotNull(state.requestStart(0))
        assertEquals(ServicePhase.STARTING, state.servicePhase)
        assertNull(state.requestStart(100))
        state.foreground = false
        assertTrue(state.onServiceReady(id))
        assertTrue(state.needsService(false, false, 500))
        assertTrue(state.needsService(true, false, 2000))
        state.endConnection(1)
        assertTrue(state.needsService(true, false, 2001))
        assertEquals(ServicePhase.RUNNING, state.servicePhase)
    }

    @Test
    fun preJoinCancellationDoesNotLeaveAReconnectGraceOrReleaseANewerOperation() {
        val state = BackgroundKeepAliveState()
        state.beginConnection(1)
        state.beginConnection(2)
        state.endConnection(1)
        state.endConnection(1)
        assertTrue(state.needsService(false, false, 100))
        state.endConnection(2)
        assertFalse(state.needsService(false, false, 101))
    }

    @Test
    fun temporaryDisconnectInBackgroundKeepsTheSameServiceUntilReconnect() {
        val state = BackgroundKeepAliveState().apply { foreground = true }
        assertTrue(state.needsService(true, false, 0))
        val id = assertNotNull(state.requestStart(0))
        state.onServiceReady(id)
        state.foreground = false
        assertTrue(state.needsService(false, false, 100))
        assertTrue(state.needsService(false, false, 9000))
        assertTrue(state.needsService(true, false, 9500))
        assertTrue(state.needsService(true, false, 50_000))
        assertEquals(id, state.generation)
        assertEquals(ServicePhase.RUNNING, state.servicePhase)
    }

    @Test
    fun relayRedirectBeforeTheFirstPollStillGetsReconnectGrace() {
        val state = BackgroundKeepAliveState()
        state.beginConnection(1)
        state.onConnected()
        state.endConnection(1)
        assertTrue(state.needsService(false, false, 100))
        assertTrue(state.needsService(true, false, 8000))
    }

    @Test
    fun disconnectedPollingDoesNotExtendGraceForever() {
        val state = BackgroundKeepAliveState()
        state.needsService(true, false, 0)
        assertTrue(state.needsService(false, false, 100))
        assertTrue(state.needsService(false, false, 10_099))
        assertFalse(state.needsService(false, false, 10_100))
        assertFalse(state.needsService(false, false, 20_000))
    }

    @Test
    fun explicitRoomExitClearsGraceButWaitsForOutstandingConnectionCleanup() {
        val state = BackgroundKeepAliveState()
        state.beginConnection(1)
        state.needsService(true, false, 0)
        state.onSessionEnded()
        assertTrue(state.needsService(false, false, 100))
        state.endConnection(1)
        assertFalse(state.needsService(false, false, 101))
    }

    @Test
    fun localSkirmishAndSandboxAreExcludedEvenWithTheEngineNetworkFlagSet() {
        val state = BackgroundKeepAliveState()
        assertFalse(state.needsService(true, true, 0))
        state.needsService(true, false, 100)
        assertFalse(state.needsService(true, true, 101))
        assertFalse(state.needsService(false, false, 102))
        // 从单人房主动加入多人时，新的连接操作仍应提前获得保护。
        state.beginConnection(1)
        assertTrue(state.needsService(true, true, 103))
    }

    @Test
    fun aServiceStoppingDuringCancellationCannotConfirmOrDestroyTheNextService() {
        val state = BackgroundKeepAliveState().apply { foreground = true }
        val oldId = assertNotNull(state.requestStart(0))
        assertEquals(oldId, state.requestStop())
        assertFalse(state.onServiceReady(oldId))
        // 取消后立即加入另一房：趁仍在前台发起新请求，不等旧 onDestroy 后才尝试后台启动。
        val newId = assertNotNull(state.requestStart(1))
        state.foreground = false
        state.onServiceDestroyed(oldId)
        assertEquals(ServicePhase.STARTING, state.servicePhase)
        assertTrue(state.onServiceReady(newId))
        state.onServiceDestroyed(oldId)
        assertFalse(state.onServiceReady(oldId))
        assertEquals(ServicePhase.RUNNING, state.servicePhase)
    }

    @Test
    fun failedPromotionWaitsForDestructionAndBacksOffBeforeRetry() {
        val state = BackgroundKeepAliveState().apply { foreground = true }
        val id = assertNotNull(state.requestStart(0))
        state.onStartFailed(id, 100, serviceCreated = true)
        assertNull(state.requestStart(1000))
        state.onServiceDestroyed(id)
        assertNull(state.requestStart(5099))
        assertNotNull(state.requestStart(5100))
    }

    @Test
    fun rejectedStartDoesNotPretendTheServiceIsRunningOrRetryFromBackground() {
        val state = BackgroundKeepAliveState().apply { foreground = true }
        val id = assertNotNull(state.requestStart(0))
        state.onStartFailed(id, 0, serviceCreated = false)
        assertEquals(ServicePhase.STOPPED, state.servicePhase)
        state.foreground = false
        assertNull(state.requestStart(6000))
        state.foreground = true
        assertNotNull(state.requestStart(6001))
    }

    @Test
    fun stopFailureAllowsAnotherAttemptInsteadOfGettingStuckStopping() {
        val state = BackgroundKeepAliveState().apply { foreground = true }
        val id = assertNotNull(state.requestStart(0))
        state.onServiceReady(id)
        state.requestStop()
        state.onStopFailed(id)
        assertEquals(id, state.requestStop())
    }
}
