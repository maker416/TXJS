/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** 只在启动期间观察界面线程；成功、失败或窗口销毁后均须 [close]，不保留局内采样任务。 */
internal class DesktopStartupDiagnostics private constructor(
    sampleIntervalMillis: Long,
    slowThresholdMillis: Long,
    private val reportIntervalMillis: Long,
    private val observer: (Observation) -> Unit,
) : AutoCloseable {
    private val startedNanos = System.nanoTime()
    private val slowThresholdNanos = TimeUnit.MILLISECONDS.toNanos(slowThresholdMillis)
    private val closed = AtomicBoolean()
    private val reportingLock = Any()
    private val firstFrame = AtomicReference<Observation.FirstFrame?>()
    private val firstFrameRecorded = AtomicBoolean()
    private val framesRendered = AtomicLong()
    private val lastFrameNanos = AtomicLong(UNSET_TIME)
    private val longestFrameGapNanos = AtomicLong()
    private val maximumFrameGapNanos = AtomicLong()
    private var firstFrameReported = false
    private var lastFrameReportNanos = UNSET_TIME
    private var lastStackReportNanos = UNSET_TIME
    // 仅诊断线程读写 pending；EDT 回调只发布完成时间，不等待诊断日志或抓栈。
    private var pending: Probe? = null
    private val executor = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "rwjs-startup-diagnostics").apply { isDaemon = true }
    }
    private val sampling = executor.scheduleAtFixedRate(
        { runCatching { sample() } }, 0, sampleIntervalMillis, TimeUnit.MILLISECONDS,
    )

    /** 在实际绘制回调完成时调用；这里只发布时间，磁盘日志留给后台诊断线程。 */
    fun onFrameRendered(continuousFramesExpected: Boolean = true) {
        if (closed.get()) return
        val now = System.nanoTime()
        framesRendered.incrementAndGet()
        if (firstFrameRecorded.compareAndSet(false, true)) {
            firstFrame.set(Observation.FirstFrame(elapsedMillis(now - startedNanos), Thread.currentThread().name))
        }
        // 静态界面只在文字/进度改变时重绘；两次更新之间的等待不代表动画掉帧。
        if (!continuousFramesExpected) {
            lastFrameNanos.set(UNSET_TIME)
            return
        }
        val previous = lastFrameNanos.getAndSet(now)
        if (previous != UNSET_TIME) {
            val gap = now - previous
            maximumFrameGapNanos.accumulateAndGet(gap, ::maxOf)
            if (gap >= slowThresholdNanos) longestFrameGapNanos.accumulateAndGet(gap, ::maxOf)
        }
    }

    private fun sample() {
        if (closed.get()) return
        val now = System.nanoTime()
        reportFrames(now)
        val probe = pending
        if (probe != null) {
            val completedAt = probe.completedNanos
            if (completedAt != UNSET_TIME) {
                val delay = completedAt - probe.enqueuedNanos
                if (delay >= slowThresholdNanos) {
                    report(Observation.EdtRecovered(elapsedMillis(delay), probe.sampled))
                }
                pending = null
            } else {
                val delay = now - probe.enqueuedNanos
                if (delay >= slowThresholdNanos && mayReport(now, lastStackReportNanos)) {
                    probe.sampled = true
                    lastStackReportNanos = now
                    report(Observation.EdtStall(elapsedMillis(delay), captureThreads()))
                }
                return
            }
        }
        if (closed.get()) return
        val next = Probe(System.nanoTime())
        pending = next
        // 每次最多排一个回调；EDT 卡住时不向事件队列持续堆积心跳。
        SwingUtilities.invokeLater {
            if (!closed.get()) next.completedNanos = System.nanoTime()
        }
    }

    private fun reportFrames(now: Long, force: Boolean = false) = synchronized(reportingLock) {
        if (closed.get()) return@synchronized
        val first = firstFrame.get()
        if (first != null && !firstFrameReported) {
            firstFrameReported = true
            report(first)
        }
        if (force || mayReport(now, lastFrameReportNanos)) {
            val gap = longestFrameGapNanos.getAndSet(0)
            if (gap > 0) {
                lastFrameReportNanos = now
                report(Observation.SlowFrame(elapsedMillis(gap)))
            }
        }
    }

    private fun mayReport(now: Long, previous: Long): Boolean =
        previous == UNSET_TIME || now - previous >= TimeUnit.MILLISECONDS.toNanos(reportIntervalMillis)

    private fun captureThreads(): List<ThreadSnapshot> {
        val threads = ManagementFactory.getThreadMXBean()
        // 先只查线程名，再对 EDT/游戏线程取有限栈，避免为整个 JVM 抓取完整堆栈。
        val ids = threads.getThreadInfo(threads.allThreadIds, 0).filterNotNull()
            .filter { it.threadName.startsWith("AWT-EventQueue-") || it.threadName == "rwjs-game" }
            .take(MAX_THREADS).map { it.threadId }.toLongArray()
        return threads.getThreadInfo(ids, MAX_STACK_DEPTH).filterNotNull().map {
            ThreadSnapshot(it.threadName, it.threadState, it.lockName, it.lockOwnerName, it.stackTrace.toList())
        }
    }

    private fun report(observation: Observation) = synchronized(reportingLock) {
        if (!closed.get()) runCatching { observer(observation) }
        Unit
    }

    override fun close() {
        synchronized(reportingLock) {
            if (closed.get()) return
            // 首帧后立即完成启动时，也保留尚未被下一次定时采样取走的绘制边界。
            reportFrames(System.nanoTime(), force = true)
            report(Observation.Summary(framesRendered.get(), elapsedMillis(maximumFrameGapNanos.get())))
            closed.set(true)
        }
        sampling.cancel(false)
        executor.shutdown()
    }

    internal fun awaitTermination(timeoutMillis: Long): Boolean =
        executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)

    internal sealed interface Observation {
        data class FirstFrame(val elapsedMillis: Long, val threadName: String) : Observation
        data class SlowFrame(val gapMillis: Long) : Observation
        data class EdtStall(val delayMillis: Long, val threads: List<ThreadSnapshot>) : Observation
        data class EdtRecovered(val delayMillis: Long, val sampledWhileBlocked: Boolean) : Observation
        data class Summary(val framesRendered: Long, val maxGapMillis: Long) : Observation
    }

    internal data class ThreadSnapshot(
        val name: String,
        val state: Thread.State,
        val lockName: String?,
        val lockOwnerName: String?,
        val stack: List<StackTraceElement>,
    ) {
        override fun toString(): String = buildString {
            append(name).append(" state=").append(state)
            lockName?.let { append(" lock=").append(it) }
            lockOwnerName?.let { append(" owner=").append(it) }
            stack.forEach { append("\n    at ").append(it) }
        }
    }

    private class Probe(val enqueuedNanos: Long) {
        @Volatile var completedNanos = UNSET_TIME
        var sampled = false
    }

    companion object {
        private const val UNSET_TIME = Long.MIN_VALUE
        private const val MAX_STACK_DEPTH = 24
        private const val MAX_THREADS = 4
        private val log = LoggerFactory.getLogger(DesktopStartupDiagnostics::class.java)

        fun start(): DesktopStartupDiagnostics = start(100, 500, 1_000, ::logObservation)

        internal fun start(
            sampleIntervalMillis: Long,
            slowThresholdMillis: Long,
            reportIntervalMillis: Long,
            observer: (Observation) -> Unit,
        ): DesktopStartupDiagnostics {
            require(sampleIntervalMillis > 0 && slowThresholdMillis > 0 && reportIntervalMillis > 0)
            return DesktopStartupDiagnostics(sampleIntervalMillis, slowThresholdMillis, reportIntervalMillis, observer)
        }

        private fun elapsedMillis(nanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanos)

        private fun logObservation(observation: Observation) {
            when (observation) {
                is Observation.FirstFrame -> log.info(
                    "[STARTUP-UI] first launcher frame rendered after {} ms since monitoring started (thread={})",
                    observation.elapsedMillis, observation.threadName,
                )
                is Observation.SlowFrame -> log.warn("[STARTUP-UI] launcher frame gap: {} ms", observation.gapMillis)
                is Observation.EdtStall -> log.warn(
                    "[STARTUP-UI] EDT heartbeat delayed {} ms; thread samples:\n{}",
                    observation.delayMillis, observation.threads.joinToString("\n"),
                )
                is Observation.EdtRecovered -> log.warn(
                    "[STARTUP-UI] EDT heartbeat recovered after {} ms (sampled while blocked={})",
                    observation.delayMillis, observation.sampledWhileBlocked,
                )
                is Observation.Summary -> log.info(
                    "[STARTUP-UI] monitoring complete: framesRendered={}, maxGapMillis={}",
                    observation.framesRendered, observation.maxGapMillis,
                )
            }
        }
    }
}
