/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl

import com.corrodinggames.rts.game.units.custom.ag
import com.corrodinggames.rts.gameFramework.e.a
import io.github.rwpp.appKoin
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.ReloadModEvent
import io.github.rwpp.event.events.ReloadModFinishedEvent
import io.github.rwpp.game.Game
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.logger
import io.github.rwpp.game.mod.ModManager
import io.github.rwpp.game.mod.deleteModFileSafely
import io.github.rwpp.io.calculateSize
import io.github.rwpp.io.zipFolderToByte
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import org.koin.core.component.get
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

@Single
class ModManagerImpl : ModManager {
    private val game: Game = get()
    private val isReloadingMods = AtomicBoolean(false)

    override suspend fun modReload(forceImmediate: Boolean) {
        if (!isReloadingMods.compareAndSet(false, true)) {
            logger.info("[MODSYNC] modReload skipped: already reloading (forceImmediate=$forceImmediate)")
            return
        }
        try {
            logger.info("[MODSYNC] modReload start, broadcasting ReloadModEvent (forceImmediate=$forceImmediate)")
            ReloadModEvent().broadcastIn()
            if (forceImmediate) {
                // mod 同步专用：加入者仍在加载阶段、游戏主循环 i.b() 尚未启动，
                // game.post 投递的 action 永远不会被消费 -> 直接在当前线程同步执行重载。
                logger.info("[MODSYNC] modReload forceImmediate: running reload inline on current thread")
                runReloadCore()
                logger.info("[MODSYNC] modReload forceImmediate: reload core done, refreshing maps")
                appKoin.get<Game>().getAllMaps(true)
            } else {
                val latch = CountDownLatch(1)
                logger.info("[MODSYNC] modReload posting reload action to game thread")
                game.post {
                    logger.info("[MODSYNC] modReload game.post action RUNNING on game thread")
                    try {
                        runReloadCore()
                        logger.info("[MODSYNC] modReload game.post action DONE")
                    } catch (e: Throwable) {
                        logger.error("[MODSYNC] modReload game.post action THREW", e)
                        throw e
                    } finally {
                        latch.countDown()
                        logger.info("[MODSYNC] modReload latch counted down")
                    }
                }
                logger.info("[MODSYNC] modReload waiting for game thread (latch.await, NO timeout) ...")
                withContext(Dispatchers.IO) {
                    awaitGamePost(latch)
                    logger.info("[MODSYNC] modReload latch released, refreshing maps")
                    appKoin.get<Game>().getAllMaps(true)
                }
            }
            logger.info("[MODSYNC] modReload main work finished")
        } finally {
            logger.info("[MODSYNC] modReload broadcasting ReloadModFinishedEvent (finally)")
            ReloadModFinishedEvent().broadcastIn()
            isReloadingMods.set(false)
        }
    }

    /**
     * 重载内核：调用引擎扫描 mods 目录并重新加载。
     * 默认应在游戏主线程执行；forceImmediate 时为绕过主循环在调用线程直接执行。
     */
    private fun runReloadCore() {
        val t = GameEngine.t()
        t.bW.d()
        t.bN.save()
        val aVar = t.bW
        t.bo = true
        try {
            t.f()
            aVar.a(false, false)
        } finally {
            t.bo = false
        }
        t.q()
    }

    override suspend fun modUpdate() {
        val latch = CountDownLatch(1)
        game.post {
            GameEngine.t().bW.k()
            latch.countDown()
        }
        awaitGamePost(latch)
    }

    private suspend fun awaitGamePost(latch: CountDownLatch) {
        withContext(Dispatchers.IO) {
            latch.await()
        }
    }

    override suspend fun modSaveChange() {
        val latch = CountDownLatch(1)
        game.post {
            try {
                val t = GameEngine.t()
                t.bW.d()
                t.bN.save()
                val a2: Int = t.bW.a()
                if (!t.bU.C) {
                    if (ag.b(true) && a2 == 0) {
                        t.bW.b()
                    }
                }
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
                        val sourceFile = File(it.e())
                        if (sourceFile.exists()) return sourceFile

                        return File("/storage/emulated/0/" + a.q(it.g()).removePrefix("/SD/"))
                    }
                })
            }
        }
    }
}
