/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

// Rare UI folder-component black 主题配色
private val BackColor = Color.Black
private val BackInsetGlow = Color.White.copy(alpha = 0.37f)
private val FlapFill = Color(0xFF292929).copy(alpha = 0.25f)
private val FlapStroke = Color(0xFF979797)
private val CardFill = Color(0xFFF1F1F1)
private val CardStroke = Color(0xFFE0E0E0)
private val CardLine = Color(0xFFD4D4D4)

// Rare: cards spring(stiffness 120, damping 13) → ζ≈0.59; flap damping 14 → ζ≈0.64
private val CardSpring = spring<Float>(stiffness = 120f, dampingRatio = 0.59f)
private val FlapSpring = spring<Float>(stiffness = 120f, dampingRatio = 0.64f)

private enum class FolderState { Idle, Hover, Open }

/**
 * Rare UI folder-component 等比移植，scale = 109/321 ≈ 0.34：
 * 画布 321×270 → 109×92，前盖 321×241 → 109×82（中心下移 mt-4×0.34），卡片 163×213 → 55×72。
 */
private object ModDropFolderMetrics {
    val outerW = 162.dp
    val outerH = 118.dp
    val canvasW = 109.dp
    val canvasH = 92.dp
    val flapW = 109.dp
    val flapH = 82.dp
    val flapCenterOffsetY = 5.dp
    val cardW = 55.dp
    val cardH = 72.dp
    val nearRadius = 100.dp

    val flapRotationIdle = -15f
    val flapRotationHover = -45f
    val flapRotationOpen = -55f
}

private data class CardPose(val x: Float, val y: Float, val rot: Float)

private data class CardAnim(
    val idle: CardPose,
    val hover: CardPose,
    val open: CardPose,
    val hoverDelayMs: Int,
    val openDelayMs: Int,
)

// Rare 卡片动画（md 坐标 × 0.34）：1 右 → 2 中 → 3 左；hover/open 依次错峰，收起同时回落
private val cardAnims = listOf(
    CardAnim( // Rare 卡1：x 40→40→70，y -10→-30→-160，rot 10→14→18
        idle = CardPose(13.6f, -3.4f, 10f),
        hover = CardPose(13.6f, -10.2f, 14f),
        open = CardPose(23.8f, -54.4f, 18f),
        hoverDelayMs = 120, openDelayMs = 100,
    ),
    CardAnim( // Rare 卡2：x 3→3→0，y -20→-35→-180，rot 2→-1→-3
        idle = CardPose(1f, -6.8f, 2f),
        hover = CardPose(1f, -11.9f, -1f),
        open = CardPose(0f, -61.2f, -3f),
        hoverDelayMs = 60, openDelayMs = 50,
    ),
    CardAnim( // Rare 卡3：x -40→-40→-65，y -22→-44→-170，rot -5→-9→-14
        idle = CardPose(-13.6f, -7.5f, -5f),
        hover = CardPose(-13.6f, -15f, -9f),
        open = CardPose(-22.1f, -57.8f, -14f),
        hoverDelayMs = 0, openDelayMs = 0,
    ),
)

