/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import androidx.compose.runtime.Immutable
import io.github.rwpp.game.Game
import io.github.rwpp.game.Player
import io.github.rwpp.game.map.GameMap
import io.github.rwpp.game.map.MapType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 等待房间核心状态的不可变快照（单一采样点）。
 *
 * 历史上房间 UI 在组合中通过 `remember(update)` 直接读引擎字段，刷新靠
 * `update = !update` 翻转触发全量重读——读路径分散、每次重组都触碰引擎。
 * 现在改为：事件（RefreshUIEvent 等）驱动 [RoomSnapshotStore.resample] 在
 * Main 上采样一次，UI 只采集 [RoomSnapshotStore.snapshot] 的不可变数据。
 *
 * 玩家列表元素仍是 [Player] 接口引用（供点击/踢人/换队等动作使用），
 * 但列表本身（增删、去重、按队伍排序）是快照时刻的稳定结果。
 * 锁房状态、开局单位选项、实时 ping 等可写/高频字段暂未纳入，
 * 仍由原 `remember(update)` 机制驱动，后续逐步迁移。
 */
@Immutable
data class RoomSnapshot(
    val players: List<Player> = emptyList(),
    val selectedMap: GameMap? = null,
    val displayMapName: String = "",
    val mapType: MapType = MapType.SkirmishMap,
    /** `isHost || isHostServer`（本地服务器掌管者）。 */
    val isHost: Boolean = false,
)

object RoomSnapshotStore {
    private val _snapshot = MutableStateFlow(RoomSnapshot())
    val snapshot: StateFlow<RoomSnapshot> = _snapshot.asStateFlow()

    /**
     * 从引擎房间采样一次。必须在 Main 上调用（与原先组合内直读同线程）；
     * 引擎字段缺失/异常时降级为默认值，不把异常抛进事件总线。
     */
    fun resample(game: Game) {
        val room = game.gameRoom
        _snapshot.value = RoomSnapshot(
            // 引擎玩家数组里 connectHexId 不保证唯一（缺省值/握手占位/正式槽位可能撞车），
            // 这里与 UI 列表同规则：按引用去重、按队伍排序，保证 LazyColumn key 稳定
            players = runCatching { room.getPlayers() }.getOrDefault(emptyList())
                .distinctBy { System.identityHashCode(it) }
                .sortedBy { it.team },
            selectedMap = runCatching { room.selectedMap }.getOrNull(),
            displayMapName = runCatching { room.displayMapName }.getOrDefault(""),
            mapType = runCatching { room.mapType }.getOrDefault(MapType.SkirmishMap),
            isHost = runCatching { room.isHost || room.isHostServer }.getOrDefault(false),
        )
    }
}
