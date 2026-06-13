/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.rwpp.appKoin
import io.github.rwpp.config.ConfigIO

private const val INIT_CONFIG_GROUP = "rwpp_init"
private const val REBUILD_ATTEMPTS_KEY = "rebuildAttempts"
private const val RESTART_REQUEST_CODE = 0x52575050 // "RWPP"

/**
 * 注入产物重建后允许的最大连续自动重启次数。
 * 超过该次数仍需重建时，停在诊断界面而不再自动重启，避免陷入死循环。
 */
const val MAX_REBUILD_ATTEMPTS = 2

/**
 * 彻底重启应用进程。
 *
 * 先用 [AlarmManager] 安排重新拉起 [LoadingScreen]，再结束当前进程，
 * 以保证在全新进程中于启动早期干净加载注入后的 DEX（跨 Android 版本/厂商最稳）。
 * 该方法不会返回。
 */
fun scheduleAppRestart(context: Context, delayMs: Long = 400L) {
    runCatching {
        val appContext = context.applicationContext
        val intent = Intent(appContext, LoadingScreen::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        val pending = PendingIntent.getActivity(
            appContext,
            RESTART_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMs
        // 优先精确闹钟；若系统不允许精确闹钟（缺少权限）则回退为非精确闹钟。
        val exactScheduled = runCatching {
            alarmManager.setExact(AlarmManager.RTC, triggerAt, pending)
            true
        }.getOrDefault(false)
        if (!exactScheduled) {
            alarmManager.set(AlarmManager.RTC, triggerAt, pending)
        }
    }
    Runtime.getRuntime().exit(0)
}

/** 读取当前连续重建计数；读取失败时按 0 处理。 */
fun getRebuildAttemptCount(): Int =
    runCatching {
        appKoin.get<ConfigIO>().readSingleConfig(INIT_CONFIG_GROUP, REBUILD_ATTEMPTS_KEY)?.toIntOrNull() ?: 0
    }.getOrDefault(0)

/** 连续重建计数加一并返回新值。 */
fun incrementRebuildAttemptCount(): Int {
    val next = getRebuildAttemptCount() + 1
    runCatching { appKoin.get<ConfigIO>().saveSingleConfig(INIT_CONFIG_GROUP, REBUILD_ATTEMPTS_KEY, next) }
    return next
}

/** 进入良好状态（DEX 干净加载并校验通过）后清零重建计数。 */
fun resetRebuildAttemptCount() {
    runCatching { appKoin.get<ConfigIO>().saveSingleConfig(INIT_CONFIG_GROUP, REBUILD_ATTEMPTS_KEY, 0) }
}
