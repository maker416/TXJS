/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import com.corrodinggames.rts.appFramework.LevelGroupSelectActivity
import com.corrodinggames.rts.appFramework.LevelSelectActivity
import com.corrodinggames.rts.appFramework.LoadLevelActivity
import com.corrodinggames.rts.gameFramework.j.ae
import com.corrodinggames.rts.gameFramework.k
import io.github.rwpp.android.*
import io.github.rwpp.android.MainActivity.Companion.gameView
import io.github.rwpp.appKoin
import io.github.rwpp.core.LoadingContext
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.*
import io.github.rwpp.game.BlockingJoinController
import io.github.rwpp.game.Game
import io.github.rwpp.game.GameRoom
import io.github.rwpp.game.base.Difficulty
import io.github.rwpp.game.map.*
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.ModPlaytimeState
import io.github.rwpp.game.ui.GUI
import io.github.rwpp.game.units.UnitType
import io.github.rwpp.game.world.World
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.logger
import io.github.rwpp.net.sanitizeJoinRelayUuid
import io.github.rwpp.ui.UI
import io.github.rwpp.utils.Reflect
import kotlinx.coroutines.*
import org.koin.core.annotation.Single
import org.koin.core.component.get
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Single
class GameImpl : Game, CoroutineScope {

    private val joinController = BlockingJoinController()
    private var _missions: List<Mission>? = null
    private var _allMaps: List<GameMap>? = null
    private var _maps = mutableMapOf<MapType, List<GameMap>>()
    private var lastPlaytimeFrame: Int? = null
    private var lastPlaytimeGameTime: Int? = null
    private var lastPlaytimeMap: Any? = null
    private var playtimeGeneration = 0L
    private var lastPlaytimeState = ModPlaytimeState()

    override fun getModPlaytimeState(): ModPlaytimeState = runCatching {
        val engine = GameEngine.t()
        val lifecycle = CustomInGameActivity.instance?.lifecycle?.currentState
        val player = engine.bp ?: engine.bU.A
        // 原版 bD 是关卡已加载，bE 是菜单演示，bF 是加载中；任务/存档不依赖 isGaming 标志。
        val hasMatch = lifecycle?.isAtLeast(Lifecycle.State.STARTED) == true &&
            engine.bD && !engine.bE && !engine.bF && !engine.bY.g() &&
            player != null && player.s != -3
        if (!hasMatch) {
            lastPlaytimeFrame = null
            lastPlaytimeGameTime = null
            lastPlaytimeMap = null
            return@runCatching ModPlaytimeState()
        }
        val frame = engine.bu
        val gameTime = engine.bv
        val previousFrame = lastPlaytimeFrame
        val newMatch = previousFrame == null || frame < previousFrame || engine.bI !== lastPlaytimeMap
        if (newMatch) playtimeGeneration++
        val advancing = !newMatch && frame > previousFrame!! &&
            lastPlaytimeGameTime?.let { gameTime != it } == true
        lastPlaytimeFrame = frame
        lastPlaytimeGameTime = gameTime
        lastPlaytimeMap = engine.bI
        // 私有 i.b(false) 的网络暂停检查会写标志。仅采样暂停事实与帧推进，不迁移任何引擎入口。
        val active = advancing && lifecycle == Lifecycle.State.RESUMED &&
            !engine.bP.u && !engine.bm &&
            !engine.bU.al && !engine.bU.am &&
            !gameOver && !player.I && !player.J && !KeepConnectedReload.active
        val modIds = com.corrodinggames.rts.game.units.custom.l.d.toArray()
            .filterIsInstance<com.corrodinggames.rts.game.units.custom.l>()
            .mapNotNull { it.J?.a }.toMutableSet()
        engine.di?.takeIf { "MOD|" in it }?.let { engine.bW.f(it)?.a }?.let(modIds::add)
        // i() 是已注册、启用且无加载错误的模组，O 是 refreshData 成功扫描的音乐轨道表。
        engine.bW.i().toArray().filterIsInstance<com.corrodinggames.rts.gameFramework.i.b>()
            .filter { it.O.isNotEmpty() }.forEach { modIds.add(it.a) }
        val mods = appKoin.get<ModManager>().getAllMods().filter { it.id in modIds }
        ModPlaytimeState(hasMatch = true, active = active, mods = mods, generation = playtimeGeneration)
    }.getOrElse {
        lastPlaytimeState.copy(active = false, reliable = false)
    }.also { lastPlaytimeState = it }

