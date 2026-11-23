/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.core.GameSessionController
import io.github.rwpp.game.Game
import io.github.rwpp.game.GameRoom
import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativeRoomCommitTest {
    @BeforeTest fun setup() {
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
    }
    @AfterTest fun cleanup() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            GameSessionController.onExternalSessionEnd()
            GameSessionController.multiplayerReturnPage = LauncherPage.Multiplayer
            resetNavigation(LauncherPage.MainMenu)
        }
        Dispatchers.resetMain()
    }

    @Test fun navigationCancellingTheLoadingOwnerStillCommitsBothRoomHooksExactlyOnce(): Unit = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val finished = CompletableDeferred<Unit>()
        val portal = LauncherPage.GamePortal("test-game")
        lateinit var loadingOwner: Job
        withContext(Dispatchers.Main.immediate) {
            resetNavigation(LauncherPage.MainMenu)
            navigateTo(LauncherPage.GameplayHall)
            navigateTo(portal)
        }
        loadingOwner = ownerScope.launch(start = CoroutineStart.LAZY) {
            try {
                commitNativeRoomEntry(
                    isCurrent = { launcherPage == portal },
                    prepare = { calls += "identity" },
                    navigate = {
                        GameSessionController.openMultiplayerRoom()
                        // Leaving the portal disposes the composition that owns its join job.
                        loadingOwner.cancel()
                        calls += "navigation"
                    },
                    committed = {
                        assertFalse(loadingOwner.isActive)
                        assertEquals(LauncherPage.Room, launcherPage)
                        calls += "sync"
                        calls += "join-event"
                    },
                )
                calls += "after-main-return"
            } finally { finished.complete(Unit) }
        }
        loadingOwner.start()
        withTimeout(5_000) { finished.await(); loadingOwner.join() }
        assertTrue(loadingOwner.isCancelled)
        assertEquals(listOf("identity", "navigation", "sync", "join-event"), calls.toList())
        withContext(Dispatchers.Main.immediate) {
            assertEquals(GameSessionController.SessionPhase.IN_ROOM, GameSessionController.sessionPhase)
            closePage(LauncherPage.Room)
            assertEquals(portal, launcherPage)
            navigateBack()
            assertEquals(LauncherPage.GameplayHall, launcherPage)
        }
        ownerScope.cancel()
    }

    @Test fun externalDisconnectReturnsToTheOriginatingPortalBeforeRoomCleanupFinishes(): Unit = runBlocking {
        val portal = LauncherPage.GamePortal("second-game")
        val disposed = CompletableDeferred<Unit>()
        val disconnectEntered = CompletableDeferred<Unit>()
        val allowDisconnect = CompletableDeferred<Unit>()
        val disconnect: suspend () -> Unit = {
            disconnectEntered.complete(Unit)
            allowDisconnect.await()
        }
        val room = proxy<GameRoom> { method, args ->
            when (method) {
                "disconnectAndWait" -> {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<Unit>
                    disconnect.startCoroutineUninterceptedOrReturn(continuation)
                }
                else -> error("Unexpected room call: $method")
            }
        }
        val game = proxy<Game> { method, _ ->
            when (method) {
                "getGameRoom" -> room
                "cancelJoinServer", "onBanUnits" -> null
                else -> error("Unexpected game call: $method")
            }
        }
        withContext(Dispatchers.Main.immediate) {
            resetNavigation(LauncherPage.GameplayHall)
            navigateTo(portal)
            GameSessionController.multiplayerReturnPage = portal
            GameSessionController.openMultiplayerRoom()
            GameSessionController.attachRoomView(disposed)
            try {
                GameSessionController.returnAfterExternalDisconnect(game)
                assertEquals(portal, launcherPage)
                assertEquals(GameSessionController.SessionPhase.CLOSING, GameSessionController.sessionPhase)
                assertFalse(disconnectEntered.isCompleted)

                disposed.complete(Unit)
                withTimeout(5_000) { disconnectEntered.await() }
                assertEquals(GameSessionController.SessionPhase.CLOSING, GameSessionController.sessionPhase)
            } finally {
                GameSessionController.detachRoomView(disposed)
                disposed.complete(Unit)
                allowDisconnect.complete(Unit)
                withTimeout(5_000) { GameSessionController.awaitSessionClose() }
            }
            assertEquals(GameSessionController.SessionPhase.IDLE, GameSessionController.sessionPhase)
            assertEquals(portal, launcherPage)
            resetNavigation(LauncherPage.GameplayHall)
            GameSessionController.returnAfterExternalDisconnect(game)
            assertEquals(LauncherPage.GameplayHall, launcherPage)
        }
    }

    @Test fun staleAdmissionCannotNavigateOrPublishRoomHooks(): Unit = runBlocking {
        withContext(Dispatchers.Main.immediate) { resetNavigation(LauncherPage.GameplayHall) }
        assertFalse(commitNativeRoomEntry(
            isCurrent = { false }, prepare = { fail("Stale identity") },
            navigate = { fail("Stale navigation") }, committed = { fail("Stale room hooks") },
        ))
        withContext(Dispatchers.Main.immediate) { assertEquals(LauncherPage.GameplayHall, launcherPage) }
    }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args)
        } as T
}
