/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import io.github.rwpp.account.RoomIdentityController
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.core.*
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.JoinGameEvent
import io.github.rwpp.game.Game
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.logger
import io.github.rwpp.net.*
import kotlinx.coroutines.*

/** The same native synchronization, engine and admission transaction is used by every entry point. */
internal data class NativeRoomEntry(
    val address: String,
    val room: RoomDescription? = null,
    val userName: String,
    val hostSession: Boolean = false,
    val hostEnableMods: Boolean = false,
    val hostTransferMods: Boolean = false,
    val onlineGame: OnlineGameJoinRequest? = null,
    val returnPage: LauncherPage = LauncherPage.Multiplayer,
)

/** Navigation may immediately cancel the page's loading job. Finish the synchronous commit on Main. */
internal suspend fun commitNativeRoomEntry(
    isCurrent: () -> Boolean,
    prepare: () -> Unit,
    navigate: () -> Unit,
    committed: () -> Unit,
): Boolean = withContext(Dispatchers.Main.immediate) {
    if (!isCurrent()) return@withContext false
    prepare()
    navigate()
    committed()
    true
}

internal suspend fun LoadingContext.joinNativeRoom(
    request: NativeRoomEntry,
    game: Game,
    configIO: ConfigIO,
    isCurrent: () -> Boolean,
    currentFailure: () -> ConnectionFailure? = { null },
    revalidate: suspend () -> Unit = {},
    onHostSyncFailure: suspend (Throwable) -> HostModSyncFailureAction = { HostModSyncFailureAction.ContinueWithoutSharing },
    onOpenRoom: () -> Unit,
): Boolean {
    val hostSession = request.hostSession
    val hostEnableMods = request.hostEnableMods
    val hostTransferMods = request.hostTransferMods
    val userName = request.userName
    val onlineGameThemeToken = request.onlineGame?.themeToken
    val onlineGameExpansion = request.onlineGame?.expansionBinding?.let {
        io.github.rwpp.content.ExpansionJoinExpectation(request.onlineGame.appId, it)
    }
    val onlineGameAppId = request.onlineGame?.appId
    return run {
        var address = request.address
        var onlineGameOpened = false
        val capturedRoomDescription = request.room
        // 新的加入/开房会话开始：等待可能挂起的退房清理完成，保证旧会话先拆后建（GameSessionController）
        // 等待旧退房清理与预同步之前就申请保活；切回 IO 时取消也不能丢弃资源句柄。
        val keepAlive = withContext(NonCancellable + Dispatchers.Main.immediate) {
            game.acquireMultiplayerKeepAlive()
        }
        try {
            GameSessionController.beginSession(allowExpansion = !hostSession) {
                if (!withContext(Dispatchers.Main.immediate) {
                    if (!isCurrent()) false else {
                        GameSessionController.multiplayerReturnPage = request.returnPage
                        true
                    }
                }) return@beginSession false
                var joined = false
                var opened = false
                var directReservation: io.github.rwpp.core.DirectJoinReservation? = null
                var directResume: io.github.rwpp.core.DirectResumeConnection? = null
                var expansionToken: Long? = null
                logger.info("[JOIN] begin room entry")
                try {
                    ModSyncController.awaitJoinEntryCleanup()
                    revalidate()
                    onlineGameThemeToken?.let { io.github.rwpp.theme.OnlineGameThemeController.activatePrepared(it) }
                    if(address.isBlank()) {
                        failure(ConnectionFailure(ConnectionFailureReason.NO_ROOM))
                        return@beginSession false
                    }
                    if (!io.github.rwpp.net.isSingleLineJoinAddress(address)) {
                        failure(ConnectionFailure(ConnectionFailureReason.INVALID_ADDRESS, details = address))
                        return@beginSession false
                    }
                    address = roomJoinAddressForId(address)

                    if (hostSession) {
                        val shareMods = hostTransferMods && awaitHostModSyncService(
                            prepare = {
                                message(readI18n("modSync.hostCheckingService"))
                                ModSyncController.prepareHostSession()
                            },
                            onFailure = { error ->
                                onHostSyncFailure(error)
                            },
                        )
                        // 旧断线清理会清空 hostSyncRequested，必须在开始屏障之后提交本次选项。
                        withContext(Dispatchers.Main.immediate) { ModSyncController.hostSyncRequested = shareMods }
                        if (!hostEnableMods) {
                            if (!quickHostAddressSkipsClientVanillaModClear(address)) {
                                val cleared = Logic.disableAllModsBeforeHosting()
                                if (cleared) {
                                    message(readI18n("multiplayer.modsClearedForVanillaHost"))
                                }
                            }
                        } else {
                            val disabled = Logic.disableNetworkModsBeforeHosting()
                            if (disabled) {
                                message(readI18n("multiplayer.networkModDisabledOnHost"))
                            }
                        }
                    } else {
                        // Direct 在原版连接前完成下载、普通重载与校验；只有未识别协议才回落旧 relay。
                        when (val direct = io.github.rwpp.core.DirectModSyncController.shared.preJoinSync(address, this, onlineGameExpansion, onlineGameAppId)) {
                            is io.github.rwpp.core.DirectJoinSyncResult.Applied -> {
                                directReservation = direct.reservation
                                directResume = direct.resume
                                expansionToken = direct.expansionToken
                                direct.pinnedAddress?.let { address = it }
                                // 本轮未创建 relay joining Presence，也清掉任何旧会话遗留上下文。
                                ModSyncController.finishJoinerPresence()
                            }
                            io.github.rwpp.core.DirectJoinSyncResult.Unsupported -> {
                                if (onlineGameExpansion != null) {
                                    failure(ConnectionFailure(ConnectionFailureReason.SYNC_FAILED, details = "The server does not support the required game expansion"))
                                    return@beginSession false
                                }
                                if (!ModSyncController.preJoinSync(capturedRoomDescription, address, this)) {
                                    logger.info("[JOIN] relay pre-join sync did not allow connection")
                                    return@beginSession false
                                }
                            }
                            is io.github.rwpp.core.DirectJoinSyncResult.Failed -> {
                                failure(direct.failure)
                                return@beginSession false
                            }
                        }
                    }

                    revalidate()
                    message("connecting...")

                    game.setUserName(userName)
                    configIO.setGameConfig("lastNetworkIP", address)

                    suspend fun connectAndConfirmAdmission(): Result<String> {
                        val result = game.directJoinServer(address, capturedRoomDescription?.joinRelayUuid(), this)
                        if (result.isSuccess) {
                            joined = true
                            directReservation?.let { reservation ->
                                message(readI18n("modSync.directConfirmingSeat"))
                                reservation.awaitAdmission()
                                reservation.captureResume()
                            }
                            directResume?.awaitAdmission()
                        }
                        return result
                    }
                    val result = try {
                        val reservation = directReservation
                        val recovery = directResume
                        if (recovery != null) recovery.openCredentials().use { connectAndConfirmAdmission() }
                        else if (reservation == null) connectAndConfirmAdmission()
                        else reservation.protect {
                            reservation.openCredentials().use { connectAndConfirmAdmission() }
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        val model = connectionFailure(error, io.github.rwpp.net.ConnectionFailureStage.SYNC_MANIFEST)
                            ?: throw error
                        failure(model)
                        return@beginSession false
                    }
                    logger.info("[JOIN] engine connection returned; success={}", result.isSuccess)
                    result.exceptionOrNull()?.let { logger.warn("[JOIN] connection failed", it) }
                    if(result.isSuccess) {
                        // 房间身份公示（带外机制）：暂存候选 key 与玩家名快照；
                        // 房主走 quickHost 指令串产不出 key，靠进房后 roomDetails 短码补充
                        joined = true
                        val accepted = commitNativeRoomEntry(
                            isCurrent = isCurrent,
                            prepare = {
                                RoomIdentityController.onJoiningRoom(address, capturedRoomDescription)
                                onlineGameThemeToken?.let { io.github.rwpp.theme.OnlineGameThemeController.onRoomOpened(it) }
                                expansionToken?.let { io.github.rwpp.content.ExpansionContentController.onRoomOpened(it) }
                                GameSessionController.multiplayerReturnPage = request.returnPage
                            },
                            navigate = {
                                onOpenRoom()
                                opened = true
                                onlineGameOpened = true
                            },
                            committed = {
                                ModSyncController.onJoinedRoom()
                                JoinGameEvent(address).broadcastIn()
                            },
                        )
                        if (!accepted) return@beginSession false
                        logger.info("[JOIN] room navigation committed")
                        true
                    } else {
                        val error = result.exceptionOrNull()
                        val model = error?.let { connectionFailure(it) }
                        if (error != null && model == null) throw kotlinx.coroutines.CancellationException("Connection attempt cancelled", error)
                        if (model != null) failure(currentFailure() ?: model)
                        false
                    }
                } finally {
                    logger.info("[JOIN] entry finished; joined={} roomOpened={}", joined, opened)
                    try {
                        if (joined && !opened) {
                            withContext(NonCancellable + Dispatchers.Main.immediate) {
                                game.gameRoom.disconnectAndWait()
                            }
                        }
                    } finally {
                        if (hostSession && !opened) ModSyncController.discardPreparedHostSession()
                        directReservation?.close()
                        directResume?.close()
                        if (!opened) expansionToken?.let { token ->
                            withContext(NonCancellable + Dispatchers.Main.immediate) { io.github.rwpp.content.ExpansionContentController.cancel(token) }
                        }
                        // 成功时为空操作；被踢重试已排定时保留 Presence。
                        ModSyncController.finishJoinerPresence()
                    }
                }
            } ?: false
        } finally {
            try {
                if (onlineGameThemeToken != null && !onlineGameOpened) {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        io.github.rwpp.theme.OnlineGameThemeController.cancelPrepared(onlineGameThemeToken)
                    }
                }
            } finally { keepAlive.close() }
        }
    }
}
