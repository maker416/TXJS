/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DesktopEngineStartupTest {
    @Test fun linkageFailureReleasesStartupWaiter() = runBlocking {
        val startup = DesktopEngineStartup()
        val failure = NoClassDefFoundError("android/os/Build\$VERSION")
        val threadFailure = AtomicReference<Throwable>()
        val thread = Thread { startup.runEngine { throw failure } }
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> threadFailure.set(error) }
        thread.start()
        try {
            val caught = assertFailsWith<NoClassDefFoundError> {
                withTimeout(2_000) { startup.awaitInitialization() }
            }
            // 协程可能为恢复异步堆栈复制异常，检查类型及消息，并保留线程端原始异常。
            assertEquals(failure.message, caught.message)
        } finally {
            thread.join(2_000)
        }
        assertSame(failure, threadFailure.get())
    }

    @Test fun earlyThreadExitReleasesStartupWaiter(): Unit = runBlocking {
        val startup = DesktopEngineStartup()
        startup.runEngine { }
        assertFailsWith<IllegalStateException> {
            withTimeout(2_000) { startup.awaitInitialization() }
        }
    }

    @Test fun successfulInitializationReleasesStartupWaiter() = runBlocking {
        val startup = DesktopEngineStartup()
        startup.runEngine { startup.completeInitialization() }
        withTimeout(2_000) { startup.awaitInitialization() }
    }
}