@Composable
fun ModDropOverlay(
    visible: Boolean,
    dragPointerInRoot: Offset,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val density = LocalDensity.current
    val nearRadiusPx = remember(density) {
        with(density) { ModDropFolderMetrics.nearRadius.toPx() }
    }
    val overHalfPx = remember(density) {
        with(density) {
            Offset(
                (ModDropFolderMetrics.outerW / 2 + 8.dp).toPx(),
                (ModDropFolderMetrics.outerH / 2 + 8.dp).toPx(),
            )
        }
    }
    var folderCenterInRoot by remember { mutableStateOf(Offset.Zero) }
    var overlayOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var localPointer by remember { mutableStateOf(Offset.Unspecified) }

    val pointerInRoot = remember(dragPointerInRoot, localPointer, overlayOriginInRoot) {
        when {
            dragPointerInRoot != Offset.Unspecified -> dragPointerInRoot
            localPointer != Offset.Unspecified -> overlayOriginInRoot + localPointer
            else -> Offset.Unspecified
        }
    }

    val pointerNearFolder = remember(pointerInRoot, folderCenterInRoot, nearRadiusPx) {
        if (pointerInRoot == Offset.Unspecified || folderCenterInRoot == Offset.Zero) {
            false
        } else {
            hypot(
                (pointerInRoot.x - folderCenterInRoot.x).toDouble(),
                (pointerInRoot.y - folderCenterInRoot.y).toDouble(),
            ) <= nearRadiusPx
        }
    }
    val pointerOverFolder = remember(pointerInRoot, folderCenterInRoot, overHalfPx) {
        pointerInRoot != Offset.Unspecified && folderCenterInRoot != Offset.Zero &&
            abs(pointerInRoot.x - folderCenterInRoot.x) <= overHalfPx.x &&
            abs(pointerInRoot.y - folderCenterInRoot.y) <= overHalfPx.y
    }
    val folderState = when {
        pointerOverFolder -> FolderState.Open
        pointerNearFolder -> FolderState.Hover
        else -> FolderState.Idle
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOriginInRoot = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull() ?: continue
                        localPointer = change.position
                    }
                }
            }
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(ModDropFolderMetrics.outerW, ModDropFolderMetrics.outerH)
                    .onGloballyPositioned { coordinates ->
                        folderCenterInRoot = coordinates.positionInRoot() +
                            Offset(coordinates.size.width / 2f, coordinates.size.height / 2f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                ModDropFolderGraphic(folderState)
            }

            Spacer(Modifier.height(20.dp))
            Text(
                readI18n("mod.dropHint", I18nType.RWPP),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Rare 叠放顺序：背板 → 卡片（1右 2中 3左）→ 前盖。 */
@Composable
private fun ModDropFolderGraphic(state: FolderState) {
    val m = ModDropFolderMetrics
    val density = LocalDensity.current

    val flapRotation by animateFloatAsState(
        targetValue = when (state) {
            FolderState.Idle -> m.flapRotationIdle
            FolderState.Hover -> m.flapRotationHover
            FolderState.Open -> m.flapRotationOpen
        },
        animationSpec = FlapSpring,
        label = "modDropFlap",
    )

    Box(
        modifier = Modifier.size(m.canvasW, m.canvasH),
        contentAlignment = Alignment.Center,
    ) {
        // 背板：黑色圆角板 + 内侧白色微光（Rare backInsetShadow）
        Canvas(Modifier.fillMaxSize()) {
            val r = 8.dp.toPx()
            drawRoundRect(BackColor, cornerRadius = CornerRadius(r))
            val inset = 1.dp.toPx()
            drawRoundRect(
                BackInsetGlow.copy(alpha = 0.3f),
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                cornerRadius = CornerRadius(r - inset),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        cardAnims.forEach { card ->
            val pose = animatedCardPose(card, state)
            ModDropCard(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            pose.x.dp.toPx().roundToInt(),
                            pose.y.dp.toPx().roundToInt(),
                        )
                    }
                    .graphicsLayer { rotationZ = pose.rot },
            )
        }

        ModDropFlap(
            modifier = Modifier
                .offset(y = m.flapCenterOffsetY)
                .graphicsLayer {
                    rotationX = flapRotation
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    cameraDistance = 14f * density.density
                },
        )
    }
}

/** Rare 前盖：FLAP_PATH 等比移植（左高标签 + S 曲线过渡 + 右低肩，底边平直）。 */
@Composable
private fun ModDropFlap(modifier: Modifier = Modifier) {
    val m = ModDropFolderMetrics
    val density = LocalDensity.current
    val path = remember(density) {
        with(density) { flapPath(m.flapW.toPx(), m.flapH.toPx()) }
    }
    Canvas(modifier.size(m.flapW, m.flapH)) {
        drawPath(path, FlapFill)
        drawPath(path, FlapStroke, style = Stroke(width = 1.dp.toPx()))
    }
}

private fun flapPath(w: Float, h: Float): Path {
    val sx = w / 321f
    val sy = h / 241f
    fun x(v: Float) = v * sx
    fun y(v: Float) = v * sy
    return Path().apply {
        moveTo(x(0f), y(25f))
        cubicTo(x(0f), y(11.1929f), x(11.1929f), y(0f), x(25f), y(0f))
        lineTo(x(136.084f), y(0f))
        cubicTo(x(143.044f), y(0f), x(149.689f), y(2.90139f), x(154.42f), y(8.00608f))
        lineTo(x(178.08f), y(33.5343f))
        cubicTo(x(182.811f), y(38.639f), x(189.456f), y(41.5404f), x(196.416f), y(41.5404f))
        lineTo(x(296f), y(41.5404f))
        cubicTo(x(309.807f), y(41.5404f), x(321f), y(52.7333f), x(321f), y(66.5404f))
        lineTo(x(321f), y(216f))
        cubicTo(x(321f), y(229.807f), x(309.807f), y(241f), x(296f), y(241f))
        lineTo(x(25f), y(241f))
        cubicTo(x(11.1929f), y(241f), x(0f), y(229.807f), x(0f), y(216f))
        close()
    }
}

/** 带错峰延迟的弹簧动画：进入 Hover/Open 按卡延迟，回落（Idle）立即。 */
@Composable
private fun animatedFloat(targetValue: Float, delayMs: Int, spec: AnimationSpec<Float>): Float {
    val animatable = remember { Animatable(targetValue) }
    LaunchedEffect(targetValue, delayMs) {
        if (delayMs > 0 && targetValue != animatable.value) {
            delay(delayMs.toLong())
        }
        animatable.animateTo(targetValue, spec)
    }
    return animatable.value
}

@Composable
private fun animatedCardPose(card: CardAnim, state: FolderState): CardPose {
    val (target, delayMs) = when (state) {
        FolderState.Idle -> card.idle to 0
        FolderState.Hover -> card.hover to card.hoverDelayMs
        FolderState.Open -> card.open to card.openDelayMs
    }
    val x = animatedFloat(target.x, delayMs, CardSpring)
    val y = animatedFloat(target.y, delayMs, CardSpring)
    val rot = animatedFloat(target.rot, delayMs, CardSpring)
    return CardPose(x, y, rot)
}

/** Rare 卡片：163×213 圆角 20，顶部标题条 + 9 行双列文字条（× scale）。 */
@Composable
private fun ModDropCard(modifier: Modifier = Modifier) {
    val m = ModDropFolderMetrics
    Canvas(modifier.size(m.cardW, m.cardH)) {
        val w = size.width
        val s = w / 163.078f
        val rx = 20f * s
        drawRoundRect(CardFill, cornerRadius = CornerRadius(rx))
        drawRoundRect(
            CardStroke,
            topLeft = Offset(0.5f, 0.5f),
            size = Size(w - 1f, size.height - 1f),
            cornerRadius = CornerRadius(rx),
            style = Stroke(width = 1.dp.toPx()),
        )
        // 标题条：x14.12 y31.21 134.84×11.89 rx5.94
        drawRoundRect(
            CardLine,
            topLeft = Offset(14.12f * s, 31.21f * s),
            size = Size(134.84f * s, 11.89f * s),
            cornerRadius = CornerRadius(5.94f * s),
        )
        // 9 行双列：y 60.99 + 14.118n，x 14.83 / 84.43，64.52×5.88 rx2.94
        repeat(9) { row ->
            val y = (60.99f + row * 14.118f) * s
            listOf(14.83f, 84.43f).forEach { x ->
                drawRoundRect(
                    CardLine,
                    topLeft = Offset(x * s, y),
                    size = Size(64.52f * s, 5.88f * s),
                    cornerRadius = CornerRadius(2.94f * s),
                )
            }
        }
    }
}