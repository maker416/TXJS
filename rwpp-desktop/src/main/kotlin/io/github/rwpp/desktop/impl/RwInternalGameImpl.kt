/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl

import com.corrodinggames.rts.java.u
import io.github.rwpp.desktop.DesktopMenuBackgroundEngine
import io.github.rwpp.desktop.GameEngine
import io.github.rwpp.desktop.isGaming
import org.newdawn.slick.GameContainer
import org.newdawn.slick.Graphics
import io.github.rwpp.logger
import org.lwjgl.input.Mouse

class RwInternalGameImpl(str: String) : u(str) {
    private var lastUpdateFailure: String? = null
    private var lastUpdateFailureAt = 0L
    private var frameDeltaMillis = 0
    private val pressedMouseButtons = mutableSetOf<Int>()
    private var menuInputReleased = false

    // The original wrapper draws its own pointer when v is true, independently of AWT focus.
    private val mouseCaptureField by lazy {
        u::class.java.getDeclaredField("v").apply { isAccessible = true }
    }

    override fun render(container: GameContainer?, graphics: Graphics?) {
        if (!isGaming) {
            if (!menuInputReleased) {
                // A key/button held when returning to the launcher must not remain held on Continue.
                super.f()
                pressedMouseButtons.toList().forEach { super.mouseReleased(it, 0, 0) }
                pressedMouseButtons.clear()
                menuInputReleased = true
            }
            releaseMenuMouse(container)
        } else {
            menuInputReleased = false
        }
        // 原版在 render 内推进模拟；隐藏背景只停演示，不停止容器的 post 队列或真实网络会话。
        if (DesktopMenuBackgroundEngine.prepareFrame()) {
            super.render(container, graphics)
        } else {
            DesktopMenuBackgroundEngine.pumpIdleCallbacks(frameDeltaMillis)
        }
        DesktopMenuBackgroundEngine.finishFrame(container)
    }

    override fun a() {
        super.a()
        if (!isGaming) releaseMenuMouse(null)
    }

    private fun releaseMenuMouse(container: GameContainer?) {
        mouseCaptureField.setBoolean(this, false)
        if (Mouse.isCreated() && Mouse.isGrabbed()) {
            if (container != null) container.setMouseGrabbed(false) else Mouse.setGrabbed(false)
        }
        GameEngine.aR = false
    }

    override fun keyPressed(key: Int, character: Char) {
        if (isGaming) super.keyPressed(key, character)
    }

    override fun keyReleased(key: Int, character: Char) {
        if (isGaming || super.b(key)) super.keyReleased(key, character)
    }

    override fun a(key: Int, pressed: Boolean) {
        if (isGaming || !pressed) super.a(key, pressed)
    }

    override fun mousePressed(button: Int, x: Int, y: Int) {
        if (!isGaming) return
        pressedMouseButtons.add(button)
        super.mousePressed(button, x, y)
    }

    override fun mouseReleased(button: Int, x: Int, y: Int) {
        val wasPressed = pressedMouseButtons.remove(button)
        if (isGaming || wasPressed) super.mouseReleased(button, x, y)
    }

    override fun mouseMoved(oldX: Int, oldY: Int, newX: Int, newY: Int) {
        if (isGaming) super.mouseMoved(oldX, oldY, newX, newY)
    }

    override fun mouseDragged(oldX: Int, oldY: Int, newX: Int, newY: Int) {
        if (isGaming) super.mouseDragged(oldX, oldY, newX, newY)
    }

    override fun mouseWheelMoved(change: Int) {
        if (isGaming) super.mouseWheelMoved(change)
    }

    override fun a(x: Int, y: Int, relative: Boolean) {
        if (isGaming) super.a(x, y, relative)
    }

    override fun a(x: Float, y: Float) {
        if (isGaming) super.a(x, y)
    }

    override fun update(p0: GameContainer?, p1: Int) {
        try {
            frameDeltaMillis = p1
            super.update(p0, p1)
        } catch (e: Exception) {
            val failure = "${e.javaClass.name}:${e.message}"
            val now = System.currentTimeMillis()
            if (failure != lastUpdateFailure || now - lastUpdateFailureAt >= 60_000L) {
                logger.error("[GAME] update failed on thread=${Thread.currentThread().name}", e)
                lastUpdateFailure = failure
                lastUpdateFailureAt = now
            }
        }
    }


    override fun b() {
    }


//    override fun keyPressed(p0: Int, p1: Char) {
//        l.B()?.b(SlickToAndroidKeycodes.b(p0), true)
//    }
//
//    override fun keyReleased(p0: Int, p1: Char) {
//        // 暂时不实现
//    }
}
