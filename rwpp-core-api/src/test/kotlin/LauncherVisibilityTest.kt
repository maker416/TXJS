/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.app.pollWhileVisible
import io.github.rwpp.app.whileVisible
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherVisibilityTest {
    @Test fun hiddenPollingHasNoWakeupsAndResumeRunsImmediately() = runTest {
        val visible = MutableStateFlow(true); var calls = 0
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            visible.pollWhileVisible(1000) { calls++ }
        }
        assertEquals(1, calls)
        advanceTimeBy(1000); runCurrent(); assertEquals(2, calls)
        visible.value = false; runCurrent()
        advanceTimeBy(60_000); runCurrent(); assertEquals(2, calls)
        visible.value = true; runCurrent(); assertEquals(3, calls)
        visible.value = true; runCurrent(); assertEquals(3, calls)
        job.cancel()
    }

    @Test fun hideCancelsInflightRequestAndPreventsLateStateWrites() = runTest {
        val visible = MutableStateFlow(true)
        var commits = 0; var cancellations = 0; var attempts = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            visible.whileVisible {
                attempts++
                try { delay(5000); commits++ }
                finally { cancellations++ }
            }
        }
        advanceTimeBy(100); visible.value = false; runCurrent()
        assertEquals(1, cancellations)
        advanceTimeBy(10_000); assertEquals(0, commits)
        visible.value = true; runCurrent()
        advanceTimeBy(5000); runCurrent()
        assertEquals(2, attempts); assertEquals(1, commits)
    }
}
