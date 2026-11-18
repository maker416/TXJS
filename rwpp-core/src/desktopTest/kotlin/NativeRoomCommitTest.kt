/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
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
        withContext(Dispatchers.Main.immediate) { resetNavigation(LauncherPage.MainMenu) }
        Dispatchers.resetMain()
    }

    @Test fun navigationCancellingTheLoadingOwnerStillCommitsBothRoomHooksExactlyOnce(): Unit = runBlocking {
        val calls = CopyOnWriteArrayList<String>()
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val finished = CompletableDeferred<Unit>()
        lateinit var loadingOwner: Job
        withContext(Dispatchers.Main.immediate) {
            resetNavigation(LauncherPage.GameplayHall)
            navigateTo(LauncherPage.GamePortal("test-game"))
        }
        loadingOwner = ownerScope.launch(start = CoroutineStart.LAZY) {
            try {
                commitNativeRoomEntry(
                    isCurrent = { launcherPage is LauncherPage.GamePortal },
                    prepare = { calls += "identity" },
                    navigate = {
                        resetNavigation(LauncherPage.GameplayHall)
                        navigateTo(LauncherPage.Room)
                        // LoadingView's composition owns this job and cancels it when the portal is removed.
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
        withContext(Dispatchers.Main.immediate) { navigateBack(); assertEquals(LauncherPage.GameplayHall, launcherPage) }
        ownerScope.cancel()
    }

    @Test fun staleAdmissionCannotNavigateOrPublishRoomHooks(): Unit = runBlocking {
        withContext(Dispatchers.Main.immediate) { resetNavigation(LauncherPage.GameplayHall) }
        assertFalse(commitNativeRoomEntry(
            isCurrent = { false }, prepare = { fail("Stale identity") },
            navigate = { fail("Stale navigation") }, committed = { fail("Stale room hooks") },
        ))
        withContext(Dispatchers.Main.immediate) { assertEquals(LauncherPage.GameplayHall, launcherPage) }
    }
}
