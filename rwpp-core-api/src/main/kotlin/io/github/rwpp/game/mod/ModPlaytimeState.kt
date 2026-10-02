/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

/** active 仅表示引擎正在推进；游玩秒数须使用真实单调时钟，不能累加游戏帧或模拟时间。 */
data class ModPlaytimeState(
    val hasMatch: Boolean = false,
    val active: Boolean = false,
    val mods: List<Mod> = emptyList(),
    /** 平台采样器的本局序号；快速回房再开局时也改变，不能仅靠 hasMatch 的采样边缘识别。 */
    val generation: Long = 0,
    /** false 表示采样失败；调用方只能暂停计时，不能据此认定退出对局。 */
    val reliable: Boolean = true,
)
