/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.game.Player
import java.util.IdentityHashMap

/** 单个玩家列表持有的引用身份；昵称、槽位、队伍和连接 id 变化不重建现有行。 */
internal class RoomPlayerKeyStore {
    private val keys = IdentityHashMap<Player, Long>()
    private var nextKey = 0L

    fun keysFor(players: List<Player>): List<Long> {
        val present = IdentityHashMap<Player, Boolean>()
        players.forEach { present[it] = true }
        keys.keys.removeAll { it !in present }
        return players.map { player -> keys.getOrPut(player) { nextKey++ } }
    }
}