    override val gameRoom: GameRoom = GameRoomImpl(this)
    override val gui: GUI by lazy {
        GameEngine.t().bP as GUI
    }
    override val world: World = WorldImpl()

    override fun post(action: () -> Unit) {
        mainThreadChannel.trySend(action)
    }

    override fun startNewMissionGame(difficulty: Difficulty, mission: Mission) {
        val t = GameEngine.t()
        GameEngine.t().bU.b("starting singleplayer")
        t.bN.aiDifficulty = difficulty.ordinal - 2
        t.bN.save()
        LevelSelectActivity.loadSinglePlayerMapRaw("maps/${mission.type.pathName()}/${mission.mapName}.tmx", false, 0, 0, true, false)
        val intent = Intent(get(), CustomInGameActivity::class.java)
        intent.putExtra("level", t.di)
        gameLauncher.launch(intent)
    }

    override suspend fun load(context: LoadingContext) {
        initMap()
    }

    override fun hostStartWithPasswordAndMods(isPublic: Boolean, password: String?, useMods: Boolean) {
        val t: k = GameEngine.t()
        GameEngine.t().bU.b("starting new")
        t.bU.n = password
        t.bU.o = useMods
        t.bU.q = isPublic
        launch(Dispatchers.IO) {
            initMap(true)
            t.bU.t()
            MapChangedEvent(gameRoom.selectedMap.displayName()).broadcastIn()
            delay(100)
            RefreshUIEvent().broadcastIn()
            HostGameEvent().broadcastIn()
            PlayerJoinEvent(gameRoom.localPlayer).broadcastIn()
        }
    }

    override fun hostNewSinglePlayer(sandbox: Boolean) {
        val t = GameEngine.t()
        GameEngine.t().bU.b("starting singleplayer")
        LevelSelectActivity.loadSinglePlayerMapRaw("skirmish/[z;p10]Crossing Large (10p).tmx", true, 3, 1, true, true)
        t.bU.b("starting singleplayer")
        t.bU.y = "You"
        t.bU.o = true
        if (sandbox) t.bU.r() else t.bU.s()
        isSinglePlayerGame = true
        initMap(true)
        RefreshUIEvent().broadcastIn(delay = 200L)
        HostSinglePlayerGameEvent().broadcastIn()
    }

    override fun setUserName(name: String) {
        GameEngine.t().bU.a(name)
    }

