/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.game.mod.KeepConnectedReload

/** 后台、重载主循环和重载看门狗共用互斥，避免同时修改网络状态或重复推进 delta。 */
internal object NetworkOnlyPump {
    private val lock = Any()

    fun duringReload(delta: Float, watchdog: Boolean = false) = synchronized(lock) {
        if (!KeepConnectedReload.active || watchdog && !KeepConnectedReload.isWatchdogDue()) return@synchronized
        try {
            val net = GameEngine.t().bU
            net.l()
            net.a(delta)
        } finally {
            KeepConnectedReload.markGameTick()
        }
    }

    /** 调用方已持有原版 ai 锁；所有等待必须留在锁外。 */
    fun inBackground(engine: GameEngine, delta: Float) = synchronized(lock) {
        if (KeepConnectedReload.active) {
            duringReload(delta)
        } else {
            val net = engine.bU
            if (net.C && !net.G) {
                net.k()
                net.l()
                net.a(delta)
            }
        }
    }
}
