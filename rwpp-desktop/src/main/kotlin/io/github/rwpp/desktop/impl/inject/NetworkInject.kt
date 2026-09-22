/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl.inject

import com.corrodinggames.rts.game.n
import com.corrodinggames.rts.gameFramework.e
import com.corrodinggames.rts.gameFramework.j.*
import io.github.rwpp.*
import io.github.rwpp.core.ModSyncController
import io.github.rwpp.desktop.GameEngine
import io.github.rwpp.desktop.impl.PlayerImpl
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.ChatMessageEvent
import io.github.rwpp.event.events.SystemMessageEvent
import io.github.rwpp.game.Game
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.InterruptResult
import io.github.rwpp.inject.RedirectMethod
import io.github.rwpp.net.Client
import io.github.rwpp.net.InternalPacketType
import io.github.rwpp.net.Net
import io.github.rwpp.ui.UI
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException


@InjectClass(ad::class)
object NetworkInject {
    @Inject("a", injectMode = InjectMode.InsertBefore)
    fun onBanUnits(netPacket: e): Any {
        // 服务器侧拦截：收到被禁单位的命令时直接丢弃，不再入队与转发。
        // 发起端本机的即时生效与统一执行由 GameCommandInject 拦截。
        return if (isBannedCommand(netPacket)) InterruptResult.Unit else Unit
    }

    @Inject("c", injectMode = InjectMode.InsertBefore)
    fun onReceivePacket(auVar: au): Any {
        return when(val type = auVar.b) {
            InternalPacketType.PREREGISTER_INFO.type -> {
                with(GameEngine.B().bX) {
                    if (this.C) return@with
                    val kVar16 = k(auVar)
                    val cVar14 = auVar.a
                    kVar16.l()
                    val f11 = kVar16.f()
                    val f12 = kVar16.f()
                    kVar16.f()
                    kVar16.l()
                    this.S = kVar16.l()
                    cVar14.E = f12
                    if (f11 >= 1) {
                        this.T = kVar16.f()
                    }
                    if (f11 >= 2) {
                        this.U = kVar16.f()
                        this.V = kVar16.f()
                    }

                    h(cVar14)
                }

                InterruptResult.Unit
            }

            InternalPacketType.REGISTER_PLAYER.type -> {
                // 注册握手入口（服务器侧）：暂存玩家名/单位校验和/连接 IP，
                // 供 redirectUnitsChecksum 判定是否放行校验和不匹配、正在带外同步的加入者。
                // 不拦截原方法；解析失败静默回落原版行为（校验和不匹配照踢）。
                ModSyncController.pendingRegistration = parseRegistration(auVar)
                Unit
            }

            else -> {
                net.listeners[type]?.forEach { listener ->
                    val result = listener.invoke(
                        auVar.a as? Client,
                        net.packetDecoders[type]!!.invoke(
                            DataInputStream(
                                ByteArrayInputStream(auVar.c)
                            )
                        )
                    )
                    if (result) return InterruptResult.Unit
                }

                Unit
            }
        }
    }

    /**
     * 解析 REGISTER_CONNECTION(110) 注册包，读取顺序严格对齐引擎 ad.c(au) case 110
     * （见 build/tmp-decompile/work/ad_code.txt:6396-6473）：
     * 前缀 l() → 格式版本 f() → 协议版本 f() → 游戏版本 f() → 玩家名 l() → 密码 j()
     * → [格式≥1] UUID l() → [格式≥2] 重连 token l() → [格式≥3] 单位校验和 f()。
     */
    private fun parseRegistration(auVar: au): ModSyncController.PendingRegistration? {
        return runCatching {
            with(GameEngine.B().bX) {
                val reader = k(auVar)
                reader.l()
                val format = reader.f()
                reader.f()
                reader.f()
                val name = reader.l()
                reader.j()
                if (format >= 1) reader.l()
                if (format >= 2) reader.l()
                val checksum = if (format >= 3) reader.f() else -1
                ModSyncController.PendingRegistration(name, auVar.a.f(), checksum)
            }
        }.getOrElse {
            logger.warn("[MODSYNC] parse REGISTER_CONNECTION failed: ${it.message}")
            null
        }
    }

