/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.rwpp.R
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.logger
import io.github.rwpp.platform.KEEP_ALIVE_NOTIFICATION_CHANNEL_ID

/**
 * 联机期间的前台服务。仅在后台持有有期限、可续期的 CPU 锁，退出会话即释放。
 * Android 14+ 不再申请不能在锁屏生效的 Wi-Fi 低延迟锁；系统省电限制由设置页说明。
 * 启停由 [BackgroundKeepAlive] 统一管理，本服务不做任何引擎调用。
 */
class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var generation = -1L
    private var promoted = false
    private var nextWakeRenewalMs = 0L
    private var nextWifiAttemptMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_GENERATION, -1L) ?: -1L
        if (!BackgroundKeepAlive.acceptsServiceStart(id)) {
            if (!promoted) {
                generation = id
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        generation = id
        if (!promoted) {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
                promoted = true
            } catch (e: Exception) {
                logger.warn("[KEEPALIVE] startForeground failed", e)
                BackgroundKeepAlive.onServiceStartFailed(id)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        BackgroundKeepAlive.onServiceReady(this, id)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        BackgroundKeepAlive.onServiceDestroyed(this, generation)
        super.onDestroy()
    }

    internal fun updateBackgroundState(background: Boolean) {
        if (background && promoted) acquireLocks() else releaseLocks()
    }

    private fun acquireLocks() {
        val now = SystemClock.elapsedRealtime()
        if (now >= nextWakeRenewalMs) {
            nextWakeRenewalMs = now + 30_000L
            runCatching {
                val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RWJS:multiplayer")
                    .apply { setReferenceCounted(false) }.also { wakeLock = it }
                lock.acquire(120_000L)
            }.onFailure { logger.warn("[KEEPALIVE] wake lock unavailable", it) }
        }
        // 29..33 的 HIGH_PERF 可用于熄屏；34+ 会被映射成仅亮屏前台生效的 LOW_LATENCY。
        if (Build.VERSION.SDK_INT < 34 && wifiLock == null && now >= nextWifiAttemptMs) {
            nextWifiAttemptMs = now + 30_000L
            runCatching {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RWJS:multiplayer")
                wifiLock = lock
                lock.setReferenceCounted(false)
                lock.acquire()
            }.onFailure {
                runCatching { wifiLock?.takeIf { lock -> lock.isHeld }?.release() }
                wifiLock = null
                logger.warn("[KEEPALIVE] wifi lock unavailable", it)
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
        nextWakeRenewalMs = 0L
        nextWifiAttemptMs = 0L
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = i18nOr("android.keepAliveTitle", "联机保持中")
        val text = i18nOr("android.keepAliveText", "正在连接或保持联机，点击返回游戏")
        manager.createNotificationChannel(
            NotificationChannel(KEEP_ALIVE_NOTIFICATION_CHANNEL_ID, title, NotificationManager.IMPORTANCE_LOW).apply {
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
        return NotificationCompat.Builder(this, KEEP_ALIVE_NOTIFICATION_CHANNEL_ID)
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
        internal const val EXTRA_GENERATION = "io.github.rwpp.keepalive.GENERATION"
        private const val NOTIFICATION_ID = 0x5257
    }
}