    override suspend fun directJoinServer(address: String, uuid: String?, context: LoadingContext): Result<String> {
        val result = try {
            Result.success(joinController.connect(
                prepare = {
                    GameEngine.t().a(appKoin.get(), gameView)
                    isSinglePlayerGame = false
                    initMap()
                    GameEngine.t().bU.by = sanitizeJoinRelayUuid(uuid)
                },
                blockingConnect = { GameEngine.t().bU.c(address, false) },
                cleanupCancelled = {
                    withContext(Dispatchers.Main.immediate) { gameRoom.disconnectAndWait("Join cancelled") }
                },
            ))
        } catch (e: CancellationException) {
            // 清理已在 joinController 内完整等待，才能释放 beginSession 的开始屏障。
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        return when {
            result.isSuccess && result.getOrNull() == null -> {
                if (GameEngine.t().G()) {
                    logger.warn("Join reported success but engine is still in local skirmish mode: $address")
                    GameEngine.t().bU.b("Connection failed")
                    Result.failure(IOException("Connection failed."))
                } else {
                    PlayerJoinEvent(gameRoom.localPlayer).broadcastIn()
                    Result.success("")
                }
            }
            ae.u() -> {
                Result.failure(IOException("Connection failed: Target server may not be open to the internet."))
            }
            else -> {
                Result.failure(IOException(result.getOrNull() ?: "Connection failed.", result.exceptionOrNull()))
            }
        }
    }

    override fun cancelJoinServer() {
        joinController.cancel()
    }
    override fun onQuestionCallback(option: String) {
        questionOption = option
    }

    override fun setTeamUnitCapHostGame(cap: Int) {
        GameEngine.t().bz = cap
        GameEngine.t().bU.az = cap
        GameEngine.t().bU.ay = cap
    }

    override fun getAllMissionTypes(): List<MissionType> {
        return listOf(MissionType.Normal, MissionType.Challenge, MissionType.Survival)
    }

    override fun getAllMissions(): List<Mission> {
        if(_missions != null) return _missions!!
        val assets = get<Context>().assets

        val missions = mutableListOf<Mission>()
        getAllMissionTypes().forEach { type ->
            assets.list("maps/${type.pathName()}")!!
                .filter { it.endsWith(".tmx") }
                .forEachIndexed { i, f ->
                    missions.add(object : Mission {
                        override val id: Int = i
                        override val name: String =
                            if(type == MissionType.Normal)
                                f.split("__-__")[1].removeSuffix(".tmx")
                            else f.removeSuffix(".tmx")
                        override val type: MissionType
                            get() = type
                        override val mapName: String
                            get() = f.removeSuffix(".tmx")
                        override val mapType: MapType
                            get() = MapType.SkirmishMap

                        private val _displayName = LevelSelectActivity.convertLevelFileNameForDisplay(name)
                        override fun displayName(): String = _displayName

                        override fun openImageInputStream(): InputStream? {
                            return assets.open("maps/${type.pathName()}/${f.removeSuffix(".tmx") + "_map.png"}")
                        }

                        override fun openInputStream(): InputStream {
                            throw RuntimeException("Not implemented")
                        }
                    })
                }
        }


        return missions.toList().also { _missions = it }
    }

    override fun getAllMaps(flush: Boolean): List<GameMap> {
        val assets = get<Context>().assets
        if(_maps.isEmpty() || flush) {
            val t = GameEngine.t()
            val levelDirs = com.corrodinggames.rts.gameFramework.e.a.a(LevelGroupSelectActivity.customLevelsDir, true)
            val mapPaths = mapOf<MapType, Array<String>?>(
                MapType.CustomMap to browserCustomMapPaths(t.bW.a(levelDirs, LevelGroupSelectActivity.customLevelsDir)),
                MapType.SavedGame to LoadLevelActivity.getGameSaves()
            )


            val assetMaps = mutableListOf<GameMap>()
            assets.list("maps/skirmish")!!
                .filter { it.endsWith(".tmx") }
                .forEachIndexed { i, f ->
                    assetMaps.add(object : GameMap {
                        override val id: Int = i
                        override val mapName: String
                            get() = f.removeSuffix(".tmx")
                        override val mapType: MapType
                            get() = MapType.SkirmishMap

                        override fun openImageInputStream(): InputStream? {
                            return assets.open("maps/skirmish/${f.removeSuffix(".tmx") + "_map.png"}")
                        }

                        override fun openInputStream(): InputStream {
                            return com.corrodinggames.rts.game.b.b.a("maps/skirmish/$f")
                        }
                    })
                }
            _maps[MapType.SkirmishMap]= assetMaps

            for((type, path) in mapPaths) {
                val maps = mutableListOf<GameMap>()
                path?.forEachIndexed { i, name ->
                        maps.add(object : GameMap {
                            override val id: Int = i
                            override val mapName: String
                                get() = name.removeSuffix(".tmx").removeSuffix(".rwsave")
                            override val mapType: MapType
                                get() = type

                            private val _displayName = LevelSelectActivity.convertLevelFileNameForDisplay(mapName)

                            override fun openImageInputStream(): InputStream? {
                                return if(type == MapType.CustomMap) {
                                    com.corrodinggames.rts.appFramework.d.c(
                                        "/SD/rusted_warfare_maps/$name"
                                    )
                                } else null
                            }

                            override fun openInputStream(): InputStream {
                                return com.corrodinggames.rts.game.b.b.a("/SD/rusted_warfare_maps/$name")
                            }

                            override fun displayName(): String = _displayName
                        })
                    }
                _maps[type] = maps.toList()
            }
        }

        return if (!flush && _allMaps != null)
            _allMaps!!
        else buildList { _maps.values.forEach(::addAll) }.also { _allMaps = it }
    }

    override fun getAllMapsByMapType(mapType: MapType): List<GameMap> {
        if(_maps.isEmpty()) getAllMaps()
        return _maps[mapType]!!
    }

    override fun getMissionsByType(type: MissionType): List<Mission> = getAllMissions().filter { it.type == type }
    override fun getStartingUnitOptions(): List<Pair<Int, String>> {
        val list = mutableListOf<Pair<Int, String>>()
        val it: Iterator<*> = ae.d().iterator()
        while(it.hasNext()) {
            val num = it.next() as Int
            list.add(num to ae.c(num))
        }
        return list
    }

    @Suppress("UNCHECKED_CAST")
    override fun getAllUnitTypes(): List<UnitType> {
        return com.corrodinggames.rts.game.units.cj.ae as ArrayList<UnitType>
    }

    /**
     * 实时重算的全部启用单位校验和（`ce.bt()`）。
     * 不用 `k.r()`：那是引擎 init 时缓存的字段，模组重载后不会刷新，同步校验需要真实状态。
     */
    override fun getUnitsChecksum(): Int = com.corrodinggames.rts.game.units.ce.bt()

    override fun refreshHandshakeChecksumCache() {
        val checksum = com.corrodinggames.rts.game.units.ce.bt()
        try {
            // i.r() 读的 init 缓存字段 `c`；重载后必须写回，否则包 110 仍发旧值。
            Reflect.set(GameEngine.t(), "c", checksum)
            logger.info("[MODSYNC] handshake checksum cache refreshed to $checksum")
        } catch (e: Throwable) {
            logger.warn("[MODSYNC] failed to write handshake checksum cache: ${e.message}")
        }
    }

    override suspend fun refreshMenuAfterDisconnect() {
        suspendCancellableCoroutine { cont ->
            val posted = uiHandler.post {
                try {
                    MainActivity.runActivityResume(forceWhileReloading = true)
                    if (cont.isActive) cont.resume(Unit)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
            if (!posted && cont.isActive) {
                logger.warn("[MODSYNC] refreshMenuAfterDisconnect: uiHandler.post failed")
                cont.resume(Unit)
            }
        }
    }

    override fun onBanUnits(units: List<UnitType>) {
        bannedUnitList = units.map { it.name }
        if(units.isNotEmpty()) gameRoom.sendSystemMessage("Host has banned these units (房间已经ban以下单位): ${
            units.joinToString(
                ", "
            ) { it.displayName }
        }")
    }

    override fun getAllReplays(): List<Replay> {
        return runCatching {
            scanReplayFiles().mapIndexed { i, file -> file.toReplay(i) }
        }.onFailure { e ->
            logger.warn("Failed to list replays", e)
        }.getOrDefault(emptyList())
    }

    override fun watchReplay(replay: Replay) {
        val loaded = runCatching {
            GameEngine.t().bY.b(replay.name)
        }.onFailure { e ->
            logger.error("Failed to load replay {}", replay.name, e)
        }.getOrDefault(false)
        if (loaded) {
            gameLauncher.launch(
                Intent(get(), CustomInGameActivity::class.java)
            )
        } else {
            UI.showWarning(readI18n("replays.loadFailed"))
        }
    }

    override fun setEffectLimitForAllEffects(limit: Int) {
        val effectEngine = GameEngine.t().bO
        effectEngine.apply {
            b = limit
            c = limit
            d = limit
            e = limit
        }
    }

    override fun isGameCouldContinue(): Boolean {
        val c = GameEngine.t()
        return !(c == null || !c.bD || c.bE)
    }

    override fun continueGame() {
        val intent = Intent(get(), CustomInGameActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        gameLauncher.launch(intent)
    }


    override val coroutineContext: CoroutineContext = Job()

}
