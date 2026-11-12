/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game

import io.github.rwpp.core.LoadingContext
import io.github.rwpp.game.base.Difficulty
import io.github.rwpp.game.map.*
import io.github.rwpp.game.mod.ModPlaytimeState
import io.github.rwpp.game.ui.GUI
import io.github.rwpp.game.units.UnitType
import io.github.rwpp.game.world.World
import org.koin.core.component.KoinComponent
import java.io.Closeable

interface Game : KoinComponent {
    /**
     * Current game room.
     */
    val gameRoom: GameRoom

    val gui: GUI

    val world: World

    /**
     * Post an action to be executed inside the game main loop (on the loop's own thread).
     *
     * 桌面端排进 `RWPPContainer` 的 channel；Android 排进 `mainThreadChannel`
     *（由主循环 `i.b(float)` 的 InsertBefore 注入逐帧取出执行）。
     *
     * 注意（踩过坑）：这**不是**通用的「引擎写操作专用通道」。Android 原版不少引擎入口
     *（如 `LevelSelectActivity.loadSinglePlayerMapRaw`、`ae.r()/s()`）隐含 UI 线程依赖
     *（会触碰 Android View/Activity 路径），必须照原版约定在 UI 线程调用；
     * 曾因此把 `hostNewSinglePlayer`/`startNewMissionGame` 经本通道迁移导致
     * 沙盒闪退、任务启动动作积压丢失（commit bfc6cb2，已回滚）。
     * 任何引擎调用迁入游戏线程前，必须逐点核实其线程依赖并真机验证。
     */
    fun post(action: () -> Unit)

    /**
     * 多人连接操作开始前申请平台保活，预同步也应包含在内；必须在 UI 线程申请。
     * 完成/取消时在 finally 关闭，连接成功后由平台接手房间保活；允许嵌套申请。
     */
    fun acquireMultiplayerKeepAlive(): Closeable = Closeable {}

    /**
     * Start a mission game with specific difficulty.
     */
    fun startNewMissionGame(difficulty: Difficulty, mission: Mission)

    /**
     * Init rw engine.
     * @param context describes how the loading information show.
     */
    suspend fun load(context: LoadingContext)

    /**
     * Host a new multiplayer game room.
     * @param isPublic whether the room will be added to the room list.
     * @param password the password of the room.
     * @param useMods whether the mod will be used or not in the room.
     */
    fun hostStartWithPasswordAndMods(
        isPublic: Boolean,
        password: String?,
        useMods: Boolean,
    )

    /**
     * Host a new single player game.
     */
    fun hostNewSinglePlayer(sandbox: Boolean)

    /**
     * 初始化单人房间后选择存档并开局。
     *
     * Android 保留调用方的 UI 线程；异步初始化的平台必须重写并等待初始化完成，
     * 不能在 [hostNewSinglePlayer] 仅完成排队后就选择存档。
     */
    suspend fun loadSavedGame(map: GameMap) {
        require(map.mapType == MapType.SavedGame)
        hostNewSinglePlayer(false)
        gameRoom.selectedMap = map
        gameRoom.startGame()
    }

    /**
     * Set the displayName of the local player.
     */
    fun setUserName(name: String)

    /**
     * Direct join a server by specific address.
     * @param uuid room uuid.
     * @param context describes the loading context.
     */
    suspend fun directJoinServer(
        address: String,
        uuid: String?,
        context: LoadingContext
    ): Result<String>

    /**
     * Interrupt directly joining Server
     */
    fun cancelJoinServer()

    /**
     * Set the option, and it will be used automatically to reply the next question.
     */
    fun onQuestionCallback(option: String)

    /**
     * Set the team unit capacity.
     */
    fun setTeamUnitCapHostGame(cap: Int)

    /**
     * Get all mission Types.
     */
    fun getAllMissionTypes(): List<MissionType>

    /**
     * Get all missions.
     */
    fun getAllMissions(): List<Mission>

    /**
     * Get all missions by given type.
     */
    fun getMissionsByType(type: MissionType): List<Mission>

    /**
     * Get all maps.
     */
    fun getAllMaps(flush: Boolean = false): List<GameMap>

    /**
     * Get all maps by given map type.
     * @param mapType the map type.
     */
    fun getAllMapsByMapType(mapType: MapType): List<GameMap>

    /**
     * Get starting unit option list.
     *
     * It will return like (index -> option displayName).
     *
     * You can use the index to set [GameRoom.startingUnits].
     */
    fun getStartingUnitOptions(): List<Pair<Int, String>>

    /**
     * Get all the unit list.
     */
    fun getAllUnitTypes(): List<UnitType>

    /**
     * 只读采样当前对局与本局生效的模组。由 UI 线程定期调用；不得在这里启动、暂停或重载引擎。
     * 菜单、战役室、回放与观战不构成可统计对局；暂停中的对局保留 hasMatch，但 active 为 false。
     */
    fun getModPlaytimeState(): ModPlaytimeState = ModPlaytimeState()

    /** 重载前后使统计来源失效；不读取或修改引擎。 */
    fun invalidateModPlaytimeSnapshot() {}

    /**
     * 返回平台同步流程使用的核心单位校验值，不代表自定义模组定义的一致性。
     * 两端必须读取原版初始化缓存（桌面 `l.z()`、Android `k.r()`）：
     * 实时 `am.bM()` / `ce.bt()` 只遍历内置单位，
     * 且建造者、运输艇的参数会读取当前菜单地图的水陆状态，不能作为稳定的入房校验。
     * 自定义模组内容仍须校验文件、实际启用集合及原版自定义单位定义。
     */
    fun getUnitsChecksum(): Int

    /**
     * 平台模组应用完成后的握手准备钩子。
     * 两端保留原版初始化缓存，不得用带菜单地图的实时 `am.bM()` / `ce.bt()` 覆写它。
     */
    fun refreshHandshakeChecksumCache()

    /**
     * 应用阶段取消回落后重建菜单（Android `activityResume`/`i.q()`）。
     * 必须在 [io.github.rwpp.game.mod.KeepConnectedReload] 仍置位时调用，
     * 主循环只泵网络，才不会和单位表迭代撞车。桌面端无需对等操作。
     */
    suspend fun refreshMenuAfterDisconnect()

    /**
     * Ban given unit, and all actions about the unit will not executed.
     */
    fun onBanUnits(units: List<UnitType>)

    /**
     * Get all replays.
     */
    fun getAllReplays(): List<Replay>

    /**
     * Watch the given replay.
     */
    fun watchReplay(replay: Replay)

    /**
     * Set effect limit for all effects (low, high, etc.).
     */
    fun setEffectLimitForAllEffects(limit: Int)

    /**
     * Describe if the game could continue.
     */
    fun isGameCouldContinue(): Boolean

    /**
     * Continue the game, make sure [isGameCouldContinue] return true.
     */
    fun continueGame()
}
