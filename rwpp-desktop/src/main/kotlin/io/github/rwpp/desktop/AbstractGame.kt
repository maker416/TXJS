/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.corrodinggames.rts.gameFramework.l
import com.corrodinggames.rts.gameFramework.s
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.HostGameEvent
import io.github.rwpp.event.events.MapChangedEvent
import io.github.rwpp.event.events.RefreshUIEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.base.Difficulty
import io.github.rwpp.game.map.MissionType
import io.github.rwpp.game.units.UnitType
import io.github.rwpp.core.LoadingContext
import io.github.rwpp.event.events.HostSinglePlayerGameEvent
import io.github.rwpp.event.events.PlayerJoinEvent
import io.github.rwpp.net.CallbackConnection
import java.io.IOException

abstract class AbstractGame : Game {

    override fun hostNewSinglePlayer(sandbox: Boolean) {
        if (!io.github.rwpp.core.MatchStartController.ensureCanStart()) return
        post { initializeSinglePlayer(sandbox) }
    }

    /** 只能在桌面游戏线程调用，初始化完成后才允许选择存档。 */
    protected fun initializeSinglePlayer(sandbox: Boolean) {
        io.github.rwpp.game.GameStartSafety.requireCanReload()
        com.corrodinggames.rts.game.n.F()

//            val root = ScriptEngine.getInstance().root
//            val libRocket = ScriptContext::class.java.getDeclaredField("libRocket").run {
//                isAccessible = true
//                get(root)
//            } as com.corrodinggames.librocket.b
//            val guiEngine = ScriptContext::class.java.getDeclaredField("guiEngine").run {
//                isAccessible = true
//                get(root)
//            } as a

        val game = GameEngine.B()
        game.bX.b("starting singleplayer")
        game.bQ.aiDifficulty = Difficulty.Hard.ordinal - 2 // fuck code

//            guiEngine.b(true)
//            guiEngine.c(false)

        val B: l = game
        B.bS.g()
        B.L()
        synchronized(B) {
            B.dm = null
            B.dl = "maps/skirmish/[z;p10]Crossing Large (10p).tmx"
        }

        B.a(true, s.b)

        initMap(true)

        if (sandbox) {
            game.bL.E = false
            game.bS.y()
            game.bv = true
        } else {
            game.bv = false
        }

        game.bX.y = "You"
        game.bX.o = true
        val S: Boolean = if (sandbox) game.bX.R() else game.bX.S()

        if (S) {
            val e = game.bX.e()
            if (e != null) {
                e.f = game.bQ.aiDifficulty
                game.bX.a(e)
            }

            singlePlayer = true
        }

        RefreshUIEvent().broadcastIn(delay = 200L)
        HostSinglePlayerGameEvent().broadcastIn()
    }

    override fun hostStartWithPasswordAndMods(
        isPublic: Boolean,
        password: String?,
        useMods: Boolean,
    ) {
        if (!io.github.rwpp.core.MatchStartController.ensureCanStart()) return
        val B = GameEngine.B()
        B.bX.b("starting new")
        B.bX.n = password
        B.bX.q = isPublic
        B.bX.o = useMods

        initMap(true)
        if(B.bX.b(false)) {
            MapChangedEvent(gameRoom.selectedMap.displayName()).broadcastIn()
            HostGameEvent().broadcastIn()
            PlayerJoinEvent(gameRoom.localPlayer).broadcastIn()
            RefreshUIEvent().broadcastIn(delay = 200L)
        }
    }

    override fun setUserName(name: String) {
        l.B().bX.a(name)
    }

    override suspend fun directJoinServer(address: String, uuid: String?, context: LoadingContext): Result<String> {
        initMap()

        context.message("Connecting...")

        val trim = restrictedString(address.trim())
        val relayUuid = io.github.rwpp.net.sanitizeJoinRelayUuid(uuid)
        if(relayUuid == null) l.B().bQ.lastNetworkIP = trim
        l.B().bX.bw = relayUuid
        val attempt = CallbackConnection<com.corrodinggames.rts.gameFramework.j.an> { connector ->
            connector.a()
            runCatching { connector.g?.close() }
        }
        joinAttempt = attempt
        try {
            return attempt.connect(
                start = { complete -> l.B().bX.a(trim, true) { complete() } },
            ) { connector ->
                if (connector.e != null) {
                    rcnOption = null
                    Result.failure(IOException(connector.e))
                } else {
                    try {
                        l.B().bX.b("starting new")
                        l.B().bX.a(connector.g)
                        PlayerJoinEvent(gameRoom.localPlayer).broadcastIn()
                        RefreshUIEvent().broadcastIn(delay = 200L)
                        Result.success("")
                    } catch (error: IOException) {
                        runCatching { connector.g?.close() }
                        Result.failure(error)
                    }
                }
            }
        } finally {
            if (joinAttempt === attempt) joinAttempt = null
        }
    }

    override fun cancelJoinServer() {
        joinAttempt?.cancel()
        rcnOption = null
    }

    override fun onQuestionCallback(option: String) {
        rcnOption = option
    }

    override fun setTeamUnitCapHostGame(cap: Int) {
        l.B().bX.ax = cap
        l.B().bX.aw = cap
        l.B().bC = cap
    }

    override fun getAllMissionTypes(): List<MissionType> {
        return listOf(MissionType.Normal, MissionType.Challenge, MissionType.Survival)
    }

    override fun getStartingUnitOptions(): List<Pair<Int, String>> {
        val B: l = GameEngine.B()
        val list = mutableListOf<Pair<Int, String>>()
        val it: Iterator<*> = B.bX.i().iterator()
        while(it.hasNext()) {
            val num = it.next() as Int
            list.add(num to B.bX.d(num))
        }
        return list
    }
    @Suppress("UNCHECKED_CAST")
    override fun getAllUnitTypes(): List<UnitType> {
        return (com.corrodinggames.rts.game.units.ar.ae as ArrayList<UnitType>)
    }

    /** 原版初始化时保存的核心单位校验和；实时 bM() 会受菜单地图水面影响。 */
    override fun getUnitsChecksum(): Int = GameEngine.B().z()

    override fun refreshHandshakeChecksumCache() {
        // 保留共享同步流程的兼容入口。包 110 必须沿用 init 缓存，重载模组不能覆写它。
    }

    override suspend fun refreshMenuAfterDisconnect() {
        // 桌面退房不走 Android activityResume/`i.q()`，无对等的 UI 线程单位表遍历。
    }

    override fun onBanUnits(units: List<UnitType>) {
        bannedUnitList = units.map(UnitType::name)
        if(units.isNotEmpty())
            gameRoom.sendSystemMessage("Host has banned these units (房间已经ban以下单位): ${units.map(UnitType::displayName).joinToString(", ")}")
    }

    private fun clearObjectOnScreen() {
        //luke 忘记清理replay留下的object，逆天
        com.corrodinggames.rts.gameFramework.w.er.clear()
        GameEngine.B().g()
    }

    private fun restrictedString(str: String?): String? {
        return str?.replace("'", ".")?.replace("\"", ".")?.replace("(", ".")?.replace(")", ".")?.replace(",", ".")
            ?.replace("<", ".")?.replace(">", ".")
    }

    private fun escapedString(str: String): String {
        return "'" + str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;")
            .replace("\"", "&quot;").replace("\${", "$ {") + "'"
    }

    @Volatile private var joinAttempt: CallbackConnection<com.corrodinggames.rts.gameFramework.j.an>? = null
}
