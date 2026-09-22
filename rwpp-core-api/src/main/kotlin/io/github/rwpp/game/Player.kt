/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game

import io.github.rwpp.game.data.PlayerData
import io.github.rwpp.game.data.PlayerStatisticsData
import io.github.rwpp.net.Client

interface Player {
    val connectHexId: String
    var spawnPoint: Int
    var name: String
    val ping: String
    var team: Int
    val pingNumber: Int

    /**
     * Set player's starting unit.
     *
     * None if value equals -1
     */
    var startingUnit: Int

    /**
     * Set player's color.
     *
     * None if value equals -1
     */
    var color: Int

    /**
     * isSpectator: team == -3
     */
    val isSpectator: Boolean
    val isAI: Boolean

    /**
     * 该玩家是否为房间房主（引擎侧标记：自建主机显示 "HOST"，中继房创建者显示 " (HOST)"）。
     * 用于校验房间控制消息（如邀请策略广播）的发送者身份。
     */
    val isRoomHost: Boolean
    var difficulty: Int?

    /**
     * The player's credits.
     */
    var credits: Int

    /**
     * The player's statistics data.
     */
    val statisticsData: PlayerStatisticsData

    /**
     * The player's income.
     */
    val income: Int

    val isDefeated: Boolean
    val isWipedOut: Boolean

    val data: PlayerData

    val client: Client?

    fun applyConfigChange(
        spawnPoint: Int = this.spawnPoint,
        team: Int = this.team,
        color: Int? = null,
        startingUnits: Int? = null,
        aiDifficulty: Int? = null,
        autoTeamMode: Boolean = false
    )

    companion object
}

fun Player.teamAlias() = when {
    team == -3 -> "S"
    team <= 10 -> Char('A'.code + team).toString()
    else -> (team + 1).toString()
}