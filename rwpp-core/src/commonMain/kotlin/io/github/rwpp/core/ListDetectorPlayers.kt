/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.Player
import java.util.IdentityHashMap

/** 列表探测器仍占真实玩家槽位，仅在本地隐藏其聊天和进出提示。 */
object ListDetectorPlayers {
    private const val NAME = "列表探测器"
    private val systemMessageSubject = Regex(
        """^(?:player\s+)?(?:['"]列表探测器['"]|列表探测器)(?:\s|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun isDetector(name: String): Boolean = name.trim() == NAME

    fun hideChatMessage(sender: String, message: String): Boolean =
        isDetector(sender) ||
            (sender.isBlank() && systemMessageSubject.containsMatchIn(message.trimStart()))

    fun canKick(room: GameRoom, player: Player): Boolean =
        (room.isHost || room.isHostServer) && !room.isSinglePlayerGame && !room.isStartGame &&
            player !== room.localPlayer && !player.isAI && !player.isRoomHost && isDetector(player.name)

    /** 弹窗可能在离房/换人后才被点击，执行前必须重新核对当前槽位对象和权限。 */
    fun kickIfPresent(room: GameRoom, player: Player): Boolean {
        if (!canKick(room, player) || room.getPlayers().none { it === player }) return false
        room.kickPlayer(player)
        return true
    }
}

/** 每个等待室独享计时；不使用昵称/槽位/id 作 key，避免新连接继承旧连接的超时。 */
class ListDetectorGuard {
    private class Visit(val firstSeenMs: Long, var nextKickMs: Long = firstSeenMs)
    private val visits = IdentityHashMap<Player, Visit>()

    fun poll(room: GameRoom, players: List<Player>, nowMs: Long) {
        val detectors = players.filter { ListDetectorPlayers.canKick(room, it) }
        visits.keys.removeAll { previous -> detectors.none { it === previous } }
        detectors.forEach { player ->
            val visit = visits.getOrPut(player) { Visit(nowMs) }
            if (nowMs - visit.firstSeenMs > 30_000L && nowMs >= visit.nextKickMs) {
                // 中继踢人异步生效；最多每五秒重试一次，避免每次刷新反复发送踢人命令。
                visit.nextKickMs = nowMs + 5_000L
                runCatching { ListDetectorPlayers.kickIfPresent(room, player) }
            }
        }
    }
}
