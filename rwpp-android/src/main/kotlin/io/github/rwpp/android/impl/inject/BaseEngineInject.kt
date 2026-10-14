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
     * 直接返回，`ae.l()`/`ae.a(float)` 不再执行，房间与对局随后超时掉线。联机期间无 Surface
     * 时改为在引擎锁 `ai` 内只泵网络（不推进模拟、不绘制），回到前台后由原版追帧逻辑补齐。
     */
    @Inject("a", InjectMode.InsertBefore, "(FI)V")
    fun onMainLoop(deltaTime: Float, unused: Int): Any {
        if (KeepConnectedReload.active) {
            KeepConnectedReload.markGameTick()
            mainThreadChannel.tryReceive().getOrNull()?.invoke()
            try {
                val net = GameEngine.t().bU
                net.l()
                net.a(deltaTime)
            } catch (e: Throwable) {
                logger.warn("[MODSYNC] net-only pump failed: ${e.message}")
            }
            return InterruptResult.Unit
        }

        val engine = GameEngine.t()
        val net = engine.bU ?: return Unit
        if (!net.C) return Unit
        val view = engine.ao ?: return Unit
        if (view.getSurfaceExists()) return Unit

        mainThreadChannel.tryReceive().getOrNull()?.invoke()
        try {
            synchronized(engine.ai) {
                net.k()
                net.l()
                net.a(deltaTime)
            }
        } catch (e: Throwable) {
            logger.warn("[KEEPALIVE] background net pump failed: ${e.message}")
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
        if (!BackgroundKeepAlive.isNetworked()) return Unit
        logger.info("[KEEPALIVE] keep game thread running after activity stop (networked)")
        runCatching { view?.onParentStop() }
        return InterruptResult.Unit
    }
}