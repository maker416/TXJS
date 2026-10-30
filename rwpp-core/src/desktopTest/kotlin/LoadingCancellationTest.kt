/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import androidx.compose.material3.Text
import io.github.rwpp.core.LoadingContext
import io.github.rwpp.net.ConnectionFailure
import io.github.rwpp.net.ConnectionFailureReason
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LoadingCancellationTest {
    @BeforeTest fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = true) } }) }.koin
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = !SwingUtilities.isEventDispatchThread()
            override fun dispatch(context: CoroutineContext, block: Runnable) = SwingUtilities.invokeLater(block)
        })
        loadingMessage = ""
    }
    @AfterTest fun teardown() {
        Dispatchers.resetMain()
        stopKoin()
        loadingMessage = ""
    }
    @Test fun firstAttemptCloseCancelsBeforeExitAnimationCompletes() = verifyClose(false)
    @Test fun retryCloseCancelsBeforeExitAnimationCompletes() = verifyClose(true)

    @Test
    fun cancelledAttemptCannotPublishIntoTheNextAttempt() = runDesktopComposeUiTest {
        var visible by mutableStateOf(true)
        val firstContext = AtomicReference<LoadingContext?>()
        val secondContext = AtomicReference<LoadingContext?>()
        val failSecond = CompletableDeferred<Unit>()
        var attempts = 0
        var shown: ConnectionFailure? = null
        val currentFailure = ConnectionFailure(ConnectionFailureReason.TIMEOUT)
        setContent {
            KoinContext(appKoin) {
                MaterialTheme {
                    LoadingView(visible, onLoaded = { visible = false }, cancellable = true,
                        failureContent = { failure, _ -> shown = failure; Text("current error") }) {
                        if (++attempts == 1) {
                            firstContext.set(this)
                            awaitCancellation()
                        } else {
                            secondContext.set(this)
                            message("Current attempt")
                            failSecond.await()
                            failure(currentFailure)
                            false
                        }
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 3_000) { firstContext.get() != null }
        onNode(hasClickAction()).performClick()
        waitUntil(timeoutMillis = 3_000) { !visible }
        waitForIdle()
        runOnIdle { visible = true }
        waitUntil(timeoutMillis = 3_000) { secondContext.get() != null }
        // 模拟引擎持有旧的 LoadingContext，在新请求已开始时从 IO 迟到回调。
        runBlocking(Dispatchers.IO) {
            firstContext.get()!!.message("Stale attempt")
            firstContext.get()!!.failure(ConnectionFailure(ConnectionFailureReason.REFUSED))
        }
        waitForIdle()
        assertEquals("Current attempt", loadingMessage)
        onNodeWithText("current error").assertDoesNotExist()
        failSecond.complete(Unit)
        waitUntil(timeoutMillis = 3_000) { shown != null }
        assertEquals(currentFailure, shown)
    }

    private fun verifyClose(failOnce: Boolean) = runDesktopComposeUiTest {
        var visible by mutableStateOf(true)
        var secondAttempt by mutableStateOf(!failOnce)
        val entered = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val connected = AtomicBoolean()
        val release = CompletableDeferred<Unit>()
        setContent {
            KoinContext(appKoin) {
                MaterialTheme {
                    LoadingView(visible, onLoaded = { visible = false }, cancellable = true,
                        onLoadingFinished = { visible = false }) {
                        if (!secondAttempt) {
                            message("Connection refused")
                            false
                        } else {
                            entered.set(true)
                            try {
                                release.await()
                                connected.set(true)
                                true
                            } catch (e: CancellationException) {
                                cancelled.set(true)
                                throw e
                            }
                        }
                    }
                }
            }
        }
        if (failOnce) {
            waitUntil(timeoutMillis = 3_000) { loadingMessage == "Connection refused" }
            waitForIdle()
            onNode(hasClickAction()).performClick()
            waitForIdle()
            waitUntil(timeoutMillis = 3_000) { !visible }
            runOnIdle { secondAttempt = true; visible = true }
        }
        waitUntil(timeoutMillis = 3_000) { entered.get() }
        waitForIdle()
        mainClock.autoAdvance = false
        onNode(hasClickAction()).performClick()
        release.complete(Unit)
        waitUntil(timeoutMillis = 3_000) { cancelled.get() || connected.get() }
        assertFalse(connected.get(), "User closed the loading dialog but the connection still completed during the exit animation")
        assertTrue(cancelled.get())
        mainClock.autoAdvance = true
    }
}

