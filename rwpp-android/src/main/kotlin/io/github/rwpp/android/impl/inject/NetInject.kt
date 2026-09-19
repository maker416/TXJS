/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl.inject

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import com.corrodinggames.rts.appFramework.ClosingActivity
import com.corrodinggames.rts.appFramework.MultiplayerBattleroomActivity
import com.corrodinggames.rts.gameFramework.j.ae
import com.corrodinggames.rts.gameFramework.k
import io.github.rwpp.*
import io.github.rwpp.android.cachePlayerSet
import io.github.rwpp.android.impl.ClientImpl
import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.android.impl.PlayerImpl
import io.github.rwpp.android.isReturnToBattleRoom
import io.github.rwpp.core.ModSyncController
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.ChatMessageEvent
import io.github.rwpp.event.events.PlayerJoinEvent
import io.github.rwpp.event.events.SystemMessageEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.Player
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.InterruptResult
import io.github.rwpp.inject.RedirectMethod
import io.github.rwpp.net.Client
import io.github.rwpp.net.InternalPacketType
import io.github.rwpp.net.Net
import io.github.rwpp.ui.UI
import io.github.rwpp.utils.Reflect
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.*

@InjectClass(ae::class)
object NetInject {

    /** 原版 `drawable/icon` 来自游戏 res，仓库默认不收录；缺失时回退启动器图标。 */
    private fun multiplayerNotificationIcon(context: android.content.Context): Int {
        val extracted = context.resources.getIdentifier("icon", "drawable", context.packageName)
        return if (extracted != 0) extracted else R.drawable.ic_launcher_2
    }

