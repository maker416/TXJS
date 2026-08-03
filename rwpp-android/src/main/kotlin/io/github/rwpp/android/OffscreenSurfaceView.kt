/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android

import android.content.Context
import android.graphics.*
import android.graphics.Paint.ANTI_ALIAS_FLAG
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.graphics.ColorUtils
import io.github.rwpp.android.impl.GamePaintImpl
import io.github.rwpp.android.impl.OffscreenGameCanvasImpl
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.game.Game
import io.github.rwpp.game.units.comp.EntityRangeUnitComp
import io.github.rwpp.game.units.comp.EntityRangeUnitComp.Companion.drawRange
import io.github.rwpp.game.world.World
import kotlin.concurrent.thread

class OffscreenSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    @Volatile
    private var isRunning = false
    private var renderThread: Thread? = null

    private val renderer by lazy { RangeRenderer(width, height) }

    init {
        holder.addCallback(this)
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSPARENT)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // 攻击范围显示功能已下线（Settings.migrate 每次加载强制关闭）：功能未启用时
        // 不启动渲染线程，避免忙等空转耗电，以及 surface 销毁竞态导致的未捕获异常闪退
        if (!isAttackRangeEnabled()) return
        isRunning = true
        renderThread = thread(start = true, name = "CircleRenderThread") {
            renderLoop()
        }
    }

    private fun isAttackRangeEnabled(): Boolean {
        val settings = runCatching { appKoin.get<Settings>() }.getOrNull() ?: return false
        return settings.showBuildingAttackRange || settings.showAttackRangeUnit != "Never"
    }

    private fun renderLoop() {
        while (isRunning) {
            try {
                // surface 销毁/页面切换（如对局中打开设置页）时 lockHardwareCanvas 会抛
                // IllegalStateException，必须放进捕获范围，否则渲染线程的未捕获异常会
                // 触发全局 handler 直接杀掉进程（闪退）
                val canvas = holder.lockHardwareCanvas() ?: continue
                try {
                    renderer.render(canvas)
                } finally {
                    // surface 半毁状态下 unlockCanvasAndPost 同样可能抛异常，不能让异常
                    // 从 finally 中逃逸出捕获范围
                    runCatching { holder.unlockCanvasAndPost(canvas) }
                }
                // 帧节流：无 sleep 的忙等循环会占满 CPU，并极大放大 surface 竞态窗口
                Thread.sleep(16)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, he: Int) {
    }


    override fun surfaceDestroyed(h: SurfaceHolder) {
        isRunning = false
        // 渲染线程可能仍阻塞在 lockHardwareCanvas 内部，无超时 join 会拖死 UI 线程（ANR）
        renderThread?.join(1000)
        renderThread = null
    }

    class RangeRenderer(val width: Int, val height: Int) {
        private val teamBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val teamCanvas = Canvas(teamBitmap)

        // 2. 总输出层：用于存放所有队伍叠加后的结果
        private val finalBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val finalCanvas = Canvas(finalBitmap)

        private var gameCanvas: OffscreenGameCanvasImpl = OffscreenGameCanvasImpl(teamCanvas)

        private val circlePaint = GamePaintImpl(Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            // 不需要特殊的 Xfermode，因为我们要的是覆盖效果
        })

        private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // SRC_IN: 只有在 teamCanvas 有圆的地方才染上队色
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }

        fun render(mainCanvas: Canvas) = with(EntityRangeUnitComp) {
            // A. 先清空“最终成品图”
            finalCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            beforeDrawRange()

            layerGroups.forEach { (team, units) ->
                // B. 处理这一支队伍
                // 1. 清空当前队伍的临时层
                teamCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // 2. 在临时层画圆
                teamCanvas.save()
                teamCanvas.scale(world.gameScale, world.gameScale)
                units.forEach { unit ->
                    gameCanvas.drawRange(unit, circlePaint, unit.maxAttackRange)
                }
                teamCanvas.restore()

                // 3. 给当前队伍上色 (此时 teamBitmap 里是这一队带透明度的圆)
                tintPaint.color = getTeamPaint(team).argb
                teamCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)

                // 4. 重要：把这一队的结果“合并”到总输出层
                // 使用默认混合模式 (SRC_OVER)，这样不同队伍的圈会叠在一起
                finalCanvas.drawBitmap(teamBitmap, 0f, 0f, null)
            }

            // C. 最后：一次性把所有队伍的结果画到主屏幕
            // 先清理主屏幕，再画成品
            mainCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            mainCanvas.drawBitmap(finalBitmap, 0f, 0f, null)
        }
    }
}