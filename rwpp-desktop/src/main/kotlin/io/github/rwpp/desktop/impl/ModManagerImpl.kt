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
import io.github.rwpp.desktop.DesktopGameThreadDispatcher
import io.github.rwpp.desktop.DesktopReloadGlContext
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.ReloadModEvent
import io.github.rwpp.event.events.ReloadModFinishedEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModLoadSafety
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.ModReloadSelection
import io.github.rwpp.game.mod.ReloadAbortToVanilla
import io.github.rwpp.game.mod.requireModSelectionApplied
import io.github.rwpp.game.mod.resolveModEnabledByFileName
import io.github.rwpp.io.calculateSize
import io.github.rwpp.logger
import io.github.rwpp.io.zipFolderToByte
import io.github.rwpp.io.zipFolderTo
import io.github.rwpp.widget.clearProtectedModLoadHint
import io.github.rwpp.widget.refreshProtectedModLoadHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import org.koin.core.component.get
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Single
class ModManagerImpl : ModManager {
    private val game: Game = get()
    private val isReloadingMods = AtomicBoolean(false)

    private val gameThreadDispatcher by lazy {
        DesktopGameThreadDispatcher(game::post, DesktopReloadGlContext::isOwnerThread)
    }

    override suspend fun modReload(forceImmediate: Boolean, enabledByFileName: Map<String, Boolean>?) {
        io.github.rwpp.game.GameStartSafety.requireCanReload()
        if (!isReloadingMods.compareAndSet(false, true)) {
            error("模组仍在重载，请等待完成")
        }
        try {
            logger.info("[MODSYNC] modReload start, broadcasting ReloadModEvent (forceImmediate=$forceImmediate)")
            ReloadModEvent().broadcastIn()
            refreshProtectedModLoadHint(getAllMods(), enabledByFileName)
            // PC 的 forceImmediate 也不能绕过 GL 所有权；已在游戏线程时直接执行，否则排队。
            gameThreadDispatcher.run {
                DesktopReloadGlContext.requireOwnerContext()
                runReloadCore(enabledByFileName)
                appKoin.get<Game>().getAllMaps(true)
            }
            logger.info("[MODSYNC] modReload main work finished")
        } finally {
            logger.info("[MODSYNC] modReload broadcasting ReloadModFinishedEvent (finally)")
            try { ReloadModFinishedEvent().broadcastIn() } finally {
                clearProtectedModLoadHint()
                isReloadingMods.set(false)
            }
        }
    }

    /**
     * 进房后模组同步专用：当前线程重建单位表，主循环只泵网络（见 [KeepConnectedReload]）。
     * 不把 `bZ.a()` 投进 `i.b(float,int)`，否则主循环被占满网络保活停摆。
     */
    override suspend fun modReloadKeepConnected(enabledByFileName: Map<String, Boolean>?) {
        io.github.rwpp.game.GameStartSafety.requireCanReload()
        if (!isReloadingMods.compareAndSet(false, true)) {
            error("模组仍在重载，请等待完成")
        }
        try {
            logger.info("[MODSYNC] modReloadKeepConnected start, broadcasting ReloadModEvent")
            ReloadModEvent().broadcastIn()
            refreshProtectedModLoadHint(getAllMods(), enabledByFileName)
            gameThreadDispatcher.run { KeepConnectedReload.begin() }
            val watchdog = startNetWatchdog()
            try {
                withContext(Dispatchers.IO) {
                    DesktopReloadGlContext.withSharedContext {
                        var aborted = false
                        try {
                            runKeepConnectedReloadCore(enabledByFileName)
                        } catch (e: ReloadAbortToVanilla) {
                            logger.info("[MODSYNC] custom mod load aborted by cancel")
                            aborted = true
                        }
                        runVanillaFallbackIfRequested(aborted)
                    }
                }
                if (!io.github.rwpp.game.GameStartSafety.requiresRestart) {
                    game.refreshHandshakeChecksumCache()
                }
            } finally {
                withContext(NonCancellable) {
                    try { finishKeepConnectedReload() } finally {
                        watchdog.interrupt()
                        watchdog.join(2_000L)
                    }
                }
            }
            logger.info("[MODSYNC] modReloadKeepConnected main work finished")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.error("[MODSYNC] keep-connected mod reload failed", e)
            throw e
        } finally {
            try { ReloadModFinishedEvent().broadcastIn() } finally {
                clearProtectedModLoadHint()
                isReloadingMods.set(false)
            }
        }
    }

