/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl.inject

import com.corrodinggames.rts.game.units.d.l
import com.corrodinggames.rts.game.units.y
import com.corrodinggames.rts.gameFramework.e
import com.corrodinggames.rts.gameFramework.utility.m
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.desktop.NetPacket
import io.github.rwpp.desktop.bannedUnitList
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
internal fun isBannedCommand(command: e): Boolean {
    if (bannedUnitList.isEmpty()) return false

    val actionString = command.k.a()
    if (actionString.removePrefix("u_") in bannedUnitList) return true

    val waypoint = command.j ?: return false
    val realAction = GameCommandActions.from(waypoint.d().ordinal)
    val unitType = waypoint.a() ?: return false
    return realAction == GameCommandActions.BUILD && unitType.v() in bannedUnitList
}

@InjectClass(NetPacket::class)
object NetPacketInject {
    @Suppress("UNCHECKED_CAST")
    @Inject("a", injectMode = InjectMode.InsertBefore)
    fun e.onEnhancedReinforceTroops(): Any {
        val settings = appKoin.get<Settings>()
        if (settings.enhancedReinforceTroops) {
            val actionString = k.a()
            if (actionString != "-1") {
                val l = vField.get(this) as List<y>
                val m = m(l.sortedBy { (it as? l)?.dx()?.size ?: 0 })
                vField.set(this, m)
            }
        }

        return Unit
    }

    // 以下两个注入为禁用单位功能的执行侧拦截。
    //
    // 仅在 NetworkInject 的命令入队/转发处（ad.a(e)）拦截是不够的：
    // 1.15 版本的网络模型中，下达命令的一端会先在本地即时应用命令
    // （e.j()，如工厂立即出现生产队列并开始生产），之后才在预定帧统一执行（e.k()）。
    // 若只在入队处丢弃，发起端本机已经生效的命令不会被其他端承认，
    // 被禁单位照样在本机造出来，随后整局游戏状态分叉（不同步）。
    // 因此在命令真正生效的两个时机同步拦截，保证所有端一致跳过被禁命令。

    /** 下达命令时发起端的本地即时应用（特殊动作，如工厂排队生产）。 */
    @Inject("j", injectMode = InjectMode.InsertBefore)
    fun e.onCommandAppliedLocally(): Any {
        return if (isBannedCommand(this)) InterruptResult.Unit else Unit
    }

    /** 命令到达预定帧后在每端统一执行（issueCommand）。 */
    @Inject("k", injectMode = InjectMode.InsertBefore)
    fun e.onCommandIssued(): Any {
        return if (isBannedCommand(this)) InterruptResult.Unit else Unit
    }

    private val vField = NetPacket::class.java.getDeclaredField("v").apply { isAccessible = true }
}
