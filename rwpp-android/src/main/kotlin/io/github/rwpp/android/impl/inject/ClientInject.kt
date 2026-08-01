/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl.inject

import io.github.rwpp.android.uiHandler
import io.github.rwpp.appKoin
import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.PlayerLeaveEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.Player
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.ui.UI
import kotlin.concurrent.thread

@InjectClass(com.corrodinggames.rts.gameFramework.j.c::class)
object ClientInject {
    @Inject("a", InjectMode.InsertBefore)
    fun com.corrodinggames.rts.gameFramework.j.c.onPlayerDisconnect(
        z1: Boolean,
        z2: Boolean,
        str: String?
    ) {
        if (this.A != null) {
            PlayerLeaveEvent(this.A as Player, com.corrodinggames.rts.gameFramework.j.ae
                .g(str ?: "")).broadcastIn()
        } else {
            // A 为 null 说明断开的可能是「客户端 → 房主/中继」连接（房主退出、超时、被踢），
            // 但进房过程中引擎还会建立探测/备用等辅助连接，它们断开也会走到这里。
            // 判定语义取「最后一条活跃连接」：本条断开后队列 aO 中已无任何其他活跃连接
            // （c.g() == 未标记断开中），才认为与房主的连接真正断开；
            // 若还有别的活跃连接（如主连接仍在），说明断开的只是辅助连接，直接忽略，
            // 否则会误杀正在进行的模组同步。主连接先断、辅助连接苟延的情况稍滞后，
            // 等辅助连接也断开时仍会触发，不会漏报。
            // 引擎自身只会在聊天区打印 "The server disconnected"，不会回调 RWPP 的断连流程，
            // 模组同步进度卡片会因此卡死在最后进度。这里补齐完整断连：
            // gameRoom.disconnect -> DisconnectEvent -> Logic 清理传输并关闭进度卡片、
            // 提示「房主连接已断开」后返回房间列表。
            // 必须异步执行：ae.b() 内部会 join 连接读线程，而本钩子正运行在该读线程上，
            // 同步调用将自我 join 死锁。
            val net = GameEngine.t().bU
            val hasOtherActive = net.aO
                .filterIsInstance<com.corrodinggames.rts.gameFramework.j.c>()
                .any { it !== this && it.g() }
            if (!net.D && net.C && !hasOtherActive) {
                val reason = com.corrodinggames.rts.gameFramework.j.ae.g(str ?: "")
                thread(isDaemon = true) {
                    runCatching { appKoin.get<Game>().gameRoom.disconnect(reason) }
                    uiHandler.post {
                        UI.showWarning(readI18n("multiplayer.hostDisconnected"), true)
                    }
                }
            }
        }
    }
}