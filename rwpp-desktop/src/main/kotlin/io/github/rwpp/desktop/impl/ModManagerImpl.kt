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
import io.github.rwpp.desktop.DesktopLoadedModSnapshot
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.ReloadModEvent
import io.github.rwpp.event.events.ReloadModFinishedEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.KeepConnectedReload
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.game.mod.ModLoadSafety
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.ModReloadSelection
import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModDescriptor
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import org.koin.core.component.get
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private typealias DetachedResources = io.github.rwpp.game.mod.DetachedModResources<
    com.corrodinggames.rts.gameFramework.m.e, com.corrodinggames.rts.gameFramework.a.i>?

@Single
class ModManagerImpl : ModManager {
    private val game: Game = get()
    private val isReloadingMods = AtomicBoolean(false)

    private val gameThreadDispatcher by lazy {
        DesktopGameThreadDispatcher(game::post, DesktopReloadGlContext::isOwnerThread)
    }

    override suspend fun isLoadedModSetExact(
        descriptors: List<NetworkModDescriptor>, cache: NetworkModCache,
    ): Boolean = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        !isReloadingMods.get() && DesktopLoadedModSnapshot.isExact(getAllMods(), descriptors, cache, context::ensureActive)
    }

    override suspend fun modReload(forceImmediate: Boolean, enabledByFileName: Map<String, Boolean>?) =
        reloadLocal(forceImmediate, enabledByFileName, refreshGameArt = false)

    override suspend fun reloadGameArt() = reloadLocal(false, null, refreshGameArt = true)

    private suspend fun reloadLocal(
        forceImmediate: Boolean,
        enabledByFileName: Map<String, Boolean>?,
        refreshGameArt: Boolean,
    ) {
        io.github.rwpp.game.GameStartSafety.requireCanReload()
        if (!isReloadingMods.compareAndSet(false, true)) {
            error("模组仍在重载，请等待完成")
        }
        val loadedSnapshotToken = DesktopLoadedModSnapshot.beginLoad()
        try {
            logger.info("[MODSYNC] modReload start, broadcasting ReloadModEvent (forceImmediate=$forceImmediate)")
            game.invalidateModPlaytimeSnapshot()
            ReloadModEvent().broadcastIn()
            refreshProtectedModLoadHint(getAllMods(), enabledByFileName)
            // PC 的 forceImmediate 也不能绕过 GL 所有权；已在游戏线程时直接执行，否则排队。
            gameThreadDispatcher.run {
                DesktopReloadGlContext.requireOwnerContext()
                runReloadCore(enabledByFileName, refreshGameArt)
                // 重载成功后才到这里：释放本轮未使用的贴图/音效缓存（需主 GL 上下文）。
                DesktopModResourceCache.sweep()
                appKoin.get<Game>().getAllMaps(true)
            }
            logger.info("[MODSYNC] modReload main work finished")
        } finally {
            logger.info("[MODSYNC] modReload broadcasting ReloadModFinishedEvent (finally)")
            game.invalidateModPlaytimeSnapshot()
            try { ReloadModFinishedEvent().broadcastIn() } finally {
                clearProtectedModLoadHint()
                isReloadingMods.set(false)
            }
        }
        DesktopLoadedModSnapshot.completeLoad(loadedSnapshotToken, getAllMods())
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
        val loadedSnapshotToken = DesktopLoadedModSnapshot.beginLoad()
        try {
            logger.info("[MODSYNC] modReloadKeepConnected start, broadcasting ReloadModEvent")
            game.invalidateModPlaytimeSnapshot()
            ReloadModEvent().broadcastIn()
            refreshProtectedModLoadHint(getAllMods(), enabledByFileName)
            gameThreadDispatcher.run { KeepConnectedReload.begin() }
            val watchdog = startNetWatchdog()
            var detached: DetachedResources = null
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
                        // 只移出缓存；GL 纹理与 Slick 缓存在 finishKeepConnectedReload 的游戏线程上释放。
                        detached = DesktopModResourceCache.detach()
                    }
                }
                if (!io.github.rwpp.game.GameStartSafety.requiresRestart) {
                    game.refreshHandshakeChecksumCache()
                }
            } finally {
                withContext(NonCancellable) {
                    try { finishKeepConnectedReload(detached) } finally {
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
            game.invalidateModPlaytimeSnapshot()
            try { ReloadModFinishedEvent().broadcastIn() } finally {
                clearProtectedModLoadHint()
                isReloadingMods.set(false)
            }
        }
        DesktopLoadedModSnapshot.completeLoad(loadedSnapshotToken, getAllMods())
    }

    override suspend fun modReloadKeepConnectedVanillaOnly() {
        if (isReloadingMods.get() && KeepConnectedReload.active) {
            logger.info("[MODSYNC] vanilla-only fallback delegated to in-flight keep-connected reload")
            return
        }
        if (!isReloadingMods.compareAndSet(false, true)) {
            error("模组仍在重载，请等待完成")
        }
        val loadedSnapshotToken = DesktopLoadedModSnapshot.beginLoad()
        try {
            game.invalidateModPlaytimeSnapshot()
            gameThreadDispatcher.run { KeepConnectedReload.begin() }
            val watchdog = startNetWatchdog()
            var detached: DetachedResources = null
            try {
                withContext(Dispatchers.IO) {
                    DesktopReloadGlContext.withSharedContext {
                        KeepConnectedReload.disarmAbortInject()
                        runVanillaFallbackIfRequested(aborted = true)
                        detached = DesktopModResourceCache.detach()
                    }
                }
                if (!io.github.rwpp.game.GameStartSafety.requiresRestart) {
                    game.refreshHandshakeChecksumCache()
                }
            } finally {
                withContext(NonCancellable) {
                    try { finishKeepConnectedReload(detached) } finally {
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
            game.invalidateModPlaytimeSnapshot()
            clearProtectedModLoadHint()
            isReloadingMods.set(false)
        }
        DesktopLoadedModSnapshot.completeLoad(loadedSnapshotToken, getAllMods())
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

    private suspend fun finishKeepConnectedReload(detached: DetachedResources) {
        try {
            if (!io.github.rwpp.game.GameStartSafety.requiresRestart && KeepConnectedReload.shouldRefreshMenuAfterAbort) {
                logger.info("[MODSYNC] refresh menu after abort-to-vanilla, before releasing keep-connected gate")
                game.refreshMenuAfterDisconnect()
            }
        } catch (e: Throwable) {
            logger.warn("[MODSYNC] refresh menu after abort failed: ${e.message}")
        }
        try {
            gameThreadDispatcher.run {
                // 闸门仍开、主循环不绘制：在主 GL 上下文删除已移出的纹理，再重建 Slick 绑定缓存。
                try { DesktopModResourceCache.release(detached) }
                finally { DesktopReloadGlContext.resetOwnerTextureBinding() }
            }
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
    private fun runReloadCore(enabledByFileName: Map<String, Boolean>?, refreshGameArt: Boolean) {
        if (refreshGameArt) {
            check(!game.gameRoom.isConnecting && !game.gameRoom.isStartGame && !game.gameRoom.isStartingGame) { io.github.rwpp.i18n.readI18n("themes.gameArtSessionActive") }
        }
        ModLoadSafety.run("local-reload") {
            val engine = GameEngine.B()
            val preserveScene = refreshGameArt && game.isGameCouldContinue()
            saveModSelection(enabledByFileName)
            try {
                engine.br = true
                // 纯贴图刷新不能通过 e()/x() 清掉可继续的单人场景。
                if (!preserveScene) engine.e()
                if (refreshGameArt) {
                    // 引擎按资源逻辑路径缓存图片，覆盖目录变化不会改变 key。
                    // 不能回收旧图：旧菜单场景仍可能引用它们，重建场景后交给引擎/GC 释放。
                    com.corrodinggames.rts.game.units.custom.ag.g.clear()
                    com.corrodinggames.rts.game.units.am.bH()
                    logger.info("[GAMEART] image cache invalidated; native unit images reloaded; scene preserved=$preserveScene")
                }
                reloadUnitsWithSelection(enabledByFileName)
                if (!preserveScene) engine.x()
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
        auxiliaryReload("reregister") { GameEngine.B().bZ.a(false, false); true }
    }

    override suspend fun modSaveChange(enabledByFileName: Map<String, Boolean>?) {
        auxiliaryReload("save-reload") {
            val b = GameEngine.B()
            saveModSelection(enabledByFileName)
            val rebuild = !b.bX.B
            if (rebuild) reloadUnitsWithSelection(enabledByFileName)
            rebuild
        }
    }

    /** [action] 返回是否重建了单位表；只有重建过才清理缓存（未重建时上一轮标记仍有效但不应动）。 */
    private suspend fun auxiliaryReload(stage: String, action: () -> Boolean) {
        io.github.rwpp.game.GameStartSafety.requireCanReload()
        check(isReloadingMods.compareAndSet(false, true)) { "模组仍在重载，请等待完成" }
        val loadedSnapshotToken = DesktopLoadedModSnapshot.beginLoad()
        try {
            game.invalidateModPlaytimeSnapshot()
            gameThreadDispatcher.run {
                DesktopReloadGlContext.requireOwnerContext()
                if (ModLoadSafety.run(stage, action)) DesktopModResourceCache.sweep()
            }
        } finally {
            game.invalidateModPlaytimeSnapshot()
            isReloadingMods.set(false)
        }
        DesktopLoadedModSnapshot.completeLoad(loadedSnapshotToken, getAllMods())
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
