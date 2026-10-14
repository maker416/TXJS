/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.rwpp.R
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.logger

/**
 * 联机期间的前台服务：防止进程退到后台后被冻结，并持有 CPU / Wi-Fi 锁让网络泵在锁屏时继续运行。
 * 启停由 [BackgroundKeepAlive] 统一管理，本服务不做任何引擎调用。
 */
class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        }.onFailure {
            logger.warn("[KEEPALIVE] startForeground failed: ${it.message}")
            stopSelf()
            return
        }
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        releaseLocks()
        BackgroundKeepAlive.onServiceDestroyed()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RWJS:multiplayer").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { logger.warn("[KEEPALIVE] wake lock unavailable: ${it.message}") }
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "RWJS:multiplayer").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { logger.warn("[KEEPALIVE] wifi lock unavailable: ${it.message}") }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = i18nOr("android.keepAliveTitle", "联机保持中")
        val text = i18nOr("android.keepAliveText", "切换应用或锁屏时将保持联机连接，点击返回游戏")
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, title, NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        // 用启动器 Intent 把已有任务带回前台，不新建 Activity 实例（对局页仍在栈顶）
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_2)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    private fun i18nOr(path: String, fallback: String): String =
        runCatching { readI18n(path) }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    companion object {
        private const val CHANNEL_ID = "rwjs_keepalive"
        private const val NOTIFICATION_ID = 0x5257
    }
}
