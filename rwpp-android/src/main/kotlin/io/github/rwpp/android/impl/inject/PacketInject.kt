/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl.inject

import io.github.rwpp.android.bannedUnitList
import io.github.rwpp.android.impl.NetPacket
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.units.GameCommandActions
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.InterruptResult

/**
 * 判断一条游戏命令是否用于建造/生产被房间禁用的单位：
 * 1. 特殊动作（如工厂生产队列）的动作 id 形如 "u_<单位内部名>"；
 * 2. 路径点动作类型为建造（BUILD）时，取其目标单位类型名比对。
 */
internal fun isBannedCommand(command: NetPacket): Boolean {
    if (bannedUnitList.isEmpty()) return false

    val actionString = command.k.b
    if (actionString.removePrefix("u_") in bannedUnitList) return true

    val waypoint = command.j ?: return false
    val realAction = GameCommandActions.from(waypoint.a.ordinal)
    val unitType = waypoint.b ?: return false
    return realAction == GameCommandActions.BUILD && unitType.i() in bannedUnitList
}

@InjectClass(NetPacket::class)
object PacketInject {
    @Inject("a", InjectMode.InsertBefore)
    fun NetPacket.onSendGameCommand(a: com.corrodinggames.rts.gameFramework.j.bg) {
        if (settings.enhancedReinforceTroops) {
            val actionString = this.k.b
            if (actionString != "-1") {
                val l = wField.get(this) as List<*>
                val m = com.corrodinggames.rts.gameFramework.utility.p(l.sortedBy {
                    (it as? com.corrodinggames.rts.game.units.d.s)?.cY()?.size ?: 0
                })
                wField.set(this, m)
            }
        }
    }

    // 禁用单位功能的执行侧拦截（兜底）。
    //
    // 命令到达预定帧后会在每端统一执行（e.h()，issueCommand）。
    // 仅在 NetInject 的命令入队/转发处（ae.a(e)）拦截无法覆盖所有路径，
    // 在此同步拦截，保证所有端一致跳过被禁命令，避免游戏状态分叉。

    /** 命令到达预定帧后在每端统一执行（issueCommand）。 */
    @Inject("h", injectMode = InjectMode.InsertBefore)
    fun NetPacket.onCommandIssued(): Any {
        return if (isBannedCommand(this)) InterruptResult.Unit else Unit
    }

    private val wField = NetPacket::class.java.getDeclaredField("w").apply {
        isAccessible = true
    }

    private val settings = appKoin.get<Settings>()
}
