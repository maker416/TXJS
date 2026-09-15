/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl.inject

import com.corrodinggames.rts.game.i
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
     */
    @Inject("a", InjectMode.InsertBefore, "(FI)V")
    fun onMainLoop(deltaTime: Float, unused: Int): Any {
        if (!KeepConnectedReload.active) return Unit
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
}