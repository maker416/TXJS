/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.event.EventPriority
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.GameLoadedEvent
import io.github.rwpp.event.events.PlayerJoinEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.i18n.GameI18nResolver
import io.github.rwpp.logger
import io.github.rwpp.net.InternalPacketType
import io.github.rwpp.net.Net
import io.github.rwpp.net.ServerStatus
import io.github.rwpp.net.packets.ServerPacket
import io.github.rwpp.net.registerPacketListener
import java.io.File

object Logic : Initialization {
    private var playerCount = 0

    override fun init() {
        registerListeners()
        ModSyncController.init()

        GlobalEventChannel.filter(PlayerJoinEvent::class).subscribeAlways(priority = EventPriority.MONITOR) { e ->
            logger.info("New player: ${e.player.name}")
            synchronized(Logic) {
                val game = appKoin.get<Game>()
                val room = game.gameRoom
                room.teamMode?.onPlayerJoin(room, e.player)
            }
        }

        GlobalEventChannel.filter(GameLoadedEvent::class).subscribeAlways {
            val game = appKoin.get<Game>()
            val settings = appKoin.get<Settings>()

            // 引擎就绪后把 language 解析结果写入 SettingsEngine.forceEnglish，避免启动器/游戏双轨不同步
            runCatching { appKoin.get<GameI18nResolver>().syncGameLanguage() }

            when (settings.effectLimitForAllEffects) {
                "Zero" -> game.setEffectLimitForAllEffects(0)
                "Unlimited" -> game.setEffectLimitForAllEffects(Int.MAX_VALUE)
                else -> {}
            }
        }
    }

    fun getNextPlayerId(): Int {
        synchronized(Logic) {
            return ++playerCount
        }
    }

    /**
     * 开房前禁用所有已启用的网络缓存模组（`*.network.rwmod`），并重建单位表。
     *
     * 使用 [ModManager.modReload] 的 forceImmediate，避免开房 Loading 阶段主循环尚未启动时
     * [ModManager.modSaveChange] 投递到游戏线程后永久等待。
     *
     * @return 是否实际禁用了至少一个网络模组。
     */
    suspend fun disableNetworkModsBeforeHosting(): Boolean {
        val manager = appKoin.get<ModManager>()
        val mods = manager.getAllMods()
        val networkEnabled = mods.filter { it.isNetworkMod && it.isEnabled }
        if (networkEnabled.isEmpty()) return false

        logger.info(
            "[MODSYNC-HOST] disabling network cache mods before hosting: ${networkEnabled.map { it.name }}"
        )
        val enabledByFileName = mods.associate { mod ->
            File(mod.path).name.lowercase() to (mod.isEnabled && !mod.isNetworkMod)
        }
        networkEnabled.forEach { it.isEnabled = false }
        manager.modReload(forceImmediate = true, enabledByFileName = enabledByFileName)
        return true
    }

    /**
     * 开原版房间前禁用全部已启用模组并重建单位表，避免引擎仍加载着模组却以「未启用模组」开房。
     *
     * @return 是否实际禁用了至少一个模组。
     */
    suspend fun disableAllModsBeforeHosting(): Boolean {
        val manager = appKoin.get<ModManager>()
        val mods = manager.getAllMods()
        val enabled = mods.filter { it.isEnabled }
        if (enabled.isEmpty()) return false

        logger.info("[HOST] disabling all mods before vanilla host: ${enabled.map { it.name }}")
        val enabledByFileName = mods.associate { mod ->
            File(mod.path).name.lowercase() to false
        }
        enabled.forEach { it.isEnabled = false }
        manager.modReload(forceImmediate = true, enabledByFileName = enabledByFileName)
        return true
    }

    fun registerListeners() {
        val game = appKoin.get<Game>()
        val net = appKoin.get<Net>()

        net.registerPacketListener<ServerPacket.ServerInfoGetPacket>(
            InternalPacketType.PRE_GET_SERVER_INFO_FROM_LIST.type
        ) { client, _ ->
            val room = game.gameRoom

            if (room.isHost) return@registerPacketListener true

            client?.sendPacketToClient(
                ServerPacket.ServerInfoReceivePacket(
                    room.localPlayer.name + "'s game",
                    room.getPlayers().size,
                    room.maxPlayerCount,
                    room.selectedMap.mapName,
                    "",
                    "v1.15 - RWPP Client",
                    room.mods.joinToString(", "),
                    if (room.isStartGame) ServerStatus.InGame else ServerStatus.BattleRoom
                )
            )

            true
        }
    }
}
