/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl.inject

import android.app.Activity
import com.corrodinggames.rts.appFramework.ab
import com.corrodinggames.rts.game.i
import io.github.rwpp.android.BackgroundKeepAlive
import io.github.rwpp.android.BackgroundPumpCadence
import io.github.rwpp.android.NetworkOnlyPump
import io.github.rwpp.android._gameSpeed
import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.android.mainThreadChannel
import io.github.rwpp.appKoin
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.InterruptResult
import io.github.rwpp.logger

@InjectClass(com.corrodinggames.rts.game.i::class)
object BaseEngineInject {
    val room by lazy { appKoin.get<Game>().gameRoom }
    private val backgroundCadence = BackgroundPumpCadence()
    @Inject("b", InjectMode.InsertBefore)
    fun onUpdate(deltaTime: Float) {
        mainThreadChannel.tryReceive().getOrNull()?.invoke()
        if (room.isHost && _gameSpeed != 1f) {
            GameEngine.t().bU.K = 1f / _gameSpeed
            GameEngine.t().bU.M = 1f / _gameSpeed
            (GameEngine.t() as i).G = _gameSpeed
        } else {
            GameEngine.t().bU.M = 1f
            (GameEngine.t() as i).G = 1f
        }
    }

    /**
     * 主循环 `a(float,int)`：保连接重载期间只泵网络并跳过原方法体（单位 tick），
     * 避免与工作线程上的 `bW.a()` 抢单位表。
     *
     * 后台保活：切应用 / 锁屏后 GameView 的 Surface 被销毁，原版主循环在 `ae.k()` 之后
     * 直接返回，`ae.l()`/`ae.a(float)` 不再执行。多人会话在后台或无 Surface 时只泵网络，
     * 后台频率为 20Hz；网络仍在游戏线程和 ai 锁内执行，等待在锁外，回前台恢复原版路径。
     */
    @Inject("a", InjectMode.InsertBefore, "(FI)V")
    fun onMainLoop(deltaTime: Float, unused: Int): Any {
        if (KeepConnectedReload.active) {
            backgroundCadence.reset()
            mainThreadChannel.tryReceive().getOrNull()?.invoke()
            try {
                NetworkOnlyPump.duringReload(deltaTime)
            } catch (e: Throwable) {
                logger.warn("[MODSYNC] net-only pump failed: ${e.message}")
            }
            return InterruptResult.Unit
        }

        val engine = GameEngine.t()
        val net = engine.bU ?: return Unit
        if (!net.C || net.G || BackgroundKeepAlive.isAppInForeground && engine.ao?.getSurfaceExists() == true) {
            backgroundCadence.reset()
            return Unit
        }

        val startNanos = System.nanoTime()
        val networkDelta = backgroundCadence.delta(startNanos, deltaTime)
        mainThreadChannel.tryReceive().getOrNull()?.invoke()
        try {
            synchronized(engine.ai) {
                NetworkOnlyPump.inBackground(engine, networkDelta)
            }
        } catch (e: Throwable) {
            logger.warn("[KEEPALIVE] background net pump failed: ${e.message}")
        }
        // 原版 GameThread 的 60/300Hz 限频仍保留；在锁外补足后台周期，避免空转唤醒。
        if (!BackgroundKeepAlive.isAppInForeground && !KeepConnectedReload.active) {
            val delay = backgroundCadence.remainingNanos(startNanos, System.nanoTime())
            if (delay > 0L) try {
                Thread.sleep(delay / 1_000_000L, (delay % 1_000_000L).toInt())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return InterruptResult.Unit
    }

    /**
     * 原版 `InGameActivity.onStop` → 本方法 → `d(false)`：700ms 后 `C()` 停掉游戏主线程，
     * 联机对局随之断线。联机时只通知视图 `onParentStop`，保留主线程（音乐由
     * `BackgroundKeepAlive` 暂停，回前台 `onResume` → `A()` 恢复）；单机仍走原版停线程以省电。
     */
    @Inject("a", InjectMode.InsertBefore, "(Landroid/app/Activity;Lcom/corrodinggames/rts/appFramework/ab;)V")
    fun onActivityStop(activity: Activity?, view: ab?): Any {
        if (!BackgroundKeepAlive.shouldKeepGameThreadRunning()) return Unit
        logger.info("[KEEPALIVE] keep game thread running after activity stop (multiplayer/connecting)")
        runCatching { view?.onParentStop() }
        return InterruptResult.Unit
    }
}
