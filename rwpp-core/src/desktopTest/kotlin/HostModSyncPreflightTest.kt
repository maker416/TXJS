/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import java.io.IOException
import kotlinx.coroutines.*
import kotlin.test.*

class HostModSyncPreflightTest {
    @Test
    fun failedCheckWaitsForDecisionAndRetryMustSucceedBeforeHosting() = runBlocking {
        val decision = CompletableDeferred<HostModSyncFailureAction>()
        val failed = CompletableDeferred<Unit>()
        var attempts = 0
        var connected = false
        val entry = async(start = CoroutineStart.UNDISPATCHED) {
            val sharing = awaitHostModSyncService(
                prepare = { if (++attempts == 1) throw IOException("offline") },
                onFailure = { failed.complete(Unit); decision.await() },
            )
            connected = true
            sharing
        }
        failed.await()
        assertFalse(connected)
        assertFalse(entry.isCompleted)
        decision.complete(HostModSyncFailureAction.Retry)
        assertTrue(entry.await())
        assertTrue(connected)
        assertEquals(2, attempts)
    }

    @Test
    fun onlyExplicitContinueDisablesSharing() = runBlocking {
        val sharing = awaitHostModSyncService(
            prepare = { throw IOException("offline") },
            onFailure = { HostModSyncFailureAction.ContinueWithoutSharing },
        )
        assertFalse(sharing)
    }

    @Test
    fun closingTheFailurePromptCancelsEntryWithoutConnecting() = runBlocking {
        val decision = CompletableDeferred<HostModSyncFailureAction>()
        var connected = false
        val entry = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitHostModSyncService(
                prepare = { throw IOException("offline") },
                onFailure = { decision.await() },
            )
            connected = true
        }
        entry.cancelAndJoin()
        decision.complete(HostModSyncFailureAction.ContinueWithoutSharing)
        assertFalse(connected)
    }

    @Test
    fun cancelledCheckNeverOffersContinueOrConnects() = runBlocking {
        var prompted = false
        assertFailsWith<CancellationException> {
            awaitHostModSyncService(
                prepare = { throw CancellationException("cancelled") },
                onFailure = { prompted = true; HostModSyncFailureAction.ContinueWithoutSharing },
            )
        }
        assertFalse(prompted)
    }
}
