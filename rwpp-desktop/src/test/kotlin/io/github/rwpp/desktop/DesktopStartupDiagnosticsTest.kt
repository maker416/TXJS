/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class DesktopStartupDiagnosticsTest {
    @Test fun blockedEdtIsSampledAndRecoveryRemainsResponsive() {
        val observations = CopyOnWriteArrayList<DesktopStartupDiagnostics.Observation>()
        val sampled = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val edtBlocked = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)
        val gameBlocked = CountDownLatch(1)
        val releaseGame = CountDownLatch(1)
        val game = Thread({ gameBlocked.countDown(); releaseGame.await(10, TimeUnit.SECONDS) }, "rwjs-game").apply {
            isDaemon = true
            start()
        }
        assertTrue(gameBlocked.await(2, TimeUnit.SECONDS))
        SwingUtilities.invokeLater { edtBlocked.countDown(); releaseEdt.await(10, TimeUnit.SECONDS) }
        assertTrue(edtBlocked.await(2, TimeUnit.SECONDS))
        val monitor = DesktopStartupDiagnostics.start(20, 100, 1_000) {
            observations += it
            if (it is DesktopStartupDiagnostics.Observation.EdtStall) sampled.countDown()
            if (it is DesktopStartupDiagnostics.Observation.EdtRecovered) recovered.countDown()
        }
        try {
            assertTrue(sampled.await(3, TimeUnit.SECONDS), "Blocked EDT was not sampled")
            val stall = observations.filterIsInstance<DesktopStartupDiagnostics.Observation.EdtStall>().first()
            assertTrue(stall.delayMillis >= 100)
            val edt = stall.threads.firstOrNull { it.name.startsWith("AWT-EventQueue-") }
            val engine = stall.threads.firstOrNull { it.name == "rwjs-game" }
            assertNotNull(edt)
            assertNotNull(engine)
            assertTrue(edt.stack.any { it.className == CountDownLatch::class.java.name }, "EDT sample lost its blocking call")
            assertTrue(engine.stack.any { it.className == CountDownLatch::class.java.name }, "Engine sample lost its blocking call")
            assertTrue(stall.threads.all { it.stack.size <= 24 })
            Thread.sleep(150)
            assertEquals(1, observations.filterIsInstance<DesktopStartupDiagnostics.Observation.EdtStall>().size,
                "The same short stall must not repeatedly dump thread stacks")
            releaseEdt.countDown()
            assertTrue(recovered.await(3, TimeUnit.SECONDS), "EDT recovery was not recorded")
            val responsive = CountDownLatch(1)
            SwingUtilities.invokeLater { responsive.countDown() }
            assertTrue(responsive.await(2, TimeUnit.SECONDS), "Diagnostics prevented EDT from recovering")
        } finally {
            releaseEdt.countDown()
            releaseGame.countDown()
            monitor.close()
            assertTrue(monitor.awaitTermination(2_000), "Startup diagnostics executor did not stop")
            game.join(2_000)
        }
    }

    @Test fun closingWhileEdtIsBlockedStopsWorkerAndQueuedProbe() {
        val observations = CopyOnWriteArrayList<DesktopStartupDiagnostics.Observation>()
        val sampled = CountDownLatch(1)
        val edtBlocked = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)
        SwingUtilities.invokeLater { edtBlocked.countDown(); releaseEdt.await(10, TimeUnit.SECONDS) }
        assertTrue(edtBlocked.await(2, TimeUnit.SECONDS))
        val monitor = DesktopStartupDiagnostics.start(20, 80, 100) {
            observations += it
            sampled.countDown()
        }
        try {
            assertTrue(sampled.await(3, TimeUnit.SECONDS))
            monitor.close()
            monitor.close()
            assertTrue(monitor.awaitTermination(2_000), "A blocked EDT must not keep the diagnostics worker alive")
            val countAtClose = observations.size
            monitor.onFrameRendered()
            releaseEdt.countDown()
            SwingUtilities.invokeAndWait { }
            Thread.sleep(150)
            assertEquals(countAtClose, observations.size, "A queued heartbeat emitted diagnostics after close")
        } finally {
            releaseEdt.countDown()
            monitor.close()
        }
    }

    @Test fun firstRenderedFrameIsRetainedWhenStartupCompletesImmediately() {
        val observations = CopyOnWriteArrayList<DesktopStartupDiagnostics.Observation>()
        val monitor = DesktopStartupDiagnostics.start(1_000, 500, 1_000) { observations += it }
        try {
            SwingUtilities.invokeAndWait { monitor.onFrameRendered(); monitor.onFrameRendered() }
            monitor.close()
            assertTrue(monitor.awaitTermination(2_000))
            val first = observations.filterIsInstance<DesktopStartupDiagnostics.Observation.FirstFrame>()
            assertEquals(1, first.size)
            assertTrue(first.single().threadName.startsWith("AWT-EventQueue-"))
            assertTrue(first.single().elapsedMillis >= 0)
            assertFalse(observations.any { it is DesktopStartupDiagnostics.Observation.SlowFrame })
        } finally {
            monitor.close()
        }
    }

    @Test fun staticProgressUpdatesDoNotReportAnimationFrameGaps() {
        val observations = CopyOnWriteArrayList<DesktopStartupDiagnostics.Observation>()
        val monitor = DesktopStartupDiagnostics.start(20, 20, 1_000) { observations += it }
        try {
            SwingUtilities.invokeAndWait { monitor.onFrameRendered(continuousFramesExpected = false) }
            Thread.sleep(60)
            SwingUtilities.invokeAndWait {
                monitor.onFrameRendered(continuousFramesExpected = false)
                monitor.onFrameRendered(continuousFramesExpected = false)
            }
            monitor.close()
            monitor.close()
            assertTrue(monitor.awaitTermination(2_000))
            assertFalse(observations.any { it is DesktopStartupDiagnostics.Observation.SlowFrame },
                "Waiting between static progress updates must not be counted as a dropped animation frame")
            assertEquals(1, observations.filterIsInstance<DesktopStartupDiagnostics.Observation.FirstFrame>().size)
            val summary = observations.filterIsInstance<DesktopStartupDiagnostics.Observation.Summary>().single()
            assertEquals(3L, summary.framesRendered)
            assertEquals(0L, summary.maxGapMillis)
        } finally {
            monitor.close()
        }
    }
}
