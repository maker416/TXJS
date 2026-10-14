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
import android.os.Bundle
import androidx.core.content.ContextCompat
import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.logger
import io.github.rwpp.platform.LauncherMusic

/**
 * Android 后台保活编排。
 *
 * 原版引擎在后台会掉线的两条链路：
 * 1. GameView 的 Surface 被销毁后，主循环 `a(float,int)` 在 `ae.k()` 之后直接返回，
 *    网络泵 `ae.a(float)`/`ae.l()` 不再执行（等待房间与对局都会命中）；
 * 2. 对局中 `InGameActivity.onStop` → `i.a(Activity, ab)` 起 700ms 定时器停掉游戏主线程。
 * 此外进程退到后台后没有前台服务，会被系统冻结 / CPU 休眠。
 *
 * 这里负责：前后台判定（按已 start 的 Activity 计数）、联网期间维持 [KeepAliveService]、
 * 退到后台时暂停背景音乐。主循环的补泵与停线程拦截在 `BaseEngineInject`。
 */
object BackgroundKeepAlive {
    private const val CHECK_INTERVAL_MS = 1000L

    private var startedActivities = 0

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    @Volatile
    private var serviceRunning = false

    private lateinit var appContext: Context

    private val checkTask = object : Runnable {
        override fun run() {
            syncService()
            uiHandler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun install(application: Application) {
        appContext = application.applicationContext
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) onForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                // 横竖屏重建时会先 stop 再 start，不算真正退到后台
                if (startedActivities == 0 && !activity.isChangingConfigurations) onBackground()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        uiHandler.post(checkTask)
    }

    /** 引擎已加载且处于联机（房间或对局）中。 */
    fun isNetworked(): Boolean {
        // 引擎 dex 加载前不得触碰游戏类，否则类解析失败可能被缓存
        if (!gameLoaded) return false
        return runCatching { GameEngine.t()?.bU?.C == true }.getOrDefault(false)
    }

    private fun onForeground() {
        isAppInForeground = true
        logger.info("[KEEPALIVE] app -> foreground")
        syncService()
    }

    private fun onBackground() {
        // 进房后 1s 轮询尚未拉起服务就立刻切走：趁仍记为前台时补一次（失败由 runCatching 兜住）
        syncService()
        isAppInForeground = false
        logger.info("[KEEPALIVE] app -> background, networked=${isNetworked()}")
        LauncherMusic.pause()
        // 原版菜单/局内音乐由引擎自己的 MusicController 播放，退后台不会自动停；
        // 回到前台时 MainActivity/InGameActivity 的 onResume 会经引擎 A() 恢复。
        if (gameLoaded) runCatching { GameEngine.t()?.bK?.d() }
        syncService()
    }

    private fun syncService() {
        val networked = isNetworked()
        if (networked && !serviceRunning) {
            // Android 12+ 禁止后台启动前台服务：只在前台时启动（进房 / 开局必然在前台）
            if (!isAppInForeground) return
            runCatching {
                ContextCompat.startForegroundService(appContext, Intent(appContext, KeepAliveService::class.java))
                serviceRunning = true
                logger.info("[KEEPALIVE] foreground service started")
            }.onFailure { logger.warn("[KEEPALIVE] cannot start foreground service: ${it.message}") }
        } else if (!networked && serviceRunning) {
            runCatching { appContext.stopService(Intent(appContext, KeepAliveService::class.java)) }
            serviceRunning = false
            logger.info("[KEEPALIVE] foreground service stopped")
        }
    }

    /** 服务被系统停止（或启动失败）时回写状态，下次前台检查可重新拉起。 */
    internal fun onServiceDestroyed() {
        serviceRunning = false
    }
}
