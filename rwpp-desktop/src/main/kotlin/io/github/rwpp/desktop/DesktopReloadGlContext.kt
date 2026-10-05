/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import io.github.rwpp.logger
import org.lwjgl.opengl.Drawable
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GLContext
import org.lwjgl.opengl.SharedDrawable
import org.newdawn.slick.opengl.TextureImpl

/** 工作线程只绑定共享上下文；主窗口的上下文始终归游戏线程所有。 */
object DesktopReloadGlContext {
    private val lock = Any()
    @Volatile private var owner: Thread? = null
    private var shared: SharedDrawable? = null

    fun initialize(drawable: Drawable) = synchronized(lock) {
        check(owner == null) { "PC reload GL context already initialized" }
        GLContext.getCapabilities() // 初始化必须发生在持有原版 GL 上下文的线程。
        owner = Thread.currentThread()
        logger.info("[GL] vendor={} renderer={} version={}",
            GL11.glGetString(GL11.GL_VENDOR), GL11.glGetString(GL11.GL_RENDERER), GL11.glGetString(GL11.GL_VERSION))
        try {
            shared = SharedDrawable(drawable)
            logger.info("[GL] shared mod reload context initialized on {}", owner?.name)
        } catch (e: Exception) {
            // 原版游戏仍可运行；房内同步明确报错，不能退化为无上下文解析。
            logger.error("[GL] failed to create shared mod reload context", e)
        }
    }

    fun isOwnerThread(): Boolean = Thread.currentThread() === owner

    fun requireOwnerContext() {
        check(isOwnerThread()) { "PC engine reload must run on the OpenGL game thread" }
        GLContext.getCapabilities()
    }

    fun <T> withSharedContext(action: () -> T): T = synchronized(lock) {
        if (isOwnerThread()) return@synchronized action()
        val context = checkNotNull(shared) { "PC 无法创建共享 OpenGL 上下文，不能在房间内重载模组。" }
        context.makeCurrent()
        try {
            action()
        } finally {
            try {
                GL11.glFlush() // 将新纹理提交给主窗口共享组。
                TextureImpl.bindNone()
            } finally {
                context.releaseContext()
            }
        }
    }

    fun resetOwnerTextureBinding() {
        requireOwnerContext()
        // Slick 的 lastBind 是静态缓存，切换上下文后必须在主线程重新建立绑定。
        TextureImpl.bindNone()
    }

    fun close() = synchronized(lock) {
        shared?.destroy()
        shared = null
    }
}
