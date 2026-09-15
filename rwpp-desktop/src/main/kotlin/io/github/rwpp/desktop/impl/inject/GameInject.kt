/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl.inject

import com.corrodinggames.rts.game.i
import io.github.rwpp.appKoin
import io.github.rwpp.desktop.GameEngine
import io.github.rwpp.desktop._gameSpeed
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.InterruptResult
import io.github.rwpp.logger

@InjectClass(i::class)
object GameInject {
    val room by lazy {
        appKoin.get<Game>().gameRoom
    }

    @Inject("b", InjectMode.InsertBefore)
    fun updateAndRender(deltaSpeed: Float) {
        if (room.isHost && _gameSpeed != 1f) {
            GameEngine.B().bX.K = 1f / _gameSpeed
            GameEngine.B().bX.a(1f / _gameSpeed, "speed")
            (GameEngine.B() as i).H = _gameSpeed
        } else {
            (GameEngine.B() as i).H = 1f
            GameEngine.B().bX.K = null
        }
    }

    /**
     * 主循环 `b(float,int)`：保连接重载期间只泵网络并跳过原方法体（单位 tick）。
     */
    @Inject("b", InjectMode.InsertBefore, "(FI)V")
    fun onMainLoop(deltaTime: Float, unused: Int): Any {
        if (!KeepConnectedReload.active) return Unit
        KeepConnectedReload.markGameTick()
        try {
            GameEngine.B().bX.a(deltaTime)
        } catch (e: Throwable) {
            logger.warn("[MODSYNC] net-only pump failed: ${e.message}")
        }
        return InterruptResult.Unit
    }
}
