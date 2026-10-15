/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

/** 仅由 Main 线程驱动；时钟与 Android 无关，便于验证迟到回调和重连时序。 */
internal class BackgroundKeepAliveState(private val reconnectGraceMs: Long = 10_000L) {
    enum class ServicePhase { STOPPED, STARTING, RUNNING, STOPPING }

    private val connections = mutableSetOf<Long>()
    private var wasNetworked = false
    private var reconnectUntilMs = 0L
    private var retryAfterMs = 0L

    var foreground = false
    var generation = 0L
        private set
    var servicePhase = ServicePhase.STOPPED
        private set

    val hasPendingConnection: Boolean get() = connections.isNotEmpty()

    fun beginConnection(id: Long) { connections.add(id) }
    fun endConnection(id: Long) { connections.remove(id) }

    fun onConnected() {
        wasNetworked = true
        reconnectUntilMs = 0L
    }

    fun onSessionEnded() {
        wasNetworked = false
        reconnectUntilMs = 0L
    }

    fun needsService(networked: Boolean, localSinglePlayer: Boolean, nowMs: Long): Boolean {
        if (localSinglePlayer) {
            onSessionEnded()
        } else if (networked) {
            wasNetworked = true
            reconnectUntilMs = 0L
        } else if (wasNetworked) {
            wasNetworked = false
            reconnectUntilMs = nowMs + reconnectGraceMs
        }
        return hasPendingConnection || networked && !localSinglePlayer || nowMs < reconnectUntilMs
    }

    fun requestStart(nowMs: Long): Long? {
        if (!foreground || nowMs < retryAfterMs ||
            servicePhase != ServicePhase.STOPPED && servicePhase != ServicePhase.STOPPING) return null
        servicePhase = ServicePhase.STARTING
        return ++generation
    }

    fun acceptsStart(id: Long): Boolean = id == generation &&
        (servicePhase == ServicePhase.STARTING || servicePhase == ServicePhase.RUNNING)

    fun onServiceReady(id: Long): Boolean {
        if (!acceptsStart(id)) return false
        servicePhase = ServicePhase.RUNNING
        return true
    }

    fun requestStop(): Long? {
        if (servicePhase != ServicePhase.STARTING && servicePhase != ServicePhase.RUNNING) return null
        servicePhase = ServicePhase.STOPPING
        return generation
    }

    fun onStartFailed(id: Long, nowMs: Long, serviceCreated: Boolean) {
        if (id != generation) return
        retryAfterMs = nowMs + 5_000L
        servicePhase = if (serviceCreated) ServicePhase.STOPPING else ServicePhase.STOPPED
    }

    fun onStopFailed(id: Long) {
        if (id == generation && servicePhase == ServicePhase.STOPPING) servicePhase = ServicePhase.RUNNING
    }

    fun onServiceDestroyed(id: Long) {
        if (id == generation) servicePhase = ServicePhase.STOPPED
    }
}

/** 原版网络 delta 的单位为 1/60 秒，原版主循环也会将它限制在 0..3。 */
internal class BackgroundPumpCadence {
    private var lastPumpNanos: Long? = null

    fun delta(nowNanos: Long, initialDelta: Float): Float {
        val previous = lastPumpNanos
        lastPumpNanos = nowNanos
        return (if (previous == null) initialDelta else (nowNanos - previous) * 60f / 1_000_000_000f)
            .takeIf { it.isFinite() }?.coerceIn(0f, 3f) ?: 0f
    }

    fun remainingNanos(startNanos: Long, nowNanos: Long): Long =
        (INTERVAL_NANOS - (nowNanos - startNanos).coerceAtLeast(0L)).coerceAtLeast(0L)

    fun reset() { lastPumpNanos = null }

    companion object {
        const val INTERVAL_NANOS = 50_000_000L
    }
}
