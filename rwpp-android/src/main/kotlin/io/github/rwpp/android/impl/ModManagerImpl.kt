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
import io.github.rwpp.game.mod.ModLoadSafety
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.ModReloadSelection
import io.github.rwpp.game.mod.ReloadAbortToVanilla
import io.github.rwpp.game.mod.deleteModFileSafely
import io.github.rwpp.io.calculateSize
import io.github.rwpp.internalModDir
import io.github.rwpp.logger
import io.github.rwpp.io.zipFolderToByte
import io.github.rwpp.io.zipFolderTo
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
        io.github.rwpp.game.GameStartSafety.requireCanReload()
        check(isReloadingMods.compareAndSet(false, true)) { "模组仍在重载，请等待完成" }
        try {
            ReloadModEvent().broadcastIn()
            refreshProtectedModLoadHint(getAllMods(), enabledByFileName)
            if (forceImmediate) runReloadCore(enabledByFileName)
            else runOnGameThread { runReloadCore(enabledByFileName) }
            game.getAllMaps(true)
        } finally {
            try { ReloadModFinishedEvent().broadcastIn() } finally {
                clearProtectedModLoadHint()
                isReloadingMods.set(false)
            }
        }
    }

    /**
     * 进房后模组同步专用：当前线程重建单位表，主循环只泵网络（见 [KeepConnectedReload]）。
     * 不把 `bW.a()` 投进 `i.a(float,int)`，否则主循环被占满网络保活停摆。
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
                if (!io.github.rwpp.game.GameStartSafety.requiresRestart) {
                    game.refreshHandshakeChecksumCache()
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    try { finishKeepConnectedReload() } finally {
                        watchdog.interrupt()
                        watchdog.join(2_000L)
                    }
                }
            }
            logger.info("[MODSYNC] modReloadKeepConnected main work finished")
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
            KeepConnectedReload.begin()
            val watchdog = startNetWatchdog()
            try {
                KeepConnectedReload.disarmAbortInject()
                runVanillaFallbackIfRequested(aborted = true)
                if (!io.github.rwpp.game.GameStartSafety.requiresRestart) {
                    game.refreshHandshakeChecksumCache()
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    try { finishKeepConnectedReload() } finally {
                        watchdog.interrupt()
                        watchdog.join(2_000L)
                    }
                }
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
        KeepConnectedReload.end()
    }

    private fun startNetWatchdog(): Thread {
        val thread = Thread({
            while (KeepConnectedReload.active && !Thread.currentThread().isInterrupted) {
                try {
                    if (KeepConnectedReload.isWatchdogDue()) {
                        io.github.rwpp.android.NetworkOnlyPump.duringReload(
                            KeepConnectedReload.WATCHDOG_DELTA, watchdog = true
                        )
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
        ModLoadSafety.run("room-reload") {
            val t = GameEngine.t()
            t.bW.d()
            t.bN.save()
            reloadUnitsWithSelection(enabledByFileName)
        }
    }

    /**
     * 重载内核：调用引擎扫描 mods 目录并重新加载。
     * 默认应在游戏主线程执行；forceImmediate 时为绕过主循环在调用线程直接执行。
     */
    private fun runReloadCore(enabledByFileName: Map<String, Boolean>?) {
        ModLoadSafety.run("local-reload") {
            val t = GameEngine.t()
            t.bW.d()
            t.bN.save()
            t.bo = true
            try {
                t.f()
                reloadUnitsWithSelection(enabledByFileName)
            } finally { t.bo = false }
            t.q()
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
        runOnGameThread { GameEngine.t().bW.k() }
    }

    override suspend fun modReregister() {
        auxiliaryReload("reregister") { GameEngine.t().bW.a(false, false) }
    }

    private suspend fun runOnGameThread(action: () -> Unit) {
        val claimed = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        game.post {
            if (claimed.compareAndSet(false, true)) {
                started.countDown()
                try { action() } catch (error: Throwable) { failure.set(error) }
                finally { done.countDown() }
            }
        }
        try {
            val consumed = withContext(Dispatchers.IO) { started.await(GAME_POST_START_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            if (!consumed && claimed.compareAndSet(false, true)) {
                // Android's existing offline fallback; never used by the room reload path.
                try { action() } catch (error: Throwable) { failure.set(error) }
                finally { done.countDown() }
            }
            withContext(Dispatchers.IO) { done.await() }
            failure.get()?.let { throw it }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // Cancel an unstarted task; once started, do not unlock the reload until parsing actually stops.
            if (!claimed.compareAndSet(false, true)) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { done.await() }
            }
            throw cancel
        }
    }

    private suspend fun auxiliaryReload(stage: String, action: () -> Unit) {
        io.github.rwpp.game.GameStartSafety.requireCanReload()
        check(isReloadingMods.compareAndSet(false, true)) { "模组仍在重载，请等待完成" }
        try { runOnGameThread { ModLoadSafety.run(stage, action) } }
        finally { isReloadingMods.set(false) }
    }

    override suspend fun modSaveChange(enabledByFileName: Map<String, Boolean>?) {
        auxiliaryReload("save-reload") {
            val t = GameEngine.t()
            t.bW.d()
            t.bN.save()
            reloadUnitsWithSelection(enabledByFileName)
        }
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

                    override fun writePayloadTo(output: java.io.OutputStream) {
                        val file = modFile()
                        if (file.isDirectory) file.zipFolderTo(output)
                        else file.inputStream().use { it.copyTo(output, 64 * 1024) }
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
