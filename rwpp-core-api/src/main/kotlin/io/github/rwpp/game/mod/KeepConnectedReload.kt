/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 保连接重载闸门：置位期间游戏主循环只泵网络、跳过单位 tick，
 * [bW.a] 在工作线程重建单位表。主循环若超过 [WATCHDOG_STALE_MS] 未进入，
 * 由看门狗线程补调网络 tick，避免 GameView 暂停时连接超时。
 */
object KeepConnectedReload {
    const val WATCHDOG_STALE_MS = 50L
    const val WATCHDOG_SLEEP_MS = 16L
    const val WATCHDOG_DELTA = 16f

    private val activeFlag = AtomicBoolean(false)
    private val abortToVanillaFlag = AtomicBoolean(false)
    private val vanillaFallbackDoneFlag = AtomicBoolean(false)
    private val refreshMenuAfterAbortFlag = AtomicBoolean(false)
    private val lastGameTickAt = AtomicLong(0L)

    val active: Boolean
        get() = activeFlag.get()

    /** 已请求中止自定义模组解析并回落到原版单位。 */
    val abortToVanilla: Boolean
        get() = abortToVanillaFlag.get()

    val didVanillaFallback: Boolean
        get() = vanillaFallbackDoneFlag.get()

    /** 取消回落后须在 [end] 前重建菜单（Android `i.q()`），避免与单位表重建并发。 */
    val shouldRefreshMenuAfterAbort: Boolean
        get() = refreshMenuAfterAbortFlag.get()

    fun begin() {
        lastGameTickAt.set(System.currentTimeMillis())
        activeFlag.set(true)
        vanillaFallbackDoneFlag.set(false)
    }

    fun requestAbortToVanilla() {
        abortToVanillaFlag.set(true)
        refreshMenuAfterAbortFlag.set(true)
    }

    fun shouldAbortCustomModLoad(): Boolean = abortToVanillaFlag.get()

    /** 第二次原版-only 重载前解除注入抛出，避免把原版目录加载也打断。 */
    fun disarmAbortInject() {
        abortToVanillaFlag.set(false)
    }

    fun markVanillaFallbackDone() {
        vanillaFallbackDoneFlag.set(true)
    }

    fun end() {
        activeFlag.set(false)
        abortToVanillaFlag.set(false)
        refreshMenuAfterAbortFlag.set(false)
    }

    fun markGameTick() {
        lastGameTickAt.set(System.currentTimeMillis())
    }

    fun isWatchdogDue(): Boolean {
        if (!activeFlag.get()) return false
        return System.currentTimeMillis() - lastGameTickAt.get() > WATCHDOG_STALE_MS
    }
}

/** 保连接重载期间用户取消：注入层抛出以中止 `ag.e()`/`ag.h()` 的后续自定义模组解析。 */
class ReloadAbortToVanilla : RuntimeException("mod reload aborted to vanilla")