    /**
     * 握手单位校验和放行（模组同步 v2「进房后同步」的核心）。
     * 仅重定向 ad.c(au) 方法体内的 l.z() 调用（校验和比较与踢人日志两处）：
     * 存在已暂存的注册且回调放行时返回客户端校验和使 != 不成立（跳过踢人）；
     * 否则返回引擎真实校验和，保持原版踢人行为，对原版客户端零影响。
     */
    @RedirectMethod(
        method = "c",
        methodDesc = "(Lcom/corrodinggames/rts/gameFramework/j/au;)V",
        targetClassName = "com.corrodinggames.rts.gameFramework.l",
        targetMethod = "z",
    )
    fun ad.redirectUnitsChecksum(): Int {
        val pending = ModSyncController.pendingRegistration
        if (pending != null && ModSyncController.shouldAllowMismatch(pending.name, pending.ip)) {
            logger.info("[MODSYNC] allow checksum mismatch for syncing peer: ${pending.name}")
            return pending.clientChecksum
        }
        return GameEngine.B().z()
    }

    /**
     * 兜底：即使缺单位检查仍抛了 `bw`，也不执行 `ad.b("Missing unit:...")` 自断。
     * 进房后同步期间连接必须活着，否则聊天变成 `not networked` 本地回显。
     */
    @Inject("b", InjectMode.InsertBefore, "(Ljava/lang/String;)V")
    fun ad.deferMissingUnitDisconnect(reason: String): Any {
        if (reason.startsWith("Missing unit:") && ModSyncController.shouldDeferMissingUnitsCheck()) {
            logger.info("[MODSYNC] keep connection despite engine missing-unit disconnect")
            return InterruptResult.Unit
        }
        return Unit
    }

    @Inject("g", InjectMode.Override)
    fun onPlayerJoin(c: c) {
        val asVar = `as`()
        try {
            val B = GameEngine.B()
            asVar.c(packageName)
            asVar.a(2)
            asVar.a(B.bX.e)
            asVar.a(B.c(true))
            asVar.c(B.l())
            asVar.c(B.bX.ab())
            asVar.a(c.M)
            asVar.a(B.bX.W)
            asVar.a(0)
            B.bX.a(c, asVar.b(InternalPacketType.PREREGISTER_INFO.type))
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    @Inject("a", injectMode = InjectMode.InsertBefore)
    fun onReceiveChat(
        cVar: c?,
        nVar: n?,
        str: String?,
        str2: String?,
        cVar2: c?
    ): Any {
        val room = appKoin.get<Game>().gameRoom
        val player = room.getPlayers()
            .firstOrNull { nVar != null && (it as PlayerImpl?)?.self == nVar }

        if ((str2 ?: "").startsWith(commands.prefix)
            && player != null
            && player != room.localPlayer
            && room.isHost
        ) {
            commands.handleCommandMessage(str2 ?: "", player) { room.sendMessageToPlayer(player, "RWPP", it) }
            return InterruptResult.Unit
        } else {
            return Unit
        }
    }

    @Inject("b", injectMode = InjectMode.InsertBefore)
    fun onShowChat(
        c: c?,
        i: Int,
        str: String?,
        str2: String?): Any {
        val room = appKoin.get<Game>().gameRoom
        val player = room.getPlayers()
            .firstOrNull {
                if (gameRoom.isHost)
                    (c != null && it.client == c) || (c == null && it.name == room.localPlayer.name && str != null)
                else it.name == str
            }

        if ((str2 ?: "").startsWith(commands.prefix) && room.isHost)
            return InterruptResult.Unit

        if (player == null) {
            SystemMessageEvent(str2 ?: "").broadcastIn(onFinished = {
                UI.onReceiveChatMessage(str ?: "",str2 ?: "", i)
            })
        } else {
            logger.info("Received chat message from ${player.name}")
            ChatMessageEvent(
                str ?: "",str2 ?: "", player, i
            ).broadcastIn(onFinished = {
                UI.onReceiveChatMessage(it.sender, it.message, i, it.player)
            })
        }
        return Unit
    }

    private val net by lazy { appKoin.get<Net>() }
    private val gameRoom by lazy { appKoin.get<Game>().gameRoom }
}