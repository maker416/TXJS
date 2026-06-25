/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import org.koin.core.component.KoinComponent

interface ModManager : KoinComponent {
    /**
     * 重新加载 mod 列表。
     *
     * @param forceImmediate 是否强制在当前线程立即执行重载，而非投递到游戏主线程后等待。
     *        默认 false：通过 [io.github.rwpp.game.Game.post] 把重载逻辑投递到游戏主循环消费，
     *        适用于游戏已运行（主循环在跑）的场景，如 Mods 页手动重载。
     *        传 true：直接在当前协程线程同步执行引擎重载方法，绕过主循环依赖。
     *        用于 mod 同步：下载完成时加入者仍处于加载阶段、游戏主循环 [com.corrodinggames.rts.game.i.b] 尚未启动，
     *        此时投递到主线程的 action 永远不会被消费，会导致 latch 永久阻塞、loading 弹窗卡死。
     */
    suspend fun modReload(forceImmediate: Boolean = false)

    suspend fun modUpdate()

    suspend fun modSaveChange()

    fun getModByName(name: String): Mod?

    fun getAllMods(): List<Mod>
}