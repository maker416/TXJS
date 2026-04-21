/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import com.corrodinggames.librocket.scripts.ScriptEngine
import io.github.rwpp.AppContext
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.desktop.GameEngine
import io.github.rwpp.graphics.GL
import io.github.rwpp.impl.BaseAppContextImpl
import org.koin.core.annotation.Single
import org.koin.core.component.get
@Single([AppContext::class])
class AppContextImpl : BaseAppContextImpl() {
    private val exitActions = mutableListOf<() -> Unit>()
    @Volatile private var exiting = false


    override fun onExit(action: () -> Unit) {
        exitActions.add(action)
    }

    override fun isAndroid(): Boolean = false

    override fun isDesktop(): Boolean = true
    override fun externalStoragePath(path: String): String {
        return System.getProperty("user.dir") + "/$path"
    }

    override fun init() {
        super.init()
        GL.gameCanvas = GameCanvasImpl()
    }

    /**
     * Shut the desktop client down without blocking the calling thread (typically the
     * AWT Event Dispatch Thread when this is invoked from the main window's close dialog).
     *
     * Why this looks the way it does:
     * - `System.exit()` / `exitProcess()` acquire the single `Runtime` exit lock and run
     *   shutdown hooks/finalizers/AWT teardown. When triggered from the EDT while Swing
     *   and Slick2D rendering threads are still alive, JVM shutdown regularly deadlocks,
     *   leaving the UI frozen even though the game engine already logged a clean
     *   `gameThread already null`.
     * - `Runtime.halt(0)` is a hard abort that bypasses all of that. We only use the
     *   graceful engine shutdown path best-effort, and always arm a daemon watchdog
     *   that halts the VM within a short grace period regardless of outcome.
     */
    override fun exit() {
        if (exiting) return
        exiting = true

        runCatching {
            GameEngine.B().bO
            val configIO: ConfigIO = get()
            GameEngine.B().bQ.apply {
                numLoadsSinceRunningGameOrNormalExit = 0
                numIncompleteLoadAttempts = 0
            }
            configIO.saveAllConfig()
        }
        runCatching { exitActions.forEach { it.invoke() } }

        Thread {
            runCatching { ScriptEngine.getInstance().root.exit() }
        }.apply { isDaemon = true; name = "rwpp-engine-shutdown" }.start()

        Thread {
            runCatching { Thread.sleep(1000) }
            Runtime.getRuntime().halt(0)
        }.apply { isDaemon = true; name = "rwpp-exit-watchdog" }.start()
    }
}