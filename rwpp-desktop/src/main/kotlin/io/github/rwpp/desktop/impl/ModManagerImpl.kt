/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import com.corrodinggames.rts.gameFramework.i.b
import io.github.rwpp.appKoin
import io.github.rwpp.desktop.GameEngine
import io.github.rwpp.desktop.IAClass
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.ReloadModEvent
import io.github.rwpp.event.events.ReloadModFinishedEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.ModReloadSelection
import io.github.rwpp.game.mod.ReloadAbortToVanilla
import io.github.rwpp.io.calculateSize
import io.github.rwpp.logger
import io.github.rwpp.io.zipFolderToByte
import io.github.rwpp.widget.clearProtectedModLoadHint
import io.github.rwpp.widget.refreshProtectedModLoadHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import org.koin.core.component.get
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Single
class ModManagerImpl : ModManager {
    private val game: Game = get()
    private val isReloadingMods = AtomicBoolean(false)

    private companion object {
        /** 等待游戏主循环开始执行已投递 action 的超时；主循环存活时一帧内（约 33ms）即会开始。 */
        const val GAME_POST_START_TIMEOUT_MS = 5000L
    }

    override suspend fun modReload(forceImmediate: Boolean, enabledByFileName: Map<String, Boolean>?) {
        if (!isReloadingMods.compareAndSet(false, true)) {
            logger.info("[MODSYNC] modReload skipped: already reloading (forceImmediate=$forceImmediate)")
            return
        }
        try {
            logger.info("[MODSYNC] modReload start, broadcasting ReloadModEvent (forceImmediate=$forceImmediate)")
            ReloadModEvent().broadcastIn()
            refreshProtectedModLoadHint(getAllMods(), enabledByFileName)
            if (forceImmediate) {
                // mod 同步专用：加入者仍在加载阶段、游戏主循环尚未启动，
                // game.post 投递的 action 永远不会被消费 -> 直接在当前线程同步执行重载。
                logger.info("[MODSYNC] modReload forceImmediate: running reload inline on current thread")
                runReloadCore(enabledByFileName)
                logger.info("[MODSYNC] modReload forceImmediate: reload core done, refreshing maps")
                appKoin.get<Game>().getAllMaps(true)
            } else {
                val started = AtomicBoolean(false)
                val startedLatch = CountDownLatch(1)
                val doneLatch = CountDownLatch(1)
                logger.info("[MODSYNC] modReload posting reload action to game thread")
                game.post {
                    if (!started.compareAndSet(false, true)) {
                        // 超时兜底已在其他线程内联执行；此处仅释放可能存在的等待方后丢弃。
                        logger.info("[MODSYNC] modReload game.post action STALE (inline fallback already ran)")
                        doneLatch.countDown()
                        return@post
                    }
                    logger.info("[MODSYNC] modReload game.post action RUNNING on game thread")
                    startedLatch.countDown()
                    try {
                        runReloadCore(enabledByFileName)
                        logger.info("[MODSYNC] modReload game.post action DONE")
                    } catch (e: Throwable) {
                        logger.error("[MODSYNC] modReload game.post action THREW", e)
                        throw e
                    } finally {
                        doneLatch.countDown()
                        logger.info("[MODSYNC] modReload latch counted down")
                    }
                }

                // 主循环存活时一帧内（约 33ms）就会取出 action 并开始执行。
                // 超时仍未开始 => 主循环已不在消费 action（例如模组同步的内联重载曾停止引擎线程，
                // 之后未进对局，菜单主循环一直是死的），若无限等待将导致 loading 弹窗永久卡死。
                val consumed = withContext(Dispatchers.IO) {
                    startedLatch.await(GAME_POST_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
                if (!consumed && started.compareAndSet(false, true)) {
                    // 与 forceImmediate 相同的语义：直接在当前线程执行重载。
                    // started 的 CAS 保证恰好执行一次；主循环若之后复活再取出该 action 会直接丢弃。
                    logger.warn(
                        "[MODSYNC] modReload: game loop did not consume posted action within " +
                            "${GAME_POST_START_TIMEOUT_MS}ms, running reload inline on current thread"
                    )
                    runReloadCore(enabledByFileName)
                    logger.info("[MODSYNC] modReload inline fallback done, refreshing maps")
                } else {
                    // 主循环已接手（含超时瞬间恰好开始执行的竞态），等待其完成。
                    logger.info("[MODSYNC] modReload waiting for game thread (doneLatch.await) ...")
                    withContext(Dispatchers.IO) {
                        doneLatch.await()
                    }
                    logger.info("[MODSYNC] modReload latch released, refreshing maps")
                }
                appKoin.get<Game>().getAllMaps(true)
            }
            logger.info("[MODSYNC] modReload main work finished")
        } finally {
            logger.info("[MODSYNC] modReload broadcasting ReloadModFinishedEvent (finally)")
            ReloadModFinishedEvent().broadcastIn()
            clearProtectedModLoadHint()
            isReloadingMods.set(false)
        }
    }

    /**
     * 进房后模组同步专用：当前线程重建单位表，主循环只泵网络（见 [KeepConnectedReload]）。
     * 不把 `bZ.a()` 投进 `i.b(float,int)`，否则主循环被占满网络保活停摆。
     */
    override suspend fun modReloadKeepConnected(enabledByFileName: Map<String, Boolean>?) {
        if (!isReloadingMods.compareAndSet(false, true)) {
            logger.info("[MODSYNC] modReloadKeepConnected skipped: already reloading")
            return
        }
        try {
            logger.info("[MODSYNC] modReloadKeepConnected start, broadcasting ReloadModEvent")
            ReloadModEvent().broadcastIn()
            refreshProtectedModLoadHint(getAllMods(), enabledByFileName)
            KeepConnectedReload.begin()
            val watchdog = startNetWatchdog()
            try {
                var aborted = false
                try {
                    runKeepConnectedReloadCore(enabledByFileName)
                } catch (e: ReloadAbortToVanilla) {
                    logger.info("[MODSYNC] custom mod load aborted by cancel")
                    aborted = true
                }
                runVanillaFallbackIfRequested(aborted)
                if (!io.github.rwpp.ui.UI.modReloadMemoryExhausted) {
                    game.refreshHandshakeChecksumCache()
                }
            } finally {
                finishKeepConnectedReload()
                watchdog.interrupt()
                watchdog.join(2_000L)
            }
            logger.info("[MODSYNC] modReloadKeepConnected main work finished")
        } finally {
            ReloadModFinishedEvent().broadcastIn()
            clearProtectedModLoadHint()
            isReloadingMods.set(false)
        }
    }

    override suspend fun modReloadKeepConnectedVanillaOnly() {
        if (isReloadingMods.get() && KeepConnectedReload.active) {
            logger.info("[MODSYNC] vanilla-only fallback delegated to in-flight keep-connected reload")
            return
        }
        if (!isReloadingMods.compareAndSet(false, true)) {
            logger.info("[MODSYNC] modReloadKeepConnectedVanillaOnly skipped: already reloading")
            return
        }
        try {
            KeepConnectedReload.begin()
            val watchdog = startNetWatchdog()
            try {
                KeepConnectedReload.disarmAbortInject()
                runVanillaFallbackIfRequested(aborted = true)
                if (!io.github.rwpp.ui.UI.modReloadMemoryExhausted) {
                    game.refreshHandshakeChecksumCache()
                }
            } finally {
                finishKeepConnectedReload()
                watchdog.interrupt()
                watchdog.join(2_000L)
            }
        } finally {
            clearProtectedModLoadHint()
            isReloadingMods.set(false)
        }
    }

    private fun allModsDisabledByFileName(): Map<String, Boolean> =
        getAllMods().associate { File(it.path).name.lowercase() to false }

    private fun runVanillaFallbackIfRequested(aborted: Boolean) {
        if (!aborted && !KeepConnectedReload.abortToVanilla) return
        KeepConnectedReload.disarmAbortInject()
        if (io.github.rwpp.ui.UI.modReloadMemoryExhausted) {
            logger.warn("[MODSYNC] skip vanilla fallback: memory exhausted")
            return
        }
        logger.info("[MODSYNC] abort-to-vanilla: disabling all mods and reloading vanilla units")
        getAllMods().forEach { it.isEnabled = false }
        runKeepConnectedReloadCore(allModsDisabledByFileName())
        KeepConnectedReload.markVanillaFallbackDone()
    }

    private suspend fun finishKeepConnectedReload() {
        try {
            if (KeepConnectedReload.shouldRefreshMenuAfterAbort) {
                logger.info("[MODSYNC] refresh menu after abort-to-vanilla, before releasing keep-connected gate")
                game.refreshMenuAfterDisconnect()
            }
        } catch (e: Throwable) {
            logger.warn("[MODSYNC] refresh menu after abort failed: ${e.message}")
        }
        KeepConnectedReload.end()
    }

    private fun startNetWatchdog(): Thread {
        val thread = Thread({
            while (KeepConnectedReload.active && !Thread.currentThread().isInterrupted) {
                try {
                    if (KeepConnectedReload.isWatchdogDue()) {
                        GameEngine.B().bX.a(KeepConnectedReload.WATCHDOG_DELTA)
                        KeepConnectedReload.markGameTick()
                    }
                    Thread.sleep(KeepConnectedReload.WATCHDOG_SLEEP_MS)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    logger.warn("[MODSYNC] net watchdog pump failed: ${e.message}")
                }
            }
        }, "rwpp-modsync-net-watchdog")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /**
     * 保连接重载内核：保存并重建单位注册表，但不停止引擎线程、不重建菜单场景——
     * 不调用 B.e()/B.x()，也不置 B.br（网络 tick 读到重载标志会立即断开连接；
     * 停主循环会导致网络保活停摆连接超时；场景重建会清空玩家数组摧毁房间状态）。
     */
    private fun runKeepConnectedReloadCore(enabledByFileName: Map<String, Boolean>?) {
        try {
            val B = GameEngine.B()
            B.bZ.e()
            B.bQ.save()
            reloadUnitsWithSelection(enabledByFileName)
        } catch (e: OutOfMemoryError) {
            // 与 runReloadCore 同一防线：吞掉 OOM 并置全局标志，保住进程与连接。
            io.github.rwpp.ui.UI.modReloadMemoryExhausted = true
            logger.error("[MODSYNC] modReloadKeepConnected aborted by OutOfMemoryError", e)
        }
    }

    /**
     * 重载内核：调用引擎扫描 mods 目录并重新加载。
     * 默认应在游戏主线程执行；forceImmediate 时为绕过主循环在调用线程直接执行。
     */
    private fun runReloadCore(enabledByFileName: Map<String, Boolean>?) {
        val runtime = Runtime.getRuntime()
        logger.info(
            "[MODSYNC] reload heap before: used=${(runtime.totalMemory() - runtime.freeMemory()) / 1048576}MB" +
                " max=${runtime.maxMemory() / 1048576}MB"
        )
        System.gc()
        try {
            val B = GameEngine.B()
            B.bZ.e()
            B.bQ.save()
            try {
                B.br = true
                B.e()
                reloadUnitsWithSelection(enabledByFileName)
                B.x()
            } finally {
                B.br = false
            }
        } catch (e: OutOfMemoryError) {
            // 堆已耗尽：置全局标志，本进程内不再允许模组重载（否则反复重载必然崩溃）。
            // 吞掉 OOM 避免游戏线程未捕获崩溃；模组页会检测标志并引导用户重启应用。
            io.github.rwpp.ui.UI.modReloadMemoryExhausted = true
            logger.error("[MODSYNC] runReloadCore aborted by OutOfMemoryError", e)
        }
    }

    /**
     * `a(false, false)` 内部先扫描目录，再解析单位。扫描后的注入点会读取
     * [ModReloadSelection]，因此本次扫描新建的模组也能在单位解析前获得正确状态。
     */
    private fun reloadUnitsWithSelection(enabledByFileName: Map<String, Boolean>?) {
        val B = GameEngine.B()
        if (enabledByFileName == null) {
            B.bZ.a(false, false)
            return
        }

        ModReloadSelection.activate(enabledByFileName)
        try {
            B.bZ.a(false, false)
        } finally {
            ModReloadSelection.deactivate()
        }

        // 扫描后再保存，确保新登记模组的禁用状态能够跨重启恢复。
        B.bZ.e()
        B.bQ.save()
    }

    override suspend fun modUpdate() {
        val B = GameEngine.B()
        // getAllModList:
        // Number of mods:
        // Modded Custom
        B.bZ.k()
    }

    override suspend fun modReregister() {
        val latch = CountDownLatch(1)
        game.post {
            GameEngine.B().bZ.a(false, false)
            latch.countDown()
        }
        withContext(Dispatchers.IO) {
            latch.await()
        }
    }

    override suspend fun modSaveChange(enabledByFileName: Map<String, Boolean>?) {
        val b = GameEngine.B()
        b.bZ.e()
        b.bQ.save()
        if (b.bX.B) return
        reloadUnitsWithSelection(enabledByFileName)
    }

    override fun getModByName(name: String): Mod? {
        return getAllMods().firstOrNull { it.name == name }
    }

    @Suppress("unchecked_cast")
    override fun getAllMods(): List<Mod> {
        val mods = IAClass::class.java.getDeclaredField("e").run {
            isAccessible = true
            get(GameEngine.B().bZ)
        } as ArrayList<b>

        return buildList {
            mods.forEach {
                add(object : Mod {
                    override val id: Int
                        get() = it.a
                    override val name: String
                        get() = it.s ?: ""
                    override val description: String
                        get() = it.u ?: ""
                    override val minVersion: String
                        get() = it.v ?: ""
                    override val errorMessage: String?
                        get() = it.R
                    override var isEnabled: Boolean
                        get() = !it.f
                        set(value) { it.f = !value }
                    override val path: String
                        get() = it.h()

//                    override var isNetworkMod: Boolean
//                        get() = it.c.contains(".network")
//                        set(value) {
//                            if (!value) {
//                                val newName = it.c.replace(".network", "")
//                                File("mods/units/${it.c}").copyTo(File("mods/units/$newName.netbak"))
//                                it.c = newName
//                            } else {
//                                throw RuntimeException("Cannot set a mod to network mod.")
//                            }
//                        }

                    override fun getRamUsed(): String {
                        return it.s()
                    }

                    override fun getSize(): Long {
                        return runCatching {
                            File(path).calculateSize()
                        }.getOrNull() ?: 0L
                    }

                    override fun getBytes(): ByteArray {
                        val file = File(path)
                        return if(file.isDirectory)
                            file.zipFolderToByte()
                        else file.readBytes()
                    }
                })
            }
        }
    }
}
