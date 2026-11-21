/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import io.github.rwpp.widget.UiLinearProgressIndicator
import io.github.rwpp.widget.animateUiFloatAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.swing.SkiaSwingLayer
import org.slf4j.LoggerFactory
import java.awt.Component
import java.awt.Container
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * 只在正式 App 组合之前运行。私有 Surface 同时预热鼠标输入、焦点/滚动与 Material 波纹，
 * 不向真实按钮或操作系统发送输入；进度条在最终菜单的同一个 scene/renderer 正常绘制。
 */
@Composable
internal fun DesktopMenuInputWarmup(
    message: String,
    inputComponent: () -> Component,
    onFinished: (Boolean) -> Unit,
    timeoutMillis: Long = 2_500L,
) {
    val log = remember { LoggerFactory.getLogger("DesktopMenuInputWarmup") }
    val interactions = remember { MutableInteractionSource() }
    val presses = remember { Channel<PressInteraction.Press>(Channel.CONFLATED) }
    val frames = remember { Channel<Int>(Channel.CONFLATED) }
    val active = remember { AtomicBoolean(true) }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current.density
    var bounds by remember { mutableStateOf<Rect?>(null) }
    var requestedFrame by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose {
            active.set(false)
            frames.close()
            presses.close()
        }
    }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { if (it is PressInteraction.Press) presses.trySend(it) }
    }
    LaunchedEffect(Unit) {
        // Compose 的 FlushCoroutineDispatcher 会在 sendPointerEvent 内同步 flush；预热控制器
        // 必须脱离该 dispatcher，否则一次本地事件可能重入尚未返回的绘制/效果协程。
        withContext(Dispatchers.Main) {
            val started = System.nanoTime()
            var target: Component? = null
            var held: PressInteraction.Press? = null
            suspend fun awaitDraw() {
                val token = ++requestedFrame
                while (frames.receive() < token) { /* 丢弃上一帧的确认。 */ }
            }
            fun mouse(id: Int, x: Int, y: Int, button: Int, down: Boolean = false) {
                check(SwingUtilities.isEventDispatchThread())
                val component = target?.takeIf { it.isShowing } ?: return
                component.dispatchEvent(MouseEvent(
                    component, id, System.currentTimeMillis(), if (down) InputEvent.BUTTON1_DOWN_MASK else 0,
                    x, y, if (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED) 1 else 0,
                    false, button,
                ))
            }
            fun releaseOutside() {
                mouse(MouseEvent.MOUSE_DRAGGED, -80, -80, MouseEvent.NOBUTTON, down = true)
                mouse(MouseEvent.MOUSE_EXITED, -80, -80, MouseEvent.NOBUTTON, down = true)
                mouse(MouseEvent.MOUSE_RELEASED, -80, -80, MouseEvent.BUTTON1)
            }
            val completed = try {
                withTimeout(timeoutMillis) {
                    awaitDraw()
                    target = checkNotNull(findMenuInputComponent(inputComponent())) { "Menu input surface unavailable" }
                    check(target.isShowing) { "Menu input surface is not showing" }
                    val center = checkNotNull(bounds) { "Menu warmup layout unavailable" }.center
                    val x = (center.x / density).roundToInt()
                    val y = (center.y / density).roundToInt()
                    mouse(MouseEvent.MOUSE_ENTERED, x, y, MouseEvent.NOBUTTON)
                    mouse(MouseEvent.MOUSE_MOVED, x, y, MouseEvent.NOBUTTON)
                    repeat(2) { awaitDraw() }
                    mouse(MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1, down = true)
                    held = presses.receive()
                    // 等实际内容绘制回调，不能用固定 sleep、零尺寸或 alpha=0 代替波纹首绘。
                    repeat(3) { awaitDraw() }
                    interactions.emit(PressInteraction.Cancel(held))
                    held = null
                    releaseOutside()
                    repeat(2) { awaitDraw() }
                    enabled = false
                    awaitDraw()
                    focusManager.clearFocus(force = true)
                    awaitDraw()
                }
                true
            } catch (failure: TimeoutCancellationException) {
                log.warn("[UI] Menu input warmup timed out; continuing startup")
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.warn("[UI] Menu input warmup unavailable; continuing startup", failure)
                false
            } finally {
                held?.let { interactions.tryEmit(PressInteraction.Cancel(it)) }
                runCatching { releaseOutside(); focusManager.clearFocus(force = true) }
            }
            currentCoroutineContext().ensureActive()
            if (active.get()) {
                log.info("[STARTUP] desktop.menuInputWarmup: {} ms, completed={}",
                    (System.nanoTime() - started) / 1_000_000, completed)
                onFinished(completed)
            }
        }
    }

    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val scale = animateUiFloatAsState(
        targetValue = when {
            pressed -> 0.982f
            hovered -> 1.012f
            else -> 1f
        },
        animationSpec = tween(150),
        label = "MenuInputWarmupScale",
    )

    DesktopStartupView(message, progressContent = {
        Surface(
            onClick = {}, enabled = enabled, interactionSource = interactions,
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth().height(48.dp)
                .graphicsLayer {
                    val s = scale.value
                    scaleX = s
                    scaleY = s
                }
                .pointerInput(Unit) {
                    // 正式页面也有 awaitPointerEventScope；一并预热其首次鼠标进入路径。
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial) }
                }
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                .drawWithContent {
                    val token = requestedFrame
                    drawContent()
                    // 必须在当前绘制返回之后再发下一次本地输入，避免重入 Compose 渲染。
                    SwingUtilities.invokeLater { if (active.get()) frames.trySend(token) }
                },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                UiLinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    })
}

/** 两种公开的 Skiko 输入承载组件，兼容原生菜单及 SwingGraphics/软件回退。 */
@OptIn(ExperimentalSkikoApi::class)
internal fun findMenuInputComponent(component: Component): Component? = when (component) {
    is SkiaLayer -> component.canvas
    is SkiaSwingLayer -> component
    is Container -> component.components.firstNotNullOfOrNull(::findMenuInputComponent)
    else -> null
}
