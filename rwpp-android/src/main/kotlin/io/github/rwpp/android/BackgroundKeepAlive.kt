/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.logger
import io.github.rwpp.platform.LauncherMusic
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 在连接前申请前台服务，连接中的资源由独立 token 持有，连接成功后交接给引擎状态。
 * 所有服务生命周期在 Main 串行处理；服务实例确认启动后才记为 RUNNING。
 * 网络主循环与原版停线程定时器的保护见 BaseEngineInject / DeferredGameStopInject。
 */
object BackgroundKeepAlive {
    private const val CHECK_INTERVAL_MS = 1000L
    private val state = BackgroundKeepAliveState()
    private val connectionIds = AtomicLong()
    private var startedActivities = 0
    private var service: KeepAliveService? = null
    private lateinit var appContext: Context

    @Volatile
    var isAppInForeground = false
        private set

    @Volatile
    private var hasPendingConnection = false

    private val checkTask = Runnable { syncService() }

    fun install(application: Application) {
        appContext = application.applicationContext
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) onForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0 && !activity.isChangingConfigurations) onBackground()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** 在预同步/Socket 连接开始前调用；允许嵌套，关闭旧 token 不会释放新连接。 */
    fun acquireConnection(): Closeable {
        val id = connectionIds.incrementAndGet()
        val closed = AtomicBoolean()
        onMain {
            if (!closed.get()) {
                state.beginConnection(id)
                hasPendingConnection = state.hasPendingConnection
                syncService()
            }
        }
        return Closeable {
            if (closed.compareAndSet(false, true)) onMain {
                state.endConnection(id)
                hasPendingConnection = state.hasPendingConnection
                syncService()
            }
        }
    }

    /** C 也用于遭遇战/沙盒；G 才能将原版的本地单人服务器排除。 */
    fun isNetworked(): Boolean {
        if (!gameLoaded) return false
        return runCatching { GameEngine.t()?.bU?.let { it.C && !it.G } == true }.getOrDefault(false)
    }

    fun shouldKeepGameThreadRunning(): Boolean = hasPendingConnection || isNetworked()

    /** 显式记录连接成功，覆盖第一次轮询之前就收到 relay 重定向、C 暂时变 false 的窗口。 */
    fun onConnected() = onMain {
        state.onConnected()
        syncService()
    }

    /** 显式退房直接结束重连宽限期；连接 token 仍由原操作的 finally 释放。 */
    fun onSessionEnded() = onMain {
        state.onSessionEnded()
        syncService()
    }

    internal fun acceptsServiceStart(id: Long): Boolean = state.acceptsStart(id)

    internal fun onServiceReady(instance: KeepAliveService, id: Long) {
        if (!state.onServiceReady(id)) {
            instance.stopSelf()
            return
        }
        service = instance
        logger.info("[KEEPALIVE] foreground service ready, generation=$id")
        syncService()
    }

    internal fun onServiceStartFailed(id: Long) {
        state.onStartFailed(id, SystemClock.elapsedRealtime(), serviceCreated = true)
    }

    internal fun onServiceDestroyed(instance: KeepAliveService, id: Long) {
        if (service === instance) service = null
        state.onServiceDestroyed(id)
        syncService()
    }

    private fun onForeground() {
        isAppInForeground = true
        state.foreground = true
        logger.info("[KEEPALIVE] app -> foreground")
        syncService()
    }

    private fun onBackground() {
        syncService()
        isAppInForeground = false
        state.foreground = false
        logger.info("[KEEPALIVE] app -> background, networked=${isNetworked()}")
        LauncherMusic.pause()
        if (gameLoaded) runCatching { GameEngine.t()?.bK?.d() }
        if (shouldKeepGameThreadRunning()) logPowerConstraints()
        syncService()
    }

    private fun syncService() {
        val now = SystemClock.elapsedRealtime()
        val localSinglePlayer = gameLoaded && runCatching { GameEngine.t()?.bU?.G == true }.getOrDefault(false)
        val needed = state.needsService(isNetworked(), localSinglePlayer, now)
        if (needed) {
            state.requestStart(now)?.let { id ->
                runCatching {
                    ContextCompat.startForegroundService(appContext, Intent(appContext, KeepAliveService::class.java)
                        .putExtra(KeepAliveService.EXTRA_GENERATION, id))
                }.onFailure {
                    state.onStartFailed(id, now, serviceCreated = false)
                    logger.warn("[KEEPALIVE] cannot start foreground service", it)
                }
            }
        } else {
            state.requestStop()?.let { id ->
                runCatching { appContext.stopService(Intent(appContext, KeepAliveService::class.java)) }
                    .onSuccess { stopped -> if (!stopped) state.onServiceDestroyed(id) }
                    .onFailure {
                        state.onStopFailed(id)
                        logger.warn("[KEEPALIVE] cannot stop foreground service", it)
                    }
            }
        }
        service?.updateBackgroundState(needed && !isAppInForeground)
        uiHandler.removeCallbacks(checkTask)
        // 前台采样未知的引擎状态；后台只在会话或服务尚未结束时兜底，菜单退后台不常驻轮询。
        if (isAppInForeground || needed || state.servicePhase != BackgroundKeepAliveState.ServicePhase.STOPPED) {
            uiHandler.postDelayed(checkTask, CHECK_INTERVAL_MS)
        }
    }

    private fun logPowerConstraints() {
        runCatching {
            val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            logger.info("[KEEPALIVE] batteryUnrestricted=${power.isIgnoringBatteryOptimizations(appContext.packageName)}, " +
                "deviceIdle=${power.isDeviceIdleMode}, lowPowerStandby=${Build.VERSION.SDK_INT >= 33 && power.isLowPowerStandbyEnabled}")
        }.onFailure { logger.warn("[KEEPALIVE] cannot query power restrictions", it) }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else uiHandler.post { action() }
    }
}
