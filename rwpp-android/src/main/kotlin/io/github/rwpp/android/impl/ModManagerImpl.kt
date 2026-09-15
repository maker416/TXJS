/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import com.corrodinggames.rts.gameFramework.e.a
import io.github.rwpp.appKoin
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.ReloadModEvent
import io.github.rwpp.event.events.ReloadModFinishedEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.ModReloadSelection
import io.github.rwpp.game.mod.ReloadAbortToVanilla
import io.github.rwpp.game.mod.deleteModFileSafely
import io.github.rwpp.io.calculateSize
import io.github.rwpp.internalModDir
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
        /** 等待游戏主循环开始执行已投递 action 的超时；主循环存活时一帧内即会开始。 */
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
                // mod 同步专用：加入者仍在加载阶段、游戏主循环 i.b() 尚未启动，
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
                // 主循环存活时一帧内就会取出 action 并开始执行。
                // 超时仍未开始 => 主循环已不在消费 action（例如模组同步的内联重载经 t.f() 停止了
                // 引擎线程，之后未进对局，菜单主循环一直是死的），若无限等待将导致 loading 弹窗永久卡死。
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
     * 不把 `bW.a()` 投进 `i.a(float,int)`，否则主循环被占满网络保活停摆。
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
                        val net = GameEngine.t().bU
                        net.l()
                        net.a(KeepConnectedReload.WATCHDOG_DELTA)
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
     * 不调用 t.f()/t.q()，也不置 k.bo（网络 tick 读到 bo=true 会立即 queDisconnect；
     * t.f() 停主循环会导致保活泵 ae.l() 停摆连接超时；t.q() 会清空玩家数组摧毁房间状态）。
     */
    private fun runKeepConnectedReloadCore(enabledByFileName: Map<String, Boolean>?) {
        try {
            val t = GameEngine.t()
            t.bW.d()
            t.bN.save()
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
            val t = GameEngine.t()
            t.bW.d()
            t.bN.save()
            t.bo = true
            try {
                t.f()
                reloadUnitsWithSelection(enabledByFileName)
            } finally {
                t.bo = false
            }
            t.q()
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
        val t = GameEngine.t()
        if (enabledByFileName == null) {
            t.bW.a(false, false)
            return
        }

        ModReloadSelection.activate(enabledByFileName)
        try {
            t.bW.a(false, false)
        } finally {
            ModReloadSelection.deactivate()
        }

        // 扫描后再保存，确保新登记模组的禁用状态能够跨重启恢复。
        t.bW.d()
        t.bN.save()
    }

    override suspend fun modUpdate() {
        val latch = CountDownLatch(1)
        game.post {
            GameEngine.t().bW.k()
            latch.countDown()
        }
        awaitGamePost(latch)
    }

    override suspend fun modReregister() {
        val latch = CountDownLatch(1)
        game.post {
            GameEngine.t().bW.a(false, false)
            latch.countDown()
        }
        awaitGamePost(latch)
    }

    private suspend fun awaitGamePost(latch: CountDownLatch) {
        withContext(Dispatchers.IO) {
            latch.await()
        }
    }

    override suspend fun modSaveChange(enabledByFileName: Map<String, Boolean>?) {
        val latch = CountDownLatch(1)
        game.post {
            try {
                val t = GameEngine.t()
                t.bW.d()
                t.bN.save()
                reloadUnitsWithSelection(enabledByFileName)
            } finally {
                latch.countDown()
            }
        }
        awaitGamePost(latch)
    }

    override fun getModByName(name: String): Mod? {
        return getAllMods().firstOrNull { it.name == name }
    }

    @Suppress("unchecked_cast")
    override fun getAllMods(): List<Mod> {
        val mods = GameEngine.t().bW.e as ArrayList<com.corrodinggames.rts.gameFramework.i.b>

        return buildList {
            mods.forEach {
                add(object : Mod {
                    override val id: Int
                        get() = it.a
                    override val name: String
                        get() = it.q ?: ""
                    override val description: String
                        get() = it.s ?: ""
                    override val minVersion: String
                        get() = it.t ?: ""
                    override val errorMessage: String?
                        get() = it.P
                    override var isEnabled: Boolean
                        get() = !it.f
                        set(value) { it.f = !value }
                    override val path: String
                        get() = modFile().path

                    override fun tryDelete(): Boolean {
                        return deleteModFileSafely(modFile())
                    }

                    override fun getRamUsed(): String {
                        return it.k()
                    }

                    override fun getSize(): Long {
                        return kotlin.runCatching {
                            modFile().calculateSize()
                        }.getOrNull() ?: 0L
                    }

                    override fun getBytes(): ByteArray {
                        val file = modFile()
                        return if(file.isDirectory)
                            file.zipFolderToByte()
                        else file.readBytes()
                    }

                    private fun modFile(): File {
                        // it.e() 经 FileLoader 解析后的路径：combined 后端模式下，
                        // 内部目录模组会解析成 /Android/data/<pkg>/files/units/xxx.rwmod，
                        // 外部目录模组会解析成 /sdcard/rustedWarfare/units/xxx.rwmod。
                        // 直接 File(it.e()) 在两种情况下都能拿到真实绝对路径。
                        val sourceFile = File(it.e())
                        if (sourceFile.exists()) return sourceFile

                        // 回退 1：按外部公共目录解析（兼容旧逻辑）。
                        // a.q() 剥离 [INTERNAL-PATH]/[EXTERNAL-PATH] tag，removePrefix("/SD/") 转成相对路径。
                        val externalRel = a.q(it.g()).removePrefix("/SD/")
                        val externalFile = File("/storage/emulated/0/", externalRel)
                        if (externalFile.exists()) return externalFile

                        // 回退 2：按应用私有目录解析（网络同步模组）。
                        // externalRel 形如 "rustedWarfare/units/xxx.network.rwmod" 或 "units/xxx.rwmod"，
                        // 取末尾 units/ 之后的部分拼到 internalModDir。
                        val fileName = externalRel.substringAfter("units/", externalRel)
                        val internalFile = File(internalModDir, fileName)
                        if (internalFile.exists()) return internalFile

                        // 都找不到时返回外部路径，保持与原行为一致（让上层 getSize/getBytes 自然失败）
                        return externalFile
                    }
                })
            }
        }
    }
}
