/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import com.corrodinggames.rts.java.u
import org.newdawn.slick.GameContainer
import org.newdawn.slick.Graphics
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.logger

class RwInternalGameImpl(str: String) : u(str) {
    private var lastUpdateFailure: String? = null
    private var lastUpdateFailureAt = 0L

    override fun render(container: GameContainer?, graphics: Graphics?) {
        // 共享 GL 工作线程重建单位表时，游戏线程继续 update/网络 tick，但不读单位绘制表。
        if (!KeepConnectedReload.active) super.render(container, graphics)
    }

    override fun update(p0: GameContainer?, p1: Int) {
        try {
            super.update(p0, p1)
        } catch (e: Exception) {
            val failure = "${e.javaClass.name}:${e.message}"
            val now = System.currentTimeMillis()
            if (failure != lastUpdateFailure || now - lastUpdateFailureAt >= 60_000L) {
                logger.error("[GAME] update failed on thread=${Thread.currentThread().name}", e)
                lastUpdateFailure = failure
                lastUpdateFailureAt = now
            }
        }
    }


    override fun b() {
    }


//    override fun keyPressed(p0: Int, p1: Char) {
//        l.B()?.b(SlickToAndroidKeycodes.b(p0), true)
//    }
//
//    override fun keyReleased(p0: Int, p1: Char) {
//        // 暂时不实现
//    }
}