    override suspend fun modReloadKeepConnectedVanillaOnly() {
        if (isReloadingMods.get() && KeepConnectedReload.active) {
            logger.info("[MODSYNC] vanilla-only fallback delegated to in-flight keep-connected reload")
            return
        }
        if (!isReloadingMods.compareAndSet(false, true)) {
            error("模组仍在重载，请等待完成")
        }
        try {
            gameThreadDispatcher.run { KeepConnectedReload.begin() }
            val watchdog = startNetWatchdog()
            try {
                withContext(Dispatchers.IO) {
                    DesktopReloadGlContext.withSharedContext {
                        KeepConnectedReload.disarmAbortInject()
                        runVanillaFallbackIfRequested(aborted = true)
                    }
                }
                if (!io.github.rwpp.game.GameStartSafety.requiresRestart) {
                    game.refreshHandshakeChecksumCache()
                }
            } finally {
                withContext(NonCancellable) {
                    try { finishKeepConnectedReload() } finally {
                        watchdog.interrupt()
                        watchdog.join(2_000L)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.error("[MODSYNC] vanilla-only mod reload failed", e)
            throw e
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
        if (io.github.rwpp.game.GameStartSafety.requiresRestart) {
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
            if (!io.github.rwpp.game.GameStartSafety.requiresRestart && KeepConnectedReload.shouldRefreshMenuAfterAbort) {
                logger.info("[MODSYNC] refresh menu after abort-to-vanilla, before releasing keep-connected gate")
                game.refreshMenuAfterDisconnect()
            }
        } catch (e: Throwable) {
            logger.warn("[MODSYNC] refresh menu after abort failed: ${e.message}")
        }
        try {
            gameThreadDispatcher.run { DesktopReloadGlContext.resetOwnerTextureBinding() }
        } finally {
            KeepConnectedReload.end()
        }
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
        ModLoadSafety.run("room-reload") {
            saveModSelection(enabledByFileName)
            reloadUnitsWithSelection(enabledByFileName)
        }
    }

    /**
     * 重载内核：调用引擎扫描 mods 目录并重新加载。
     * 必须在持有主窗口 GL 上下文的游戏线程执行。
     */
    private fun runReloadCore(enabledByFileName: Map<String, Boolean>?) {
        ModLoadSafety.run("local-reload") {
            val engine = GameEngine.B()
            saveModSelection(enabledByFileName)
            try {
                engine.br = true
                engine.e()
                reloadUnitsWithSelection(enabledByFileName)
                engine.x()
            } finally { engine.br = false }
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
            requireModSelectionApplied(getAllMods(), enabledByFileName)
        } finally {
            ModReloadSelection.deactivate()
        }

        // 扫描后再保存，确保新登记模组的禁用状态能够跨重启恢复。
        B.bZ.e()
        B.bQ.save()
    }

    private fun saveModSelection(enabledByFileName: Map<String, Boolean>?) {
        // UI 的待应用开关不再直接修改引擎；停止/重载前先保存本次选择。
        if (enabledByFileName != null) {
            getAllMods().forEach { mod ->
                mod.isEnabled = resolveModEnabledByFileName(listOf(mod.path), enabledByFileName)
            }
            logger.info("[MODSYNC] reload selection: {}", enabledByFileName)
        }
        val engine = GameEngine.B()
        engine.bZ.e()
        engine.bQ.save()
    }

    override suspend fun modUpdate() {
        val B = GameEngine.B()
        // getAllModList:
        // Number of mods:
        // Modded Custom
        B.bZ.k()
    }

    override suspend fun modReregister() {
        auxiliaryReload("reregister") { GameEngine.B().bZ.a(false, false) }
    }

    override suspend fun modSaveChange(enabledByFileName: Map<String, Boolean>?) {
        auxiliaryReload("save-reload") {
            val b = GameEngine.B()
            saveModSelection(enabledByFileName)
            if (!b.bX.B) reloadUnitsWithSelection(enabledByFileName)
        }
    }

    private suspend fun auxiliaryReload(stage: String, action: () -> Unit) {
        io.github.rwpp.game.GameStartSafety.requireCanReload()
        check(isReloadingMods.compareAndSet(false, true)) { "模组仍在重载，请等待完成" }
        try {
            gameThreadDispatcher.run {
                DesktopReloadGlContext.requireOwnerContext()
                ModLoadSafety.run(stage, action)
            }
        } finally { isReloadingMods.set(false) }
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

                    override fun writePayloadTo(output: java.io.OutputStream) {
                        val file = File(path)
                        if (file.isDirectory) file.zipFolderTo(output)
                        else file.inputStream().use { it.copyTo(output, 64 * 1024) }
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