    @SuppressLint("WrongConstant")
    @Inject("X", InjectMode.Override)
    fun notify1() {
        if (!GameEngine.aR) {
            val t: k = GameEngine.t()
            val intent = Intent(
                t.al,
                ClosingActivity::class.java
            )
            // support for android 12
            val activity = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.getActivity(
                    t.al, 0, Intent(
                        t.al,
                        ClosingActivity::class.java
                    ), PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getActivity(
                    t.al, 0, Intent(
                        t.al,
                        ClosingActivity::class.java
                    ), PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            val notificationManager =
                t.al.getSystemService("notification") as NotificationManager
            if (Build.VERSION.SDK_INT >= 11) {
                val i = Build.VERSION.SDK_INT
                val builder = Notification.Builder(t.al)
                builder.setContentTitle("Rusted Warfare Multiplayer")
                builder.setContentText("A multiplayer game is in progress")
                builder.setSmallIcon(multiplayerNotificationIcon(t.al))
                builder.setContentIntent(activity)
                builder.setOngoing(true)
                Reflect.call<ae, Any>(null, "a", listOf(notificationManager::class), listOf(notificationManager))
                Reflect.call<ae, Any>(null, "a", listOf(builder::class, String::class), listOf(builder, "multiplayerStatusId"))
                if (Build.VERSION.SDK_INT >= 16) {
                    builder.build()
                }
                notificationManager.notify(1, builder.notification)
            }
        }
    }

    @SuppressLint("WrongConstant")
    @Inject("d", InjectMode.Override)
    fun ae.notify2(arg1: String, arg2: String) {
        if (!GameEngine.aR) {
            val t: k = GameEngine.t()
            if (!this.G && !t.bY.g()) {
                var isActivityVisible: Boolean =
                    MultiplayerBattleroomActivity.isActivityVisible()
                val abVar = t.an
                if (abVar != null && !abVar.isPaused) {
                    isActivityVisible = true
                }
                if (isActivityVisible) {
                    if (Reflect.get<Boolean>(this, "bD") == true) {
                        Reflect.call<ae, Any>(null, "f", listOf(Int::class), listOf(2))
                        return Unit
                    }
                    return Unit
                }
                val notificationManager: NotificationManager =
                    t.al.getSystemService("notification") as NotificationManager
                val intent = Intent(
                    t.al,
                    ClosingActivity::class.java
                )
                // support for android 12
                val activity = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.getActivity(
                        t.al, 0, Intent(
                            t.al,
                            ClosingActivity::class.java
                        ), PendingIntent.FLAG_IMMUTABLE
                    )
                } else {
                    PendingIntent.getActivity(
                        t.al, 0, Intent(
                            t.al,
                            ClosingActivity::class.java
                        ), PendingIntent.FLAG_UPDATE_CURRENT
                    )
                }
                if (Build.VERSION.SDK_INT >= 11) {
                    val builder: Notification.Builder = Notification.Builder(t.al)
                    builder.setContentTitle("Rusted Warfare Multiplayer")
                    builder.setContentText("$arg1: $arg2")
                    builder.setSmallIcon(multiplayerNotificationIcon(t.al))
                    builder.setContentIntent(activity)
                    builder.setOngoing(false)
                    builder.setAutoCancel(true)
                    Reflect.call<ae, Any>(null, "a", listOf(notificationManager::class), listOf(notificationManager))
                    Reflect.call<ae, Any>(null, "a", listOf(builder::class, String::class), listOf(builder, "multiplayerChatId"))
                    notificationManager.notify(2, builder.getNotification())
                    Reflect.set(this, "bD", true)
                }
            }
        }
    }

    @Inject("x", InjectMode.InsertBefore)
    fun onReturnToBattleRoom() {
        isReturnToBattleRoom = true
    }

    // 网络引擎每帧执行前执行，将 112 包强制推迟到注册完成之后
    @Inject("a", injectMode = InjectMode.InsertBefore)
    fun ae.guardAcceptStartGameUntilRegistered(delta: Float) {
        if (!this.D && Reflect.get<Boolean>(this, "bB") != true) {
            // 如果 110 包还没发，抑制 112 包的发送
            Reflect.set(this, "bI", true)
        }
    }

    @Inject("a", InjectMode.InsertBefore)
    fun onProcessPacket(packet: com.corrodinggames.rts.gameFramework.j.bi): Any {
        return when (val type = packet.b) {
            InternalPacketType.PREREGISTER_INFO.type -> {
                val r0 = com.corrodinggames.rts.gameFramework.j.j(packet);     // Catch: java.lang.Throwable -> L603
                val r1 = packet.a
                val r14 = GameEngine.t().bU
                r0.b.readUTF() // Catch: java.lang.Throwable -> L603
                val r2 = r0.b.readInt() // Catch: java.lang.Throwable -> L603
                val r3 = r0.b.readInt() // Catch: java.lang.Throwable -> L603
                r0.b.readInt() // Catch: java.lang.Throwable -> L603
                r0.b.readUTF() // Catch: java.lang.Throwable -> L603
                r14.U = r0.b.readUTF() // Catch: java.lang.Throwable -> L603
                r1.F = r3 // Catch: java.lang.Throwable -> L603

                if (r2 >= 1) r14.V = r0.b.readInt();

                if (r2 >= 2) {
                    r14.W = r0.b.readInt();     // Catch: java.lang.Throwable -> L603
                    r14.X = r0.b.readInt();
                }

                // 允许 112 包的发送
                Reflect.set(r14, "bI", true)
                r14::class.java.getDeclaredMethod("f", r1::class.java).apply {
                    isAccessible = true
                }.invoke(r14, r1)


                InterruptResult(Unit)
            }

            InternalPacketType.PREREGISTER_INFO_RECEIVE.type -> {
                val j = com.corrodinggames.rts.gameFramework.j.j(packet)
                val c = packet.a

                j.b.readUTF()
                val i = j.b.readInt()
                j.b.readInt()
                if (i >= 1) j.b.readUTF()
                if (i >= 2) j.a()?.let {
                    c.p = it
                }

                if (i >= 4) GameEngine.ab()

                val t = GameEngine.t()
                val a = com.corrodinggames.rts.gameFramework.j.bg()
                a.b(packageName)
                a.c(2)
                a.c(t.bU.e)
                a.c(t.a(true))
                a.b(t.h())
                if (t.bN.networkServerId == null) {
                    t.bN.networkServerId = UUID.randomUUID().toString()
                    t.bN.save()
                }
                a.b(t.bN.networkServerId)
                a.c(c.N)
                a.c(t.bU.Y)
                a.c(0)

                Reflect.call<ae, Any>(
                    t.bU,
                    "a",
                    listOf(c::class, com.corrodinggames.rts.gameFramework.j.bi::class),
                    listOf(c, a.a(InternalPacketType.PREREGISTER_INFO.type))
                )
                InterruptResult.Unit
            }

            InternalPacketType.REGISTER_PLAYER.type -> {
                // 注册握手入口（服务器侧）：暂存玩家名/单位校验和/连接 IP，
                // 供 redirectUnitsChecksum 判定是否放行校验和不匹配、正在带外同步的加入者。
                // 不拦截原方法；解析失败静默回落原版行为（校验和不匹配照踢）。
                ModSyncController.pendingRegistration = parseRegistration(packet)
                Unit
            }


            else -> {
                net.listeners[type]?.forEach { listener ->
                    val result = listener.invoke(
                        packet.a as Client,
                        net.packetDecoders[type]!!.invoke(
                            DataInputStream(
                                ByteArrayInputStream(packet.c)
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
     * 解析 REGISTER_CONNECTION(110) 注册包，读取顺序严格对齐引擎 ae.a(bi) case 110
     * （见 build/tmp-decompile/ae_android_full.txt:10155-10223）：
     * 前缀 readUTF() → 格式版本 readInt() → 协议版本 readInt() → 游戏版本 readInt()
     * → 玩家名 readUTF() → 密码 j.a() → [格式≥1] UUID readUTF() → [格式≥2] 重连 token readUTF()
     * → [格式≥3] 单位校验和 readInt()（其后 [格式≥4]/[格式≥5] 还各有一个 readUTF()，与解析无关）。
     */
    private fun parseRegistration(packet: com.corrodinggames.rts.gameFramework.j.bi): ModSyncController.PendingRegistration? {
        return runCatching {
            val reader = com.corrodinggames.rts.gameFramework.j.j(packet)
            reader.b.readUTF()
            val format = reader.b.readInt()
            reader.b.readInt()
            reader.b.readInt()
            val name = reader.b.readUTF()
            reader.a()
            if (format >= 1) reader.b.readUTF()
            if (format >= 2) reader.b.readUTF()
            val checksum = if (format >= 3) reader.b.readInt() else -1
            ModSyncController.PendingRegistration(name, packet.a.f(), checksum)
        }.getOrElse {
            logger.warn("[MODSYNC] parse REGISTER_CONNECTION failed: ${it.message}")
            null
        }
    }

    /**
     * 握手单位校验和放行（模组同步 v2「进房后同步」的核心）。
     * 仅重定向 ae.a(bi) 方法体内的 k.r() 调用（校验和比较与踢人日志两处）：
     * 存在已暂存的注册且回调放行时返回客户端校验和使 != 不成立（跳过踢人）；
     * 否则返回引擎真实校验和，保持原版踢人行为，对原版客户端零影响。
     */
    @RedirectMethod(
        method = "a",
        methodDesc = "(Lcom/corrodinggames/rts/gameFramework/j/bi;)V",
        targetClassName = "com.corrodinggames.rts.gameFramework.k",
        targetMethod = "r",
    )
    fun ae.redirectUnitsChecksum(): Int {
        val pending = ModSyncController.pendingRegistration
        if (pending != null && ModSyncController.shouldAllowMismatch(pending.name, pending.ip)) {
            logger.info("[MODSYNC] allow checksum mismatch for syncing peer: ${pending.name}")
            return pending.clientChecksum
        }
        return GameEngine.t().r()
    }

    /**
     * 兜底：即使缺单位检查仍抛了 `bw`，也不执行 `ae.b("Missing unit:...")` 自断。
     * 进房后同步期间连接必须活着，否则聊天变成 `not networked` 本地回显。
     */
    @Inject("b", InjectMode.InsertBefore, "(Ljava/lang/String;)V")
    fun ae.deferMissingUnitDisconnect(reason: String): Any {
        if (reason.startsWith("Missing unit:") && ModSyncController.shouldDeferMissingUnitsCheck()) {
            logger.info("[MODSYNC] keep connection despite engine missing-unit disconnect")
            return InterruptResult.Unit
        }
        return Unit
    }

    @Inject("d", InjectMode.InsertBefore)
    fun onSendServerInfo(c: com.corrodinggames.rts.gameFramework.j.c) {
        c.A?.let {
            if (!cachePlayerSet.contains(it)) {
                PlayerJoinEvent(it as Player).broadcastIn()
                cachePlayerSet.add(it)
            }
        }
    }

    @Inject("a", InjectMode.InsertBefore)
    fun onReceiveGameCommand(b3: com.corrodinggames.rts.gameFramework.e): Any {
        // 服务器侧拦截：收到被禁单位的命令时直接丢弃，不再入队与转发。
        // 命令到达预定帧后的统一执行由 GameCommandInject 拦截兜底。
        return if (isBannedCommand(b3)) InterruptResult.Unit else Unit
    }

    @Inject("a", injectMode = InjectMode.InsertBefore)
    fun onReceiveChat(
        cVar: com.corrodinggames.rts.gameFramework.j.c?,
        pVar: com.corrodinggames.rts.game.p?,
        str: String?,
        str2: String?,
        cVar2: com.corrodinggames.rts.gameFramework.j.c?
    ): Any {
        val room = appKoin.get<Game>().gameRoom

        if ((str2 ?: "").startsWith(commands.prefix)
            && pVar != null
            && pVar != room.localPlayer
            && room.isHost
        ) {
            commands.handleCommandMessage(str2 ?: "", pVar as Player) { room.sendMessageToPlayer(pVar, "RWPP", it) }
            return InterruptResult.Unit
        } else {
            return Unit
        }
    }

    @Inject("k", injectMode = InjectMode.InsertBefore)
    fun onSendChatMessage(message: String): Any{
        return if (gameRoom.isHost && message.startsWith(commands.prefix)) {
            commands.handleCommandMessage(message, gameRoom.localPlayer) { gameRoom.sendMessageToPlayer(gameRoom.localPlayer, "RWPP", it) }
            InterruptResult.Unit
        } else Unit
    }

    @Inject("a", injectMode = InjectMode.InsertBefore)
    fun onShowChat(
        c: com.corrodinggames.rts.gameFramework.j.c?,
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
                UI.onReceiveChatMessage(it.sender, it.message, i)
            })
        }
        return Unit
    }

    @Inject("b", injectMode = InjectMode.Override)
    fun fuckCheck(z: Boolean) {
        // hack fix game frame
        val t: k = GameEngine.t()
        if (t.bu >= t.bU.Z) {
            if (t.bu > t.bU.Z) {
                t.bu = t.bU.Z
            }
            t.bU.aa = true;
        }
        if (z && t.bU.m()) {
            t.bU.aa = true
        }
    }

    private val gameRoom by lazy { appKoin.get<Game>().gameRoom }
    private val net by lazy { appKoin.get<Net>() }
}