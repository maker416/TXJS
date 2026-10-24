/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.android

import io.github.rwpp.android.impl.inject.BaseEngineInject
import io.github.rwpp.game.GameStartSafety
import io.github.rwpp.inject.InterruptResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class ResourceFailureFrameTest {
    @Test fun queuedLoadFailureSkipsTheSameFrameAndSubsequentTicks() {
        @Suppress("UNCHECKED_CAST")
        val state = GameStartSafety::class.java.getDeclaredField("failure").run {
            isAccessible = true
            get(null) as MutableStateFlow<GameStartSafety.Failure>
        }
        val old = state.value
        try {
            state.value = GameStartSafety.Failure.NONE
            assertTrue(mainThreadChannel.trySend { GameStartSafety.markMemoryExhausted() }.isSuccess)
            // No engine or Koin exists in this test: touching either original path would fail.
            assertSame(InterruptResult.Unit, BaseEngineInject.onMainLoop(1f, 1))
            assertTrue(GameStartSafety.requiresRestart)
            assertSame(InterruptResult.Unit, BaseEngineInject.onUpdate(1f))
            assertSame(InterruptResult.Unit, BaseEngineInject.onMainLoop(1f, 1))
        } finally { state.value = old }
    }
}
