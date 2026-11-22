/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.widget.uiAnimationsEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val MASTER_CYCLE_MS = 12_000
private const val TIP_ROTATE_INTERVAL_MS = 3_400L
private const val OUTER_TIP_ANGLE_DEG = 346f
private const val OUTER_TIP_COS = 0.9702957f
private const val OUTER_TIP_SIN = -0.2419219f
private val RefreshSpinEasing = CubicBezierEasing(0.42f, 0.0f, 0.2f, 1.0f)

private data class SignalBlip(
    val angleDeg: Float,
    val radiusFraction: Float,
)

private val BeaconBlips = listOf(
    SignalBlip(angleDeg = 34f, radiusFraction = 0.54f),
    SignalBlip(angleDeg = 138f, radiusFraction = 0.74f),
    SignalBlip(angleDeg = 228f, radiusFraction = 0.62f),
    SignalBlip(angleDeg = 314f, radiusFraction = 0.72f),
)

private data class SkeletonCardBlueprint(
    val titleFraction: Float,
    val badgeWidths: List<Float>,
    val highlightSecondBadge: Boolean,
    val mapFraction: Float,
)

private val SkeletonBlueprints = listOf(
    SkeletonCardBlueprint(0.68f, listOf(46f, 68f, 44f), highlightSecondBadge = true, mapFraction = 0.48f),
    SkeletonCardBlueprint(0.54f, listOf(52f, 42f), highlightSecondBadge = false, mapFraction = 0.58f),
    SkeletonCardBlueprint(0.74f, listOf(44f, 66f, 52f), highlightSecondBadge = true, mapFraction = 0.42f),
    SkeletonCardBlueprint(0.60f, listOf(58f, 48f), highlightSecondBadge = false, mapFraction = 0.52f),
    SkeletonCardBlueprint(0.70f, listOf(46f, 64f), highlightSecondBadge = true, mapFraction = 0.44f),
    SkeletonCardBlueprint(0.50f, listOf(50f, 44f, 40f), highlightSecondBadge = false, mapFraction = 0.60f),
    SkeletonCardBlueprint(0.64f, listOf(48f, 66f), highlightSecondBadge = true, mapFraction = 0.46f),
    SkeletonCardBlueprint(0.58f, listOf(54f, 42f), highlightSecondBadge = false, mapFraction = 0.54f),
)

/**
 * 房间列表加载阶段共享的单一谐波时钟（12s 整周期无缝循环）。
 * 禁用动效或不在加载阶段时返回 null，无限动画立即退出组合，不调度任何帧。
 */
@Composable
internal fun rememberRoomListLoadingPhase(active: Boolean): State<Float>? {
    if (!active || !uiAnimationsEnabled()) return null
    val transition = rememberInfiniteTransition(label = "roomListLoadingMaster")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = MASTER_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "roomListLoadingMasterPhase",
    )
}

/**
 * 将「战术信标舞台 + 与真实网格等列对齐的流体骨架卡片」直接嵌入房间列表 [LazyColumn]，
 * 自动继承顶栏动态内边距与底部按钮留白，且不会破坏外部列表滚动条状态。
 */
internal fun LazyListScope.roomListLoadingStage(
    columns: Int,
    phase: State<Float>?,
    compactSkeletonRows: Boolean,
    isDesktop: Boolean,
    animate: Boolean,
) {
    val safeColumns = columns.coerceAtLeast(1)
    val rowCount = when {
        compactSkeletonRows -> 1
        safeColumns == 1 -> 4
        else -> 3
    }

    item(key = "room-list-loading-hero", contentType = "room-list-loading-hero") {
        RoomListLoadingHero(
            phase = phase,
            isDesktop = isDesktop,
            modifier = Modifier
                .then(
                    if (animate) {
                        Modifier.animateItem(
                            fadeInSpec = tween(220, easing = StandardDecelerateEasing),
                            fadeOutSpec = tween(200, easing = EmphasizedAccelerateEasing),
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }

    items(
        count = rowCount,
        key = { "room-list-skeleton-row:$it" },
        contentType = { "room-list-skeleton-row" },
    ) { rowIndex ->
        val rowDepthAlpha = when (rowIndex) {
            0 -> 1.0f
            1 -> 0.84f
            2 -> 0.60f
            else -> 0.40f
        }
        RoomListSkeletonRow(
            rowIndex = rowIndex,
            columns = safeColumns,
            phase = phase,
            rowAlpha = rowDepthAlpha,
            modifier = if (animate) {
                Modifier.animateItem(
                    fadeInSpec = tween(240, easing = StandardDecelerateEasing),
                    fadeOutSpec = tween(180, easing = EmphasizedAccelerateEasing),
                )
            } else {
                Modifier
            },
        )
    }
}

/**
 * 顶栏底部 2dp 柔光扫描线程：首次加载与每 5 秒后台静默刷新共用，
 * 既传达实时同步反馈，又不打断已加载房间的点击与浏览。
 */
@Composable
internal fun MultiplayerTopBarRefreshLine(
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val animated = uiAnimationsEnabled()
    AnimatedVisibility(
        visible = isRefreshing,
        modifier = modifier.fillMaxWidth().height(2.dp),
        enter = if (animated) fadeIn(tween(180, easing = StandardDecelerateEasing)) else EnterTransition.None,
        exit = if (animated) fadeOut(tween(260, easing = EmphasizedAccelerateEasing)) else ExitTransition.None,
    ) {
        val colors = MaterialTheme.colorScheme
        val primary = colors.primary
        val secondary = colors.secondary
        val highlight = remember(primary, colors.onSurface) { lerp(primary, colors.onSurface, 0.35f) }
        val progress = if (animated) {
            rememberInfiniteTransition(label = "topBarRefreshSweep").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1_750, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "topBarRefreshSweepOffset",
            )
        } else {
            null
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
                .testTag("multiplayer-topbar-refresh-line")
                .drawWithCache {
                    val cometWidth = (size.width * 0.40f).coerceAtLeast(72.dp.toPx())
                    val cometBrush = Brush.horizontalGradient(
                        0.00f to Color.Transparent,
                        0.28f to primary.copy(alpha = 0.32f),
                        0.68f to secondary.copy(alpha = 0.88f),
                        0.88f to highlight.copy(alpha = 0.96f),
                        1.00f to Color.Transparent,
                        startX = 0f,
                        endX = cometWidth,
                    )
                    val trackColor = primary.copy(alpha = 0.12f)
                    val staticBrush = Brush.horizontalGradient(
                        0.0f to Color.Transparent,
                        0.25f to primary.copy(alpha = 0.72f),
                        0.75f to secondary.copy(alpha = 0.72f),
                        1.0f to Color.Transparent,
                    )
                    onDrawBehind {
                        drawRect(trackColor)
                        if (progress != null) {
                            val eased = FastOutSlowInEasing.transform(progress.value)
                            val startX = -cometWidth + (size.width + cometWidth) * eased
                            withTransform({ translate(left = startX) }) {
                                drawRect(
                                    brush = cometBrush,
                                    topLeft = Offset.Zero,
                                    size = Size(cometWidth, size.height),
                                )
                            }
                        } else {
                            drawRect(brush = staticBrush)
                        }
                    }
                },
        )
    }
}

/**
 * 顶部刷新按钮内部的柔滑动态图标：刷新中不再突兀替换成厚重转圈，
 * 而是由原刷新图标平滑缓动旋转并环绕细微极光弧；关闭动效时保持静态零帧请求。
 */
@Composable
internal fun RefreshActionIndicator(
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
) {
    val animated = uiAnimationsEnabled()
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val secondary = colors.secondary
    val activeTint = if (isRefreshing) primary else colors.surfaceTint

    val rotation = if (isRefreshing && animated) {
        rememberInfiniteTransition(label = "refreshButtonSpin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_100, easing = RefreshSpinEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "refreshButtonAngle",
        )
    } else {
        null
    }

    val ringBrush = remember(primary, secondary) {
        Brush.sweepGradient(
            0.00f to Color.Transparent,
            0.35f to Color.Transparent,
            0.70f to secondary.copy(alpha = 0.45f),
            0.94f to primary.copy(alpha = 0.92f),
            0.99f to Color.Transparent,
            1.00f to Color.Transparent,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (isRefreshing) {
                    Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isRefreshing) {
            Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                val strokePx = 2.dp.toPx()
                val arcSize = Size(
                    (size.width - strokePx).coerceAtLeast(0f),
                    (size.height - strokePx).coerceAtLeast(0f),
                )
                val arcTopLeft = Offset(strokePx / 2f, strokePx / 2f)
                drawCircle(
                    color = primary.copy(alpha = 0.14f),
                    radius = arcSize.minDimension / 2f,
                    style = Stroke(strokePx),
                )
                if (rotation != null) {
                    rotate(degrees = rotation.value, pivot = center) {
                        drawArc(
                            brush = ringBrush,
                            startAngle = 120f,
                            sweepAngle = 220f,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(strokePx, cap = StrokeCap.Round),
                        )
                    }
                } else {
                    drawArc(
                        color = primary.copy(alpha = 0.85f),
                        startAngle = -90f,
                        sweepAngle = 250f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(strokePx, cap = StrokeCap.Round),
                    )
                }
            }
        }

        Icon(
            Icons.Default.Refresh,
            contentDescription = null,
            tint = activeTint,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    rotationZ = rotation?.value ?: 0f
                },
        )
    }
}

@Composable
internal fun RoomListLoadingHero(
    phase: State<Float>?,
    isDesktop: Boolean,
    modifier: Modifier = Modifier,
) {
    val animated = uiAnimationsEnabled() && phase != null
    val colors = MaterialTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val primary = colors.primary
    val secondary = colors.secondary
    val onSurface = colors.onSurface

    val hints = remember(isDesktop) {
        buildList {
            add(readI18n("multiplayer.roomList.loadingHintConnect"))
            add(readI18n("multiplayer.roomList.loadingHintPreview"))
            add(readI18n("multiplayer.roomList.loadingHintModSync"))
            add(readI18n("multiplayer.roomList.loadingHintFilter"))
            if (isDesktop) {
                add(readI18n("multiplayer.roomList.loadingHintShortcut"))
            }
            add(readI18n("multiplayer.roomList.loadingHintHost"))
        }
    }
    var hintIndex by remember(hints) { mutableIntStateOf(0) }
    LaunchedEffect(animated, hints) {
        hintIndex = 0
        if (!animated || hints.size <= 1) return@LaunchedEffect
        while (true) {
            delay(TIP_ROTATE_INTERVAL_MS)
            hintIndex = (hintIndex + 1) % hints.size
        }
    }

    val shape = RoundedCornerShape(18.dp)
    val cardContainer = colors.surface.copy(alpha = if (isDark) 0.90f else 0.95f)
    val borderColor = colors.outlineVariant.copy(alpha = if (isDark) 0.42f else 0.55f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardContainer)
            .drawWithCache {
                val leftCenter = Offset(size.width * 0.14f, size.height * 0.48f)
                val leftRadius = (size.maxDimension * 0.44f).coerceAtLeast(120.dp.toPx())
                val leftGlowBrush = Brush.radialGradient(
                    colors = listOf(primary, Color.Transparent),
                    center = leftCenter,
                    radius = leftRadius,
                )
                val rightCenter = Offset(size.width * 0.88f, size.height * 0.25f)
                val rightRadius = (size.maxDimension * 0.36f).coerceAtLeast(96.dp.toPx())
                val rightGlowBrush = Brush.radialGradient(
                    colors = listOf(secondary, Color.Transparent),
                    center = rightCenter,
                    radius = rightRadius,
                )
                val topLineStart = Offset(20.dp.toPx(), 1.dp.toPx())
                val topLineEnd = Offset((size.width - 20.dp.toPx()).coerceAtLeast(topLineStart.x), 1.dp.toPx())
                val topLineBrush = Brush.horizontalGradient(
                    0.0f to Color.Transparent,
                    0.26f to primary,
                    0.68f to secondary,
                    1.0f to Color.Transparent,
                    startX = topLineStart.x,
                    endX = topLineEnd.x,
                )
                val topStroke = 1.dp.toPx()
                val gridStep = 14.dp.toPx()
                val canvasWidth = size.width
                val canvasHeight = size.height
                val gridStartX = canvasWidth * 0.58f
                val dotRadius = 0.9.dp.toPx()
                val maxDotAlpha = if (isDark) 0.075f else 0.06f
                val limitX = canvasWidth - 12.dp.toPx()
                val limitY = canvasHeight - 12.dp.toPx()
                val spanX = (canvasWidth - gridStartX).coerceAtLeast(1f)
                val safeHeight = canvasHeight.coerceAtLeast(1f)
                val gridDots = buildList {
                    var gx = gridStartX
                    while (gx < limitX) {
                        val xRatio = ((gx - gridStartX) / spanX).coerceIn(0f, 1f)
                        var gy = 14.dp.toPx()
                        while (gy < limitY) {
                            val yDist = kotlin.math.abs(gy / safeHeight - 0.5f) * 2f
                            val dotAlpha = maxDotAlpha * xRatio * (1f - yDist * 0.72f)
                            if (dotAlpha > 0.008f) {
                                add(Offset(gx, gy) to onSurface.copy(alpha = dotAlpha))
                            }
                            gy += gridStep
                        }
                        gx += gridStep
                    }
                }
                onDrawBehind {
                    val t = phase?.value ?: 0.12f
                    val breath = 0.5f - 0.5f * cos(t * 6.0 * PI).toFloat()
                    val counterBreath = 0.5f + 0.5f * cos(t * 4.0 * PI).toFloat()
                    val primaryGlowAlpha = if (isDark) 0.08f + 0.07f * breath else 0.06f + 0.05f * breath
                    val secondaryGlowAlpha = if (isDark) 0.05f + 0.05f * counterBreath else 0.04f + 0.04f * counterBreath

                    // 左侧主极光呼吸晕 & 右侧副色柔和雾晕（画刷已缓存，仅按帧调制透明度）
                    drawRect(brush = leftGlowBrush, alpha = primaryGlowAlpha)
                    drawRect(brush = rightGlowBrush, alpha = secondaryGlowAlpha)
                    for ((pos, color) in gridDots) {
                        drawCircle(color, dotRadius, pos)
                    }
                    // 顶部镜面流光细线
                    drawLine(
                        brush = topLineBrush,
                        start = topLineStart,
                        end = topLineEnd,
                        strokeWidth = topStroke,
                        alpha = 0.36f + 0.18f * breath,
                    )
                }
            }
            .border(BorderStroke(1.dp, borderColor), shape)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
            .testTag("room-list-loading-hero"),
    ) {
        val compact = maxWidth < 460.dp
        val showEqualizer = maxWidth >= 620.dp
        val beaconSize = if (compact) 62.dp else 78.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 14.dp else 20.dp,
                    vertical = if (compact) 14.dp else 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OrbitalPulseBeacon(
                    phase = phase,
                    modifier = Modifier.size(beaconSize),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (compact) {
                        LiveSyncPill(phase = phase)
                        Text(
                            text = readI18n("multiplayer.roomList.loadingTitle"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LiveSyncPill(phase = phase)
                            Text(
                                text = readI18n("multiplayer.roomList.loadingTitle"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }

                    if (animated) {
                        AnimatedContent(
                            targetState = hints[hintIndex],
                            transitionSpec = {
                                (fadeIn(tween(420, easing = StandardDecelerateEasing)) +
                                    slideInVertically(tween(420, easing = EmphasizedDecelerateEasing)) { it / 3 })
                                    .togetherWith(
                                        fadeOut(tween(260, easing = EmphasizedAccelerateEasing)) +
                                            slideOutVertically(tween(260, easing = EmphasizedAccelerateEasing)) { -it / 4 },
                                    )
                            },
                            label = "roomListLoadingHint",
                        ) { currentHint ->
                            Text(
                                text = currentHint,
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = hints.first(),
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (!compact) {
                        Spacer(Modifier.height(2.dp))
                        LuminousFluidBar(
                            phase = phase,
                            modifier = Modifier
                                .fillMaxWidth(0.82f)
                                .widthIn(max = 380.dp)
                                .height(3.5.dp),
                        )
                    }
                }

                if (showEqualizer) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surfaceContainerLow.copy(alpha = if (isDark) 0.48f else 0.70f),
                        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.65f)),
                    ) {
                        CalmSignalEqualizer(
                            phase = phase,
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .width(34.dp)
                                .height(24.dp),
                        )
                    }
                }
            }

            if (compact) {
                LuminousFluidBar(
                    phase = phase,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp),
                )
            }
        }
    }
}

@Composable
private fun LiveSyncPill(
    phase: State<Float>?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val secondary = colors.secondary

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = primary.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, primary.copy(alpha = 0.30f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Canvas(Modifier.size(8.dp)) {
                val t = phase?.value ?: 0.25f
                val pulse = 0.5f - 0.5f * cos(t * 6.0 * PI).toFloat()
                drawCircle(
                    color = secondary.copy(alpha = 0.18f + 0.24f * pulse),
                    radius = size.minDimension * (0.34f + 0.16f * pulse),
                )
                drawCircle(
                    color = primary,
                    radius = size.minDimension * 0.24f,
                )
            }
            Text(
                text = readI18n("multiplayer.roomList.loadingBadge"),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = primary,
                maxLines = 1,
            )
        }
    }
}

/**
 * 呼吸感双轨星环信标：所有帧状态均在绘制阶段读取，支持轻点激发柔光共振涟漪。
 */
@Composable
private fun OrbitalPulseBeacon(
    phase: State<Float>?,
    modifier: Modifier = Modifier,
) {
    val animated = uiAnimationsEnabled() && phase != null
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val secondary = colors.secondary
    val onSurface = colors.onSurface
    val coreSurface = colors.surfaceContainerLow
    val highlight = remember(primary, onSurface) { lerp(primary, onSurface, 0.52f) }
    val coreDiamondPath = remember { Path() }

    val tapRipple = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(animated) {
        if (!animated) tapRipple.snapTo(1f)
    }

    val outerArcBrush = remember(primary, secondary, highlight) {
        Brush.sweepGradient(
            0.00f to Color.Transparent,
            0.52f to Color.Transparent,
            0.66f to secondary.copy(alpha = 0.12f),
            0.80f to secondary.copy(alpha = 0.42f),
            0.91f to primary.copy(alpha = 0.86f),
            0.96f to highlight,
            0.985f to Color.Transparent,
            1.00f to Color.Transparent,
        )
    }
    val innerArcBrush = remember(primary, secondary) {
        Brush.sweepGradient(
            0.00f to Color.Transparent,
            0.60f to Color.Transparent,
            0.76f to primary.copy(alpha = 0.18f),
            0.92f to secondary.copy(alpha = 0.72f),
            0.97f to secondary.copy(alpha = 0.90f),
            0.99f to Color.Transparent,
            1.00f to Color.Transparent,
        )
    }

    val ambientHaloBrush = remember(primary, secondary) {
        Brush.radialGradient(
            colors = listOf(primary, secondary.copy(alpha = 0.42f), Color.Transparent),
        )
    }
    val sectorWashBrush = remember(primary, secondary) {
        Brush.sweepGradient(
            0.00f to Color.Transparent,
            0.76f to Color.Transparent,
            0.88f to secondary.copy(alpha = 0.04f),
            0.96f to primary.copy(alpha = 0.14f),
            0.985f to Color.Transparent,
            1.00f to Color.Transparent,
        )
    }

    Canvas(
        modifier = modifier
            .pointerInput(animated) {
                if (!animated) return@pointerInput
                detectTapGestures {
                    scope.launch {
                        tapRipple.snapTo(0f)
                        tapRipple.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = 860, easing = FastOutSlowInEasing),
                        )
                    }
                }
            },
    ) {
        val t = phase?.value
        val radius = size.minDimension * 0.46f
        val breath = if (t != null) 0.5f - 0.5f * cos(t * 6.0 * PI).toFloat() else 0.55f
        val outerHeadDeg = if (t != null) t * 3f * 360f else -42f
        val innerHeadDeg = if (t != null) -t * 2f * 360f + 135f else 142f
        val sonarProgress = if (t != null) (t * 4f) % 1f else 0.42f
        val rippleProgress = tapRipple.value

        // 1. 柔和呼吸光晕（缓存径向画刷，按帧调制透明度）
        drawCircle(
            brush = ambientHaloBrush,
            radius = radius * 1.12f,
            alpha = 0.11f + 0.10f * breath,
        )

        // 2. 极细同心定位环与四向微刻度
        val hairline = 1.dp.toPx()
        val outerOrbitRadius = radius * 0.88f
        val innerOrbitRadius = radius * 0.58f
        drawCircle(onSurface.copy(alpha = 0.12f), outerOrbitRadius, style = Stroke(hairline))
        drawCircle(onSurface.copy(alpha = 0.08f), innerOrbitRadius, style = Stroke(hairline))
        val tickInner = outerOrbitRadius - 2.5.dp.toPx()
        val tickOuter = outerOrbitRadius + 2.5.dp.toPx()
        val tickColor = onSurface.copy(alpha = 0.16f)
        drawLine(tickColor, Offset(center.x, center.y - tickOuter), Offset(center.x, center.y - tickInner), hairline, StrokeCap.Round)
        drawLine(tickColor, Offset(center.x, center.y + tickInner), Offset(center.x, center.y + tickOuter), hairline, StrokeCap.Round)
        drawLine(tickColor, Offset(center.x - tickOuter, center.y), Offset(center.x - tickInner, center.y), hairline, StrokeCap.Round)
        drawLine(tickColor, Offset(center.x + tickInner, center.y), Offset(center.x + tickOuter, center.y), hairline, StrokeCap.Round)

        // 3. 舒缓扩散声纳涟漪
        val easedSonar = FastOutSlowInEasing.transform(sonarProgress)
        val sonarRadius = radius * (0.26f + 0.72f * easedSonar)
        val sonarAlpha = (1f - easedSonar).pow(1.9f) * 0.34f
        if (sonarAlpha > 0.01f) {
            drawCircle(
                color = lerp(primary, secondary, easedSonar).copy(alpha = sonarAlpha),
                radius = sonarRadius,
                style = Stroke(1.3.dp.toPx()),
            )
        }

        // 4. 轻点触发的互动共振光环
        if (rippleProgress < 0.995f) {
            val rippleAlpha = (1f - rippleProgress).pow(1.7f)
            drawCircle(
                color = highlight.copy(alpha = rippleAlpha * 0.55f),
                radius = radius * (0.20f + 0.86f * rippleProgress),
                style = Stroke(2.dp.toPx()),
            )
            if (rippleProgress > 0.12f) {
                val echo = ((rippleProgress - 0.12f) / 0.88f).coerceIn(0f, 1f)
                drawCircle(
                    color = primary.copy(alpha = (1f - echo).pow(2f) * 0.30f),
                    radius = radius * (0.18f + 0.76f * echo),
                    style = Stroke(1.2.dp.toPx()),
                )
            }
        }

        // 5. 内圈反向流光弧
        val innerArcSize = Size(innerOrbitRadius * 2f, innerOrbitRadius * 2f)
        val innerTopLeft = Offset(center.x - innerOrbitRadius, center.y - innerOrbitRadius)
        rotate(degrees = innerHeadDeg, pivot = center) {
            drawArc(
                brush = innerArcBrush,
                startAngle = 225f,
                sweepAngle = 122f,
                useCenter = false,
                topLeft = innerTopLeft,
                size = innerArcSize,
                style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // 6. 外圈主星轨彗尾弧 + 柔光扇形余晖 + 领航光珠
        val outerArcSize = Size(outerOrbitRadius * 2f, outerOrbitRadius * 2f)
        val outerTopLeft = Offset(center.x - outerOrbitRadius, center.y - outerOrbitRadius)
        rotate(degrees = outerHeadDeg, pivot = center) {
            drawArc(
                brush = sectorWashBrush,
                startAngle = 272f,
                sweepAngle = 74f,
                useCenter = true,
                topLeft = outerTopLeft,
                size = outerArcSize,
            )
            drawArc(
                brush = outerArcBrush,
                startAngle = 200f,
                sweepAngle = 146f,
                useCenter = false,
                topLeft = outerTopLeft,
                size = outerArcSize,
                style = Stroke(2.3.dp.toPx(), cap = StrokeCap.Round),
            )
            val tipCenter = Offset(
                center.x + OUTER_TIP_COS * outerOrbitRadius,
                center.y + OUTER_TIP_SIN * outerOrbitRadius,
            )
            drawCircle(primary.copy(alpha = 0.24f), radius = 6.dp.toPx(), center = tipCenter)
            drawCircle(highlight, radius = 2.4.dp.toPx(), center = tipCenter)
        }

        // 7. 三个随扫掠点亮的战局星点
        val worldTipAngle = ((outerHeadDeg + OUTER_TIP_ANGLE_DEG) % 360f + 360f) % 360f
        for (blip in BeaconBlips) {
            val lag = ((worldTipAngle - blip.angleDeg) % 360f + 360f) % 360f
            val activation = if (lag < 140f) (1f - lag / 140f).pow(2.1f) else 0f
            val rad = blip.angleDeg * PI / 180.0
            val pos = Offset(
                center.x + cos(rad).toFloat() * radius * blip.radiusFraction,
                center.y + sin(rad).toFloat() * radius * blip.radiusFraction,
            )
            if (activation > 0.04f) {
                drawCircle(
                    color = secondary.copy(alpha = activation * 0.34f),
                    radius = (3.2f + (1f - activation) * 5.5f).dp.toPx(),
                    center = pos,
                    style = Stroke(1.dp.toPx()),
                )
                drawCircle(
                    color = primary.copy(alpha = activation * 0.26f),
                    radius = 4.8.dp.toPx(),
                    center = pos,
                )
            }
            drawCircle(
                color = lerp(secondary, highlight, activation).copy(alpha = 0.32f + activation * 0.68f),
                radius = (1.6f + activation * 0.9f).dp.toPx(),
                center = pos,
            )
        }

        // 8. 中心磨砂内核与呼吸菱形信标
        val coreRadius = radius * 0.27f
        val rippleBoost = if (rippleProgress < 0.995f) (1f - rippleProgress) * 0.25f else 0f
        drawCircle(coreSurface.copy(alpha = 0.92f), coreRadius)
        drawCircle(
            color = primary.copy(alpha = (0.45f + 0.30f * breath + rippleBoost).coerceAtMost(1f)),
            radius = coreRadius,
            style = Stroke(1.2.dp.toPx()),
        )
        val gemSize = coreRadius * (0.44f + 0.10f * breath + rippleBoost * 0.14f)
        coreDiamondPath.reset()
        coreDiamondPath.moveTo(center.x, center.y - gemSize)
        coreDiamondPath.lineTo(center.x + gemSize, center.y)
        coreDiamondPath.lineTo(center.x, center.y + gemSize)
        coreDiamondPath.lineTo(center.x - gemSize, center.y)
        coreDiamondPath.close()
        drawPath(
            path = coreDiamondPath,
            color = lerp(primary, highlight, 0.35f + 0.35f * breath),
        )
    }
}

/** 柔光液态流丝条：以平滑单轨光波替代僵硬往返进度条。 */
@Composable
private fun LuminousFluidBar(
    phase: State<Float>?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val secondary = colors.secondary
    val trackColor = colors.onSurface.copy(alpha = 0.09f)
    val highlight = remember(primary, colors.onSurface) { lerp(primary, colors.onSurface, 0.42f) }

    Box(
        modifier = modifier.drawWithCache {
            val radius = size.height / 2f
            val corner = CornerRadius(radius, radius)
            val waveWidth = (size.width * 0.48f).coerceAtLeast(68.dp.toPx())
            val waveBrush = Brush.horizontalGradient(
                0.00f to Color.Transparent,
                0.24f to primary.copy(alpha = 0.40f),
                0.60f to secondary.copy(alpha = 0.88f),
                0.82f to highlight.copy(alpha = 0.96f),
                1.00f to Color.Transparent,
                startX = 0f,
                endX = waveWidth,
            )
            val echoBrush = Brush.horizontalGradient(
                0.00f to Color.Transparent,
                0.35f to primary.copy(alpha = 0.24f),
                0.68f to secondary.copy(alpha = 0.36f),
                1.00f to Color.Transparent,
                startX = 0f,
                endX = waveWidth,
            )
            val staticBrush = Brush.horizontalGradient(
                0.0f to primary.copy(alpha = 0.35f),
                0.5f to secondary.copy(alpha = 0.85f),
                1.0f to primary.copy(alpha = 0.35f),
                startX = size.width * 0.18f,
                endX = size.width * 0.68f,
            )
            onDrawBehind {
                drawRoundRect(color = trackColor, cornerRadius = corner)
                if (phase != null) {
                    val primaryCycle = (phase.value * 4f) % 1f
                    val echoCycle = (primaryCycle + 0.5f) % 1f
                    val echoStartX = -waveWidth + (size.width + waveWidth) * FastOutSlowInEasing.transform(echoCycle)
                    val primaryStartX = -waveWidth + (size.width + waveWidth) * FastOutSlowInEasing.transform(primaryCycle)
                    withTransform({ clipRect(0f, 0f, size.width, size.height) }) {
                        withTransform({ translate(left = echoStartX) }) {
                            drawRoundRect(
                                brush = echoBrush,
                                topLeft = Offset.Zero,
                                size = Size(waveWidth, size.height),
                                cornerRadius = corner,
                            )
                        }
                        withTransform({ translate(left = primaryStartX) }) {
                            drawRoundRect(
                                brush = waveBrush,
                                topLeft = Offset.Zero,
                                size = Size(waveWidth, size.height),
                                cornerRadius = corner,
                            )
                        }
                    }
                } else {
                    drawRoundRect(
                        brush = staticBrush,
                        topLeft = Offset(size.width * 0.18f, 0f),
                        size = Size(size.width * 0.50f, size.height),
                        cornerRadius = corner,
                    )
                }
            }
        },
    )
}

/** 宽屏右侧的 5 柱宁静谐波律动条，营造现代实时遥测质感。 */
@Composable
private fun CalmSignalEqualizer(
    phase: State<Float>?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val secondary = colors.secondary
    val staticHeights = remember { floatArrayOf(0.42f, 0.78f, 1.0f, 0.66f, 0.36f) }

    Canvas(modifier = modifier) {
        val count = 5
        val barWidth = 3.2.dp.toPx()
        val totalBarWidth = barWidth * count
        val gap = ((size.width - totalBarWidth) / (count - 1)).coerceAtLeast(2.dp.toPx())
        val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
        val t = phase?.value

        for (i in 0 until count) {
            val fraction = if (t != null) {
                val angle = t * 6.0 * PI * 2.0 - i * 0.72
                val envelope = 1f - kotlin.math.abs(i - 2f) * 0.14f
                (0.28f + 0.68f * (0.5f + 0.5f * sin(angle).toFloat()) * envelope).coerceIn(0.22f, 1f)
            } else {
                staticHeights[i]
            }
            val h = size.height * fraction
            val left = i * (barWidth + gap)
            val top = (size.height - h) / 2f
            val tint = lerp(primary, secondary, i / (count - 1f)).copy(alpha = 0.38f + 0.54f * fraction)
            drawRoundRect(
                color = tint,
                topLeft = Offset(left, top),
                size = Size(barWidth, h),
                cornerRadius = corner,
            )
        }
    }
}

@Composable
private fun RoomListSkeletonRow(
    rowIndex: Int,
    columns: Int,
    phase: State<Float>?,
    rowAlpha: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (colIndex in 0 until columns) {
            val blueprint = SkeletonBlueprints[(rowIndex * columns + colIndex) % SkeletonBlueprints.size]
            RoomListSkeletonCard(
                blueprint = blueprint,
                rowIndex = rowIndex,
                colIndex = colIndex,
                phase = phase,
                baseAlpha = rowAlpha,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 与 [RoomListCard] 精确对齐比例的流光骨架卡：
 * 单张卡仅用一层 Canvas 绘制全部占位元素与全局斜向流体极光波，零逐帧重组。
 */
@Composable
private fun RoomListSkeletonCard(
    blueprint: SkeletonCardBlueprint,
    rowIndex: Int,
    colIndex: Int,
    phase: State<Float>?,
    baseAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val surfaceColor = colors.surface.copy(alpha = if (isDark) 0.90f else 0.95f)
    val borderBase = colors.outlineVariant.copy(alpha = if (isDark) 0.40f else 0.52f)
    val primary = colors.primary
    val secondary = colors.secondary
    val onSurface = colors.onSurface
    val thumbBg = colors.surfaceContainerLow.copy(alpha = if (isDark) 0.86f else 0.95f)

    val blockBase = remember(onSurface, primary, isDark) {
        lerp(
            onSurface.copy(alpha = if (isDark) 0.115f else 0.085f),
            primary.copy(alpha = if (isDark) 0.14f else 0.10f),
            0.22f,
        )
    }
    val blockMuted = remember(onSurface, isDark) {
        onSurface.copy(alpha = if (isDark) 0.075f else 0.058f)
    }
    val accentPillBase = remember(primary, isDark) {
        primary.copy(alpha = if (isDark) 0.16f else 0.12f)
    }
    val sheenColor = remember(onSurface, primary, secondary, isDark) {
        if (isDark) {
            lerp(onSurface, lerp(primary, secondary, 0.4f), 0.44f)
        } else {
            lerp(Color.White, primary, 0.24f)
        }
    }
    val shape = RoundedCornerShape(14.dp)
    val stagger = rowIndex * 0.14f + colIndex * 0.09f

    Box(
        modifier = modifier
            .height(92.dp)
            .graphicsLayer {
                alpha = baseAlpha
                if (phase != null) {
                    val localWave = (phase.value * 6f - stagger).let { it - kotlin.math.floor(it) }
                    // 极轻微波峰微浮，产生丝绸呼吸感而不影响排版
                    translationY = -sin(localWave * PI * 2.0).toFloat() * 1.15.dp.toPx()
                }
            }
            .clip(shape)
            .background(surfaceColor)
            .testTag("room-list-skeleton-card")
            .drawWithCache {
                val bandWidth = (size.width * 0.56f).coerceAtLeast(96.dp.toPx())
                // 斜向线性渐变的两侧须额外预留 >= height 的透明羽化翼，杜绝倾斜光带被矩形边硬裁切。
                val featherPad = size.height
                val sheenBoxWidth = bandWidth + featherPad * 2f
                val sheenMaxAlpha = if (isDark) 0.15f else 0.30f
                val sheenBrush = Brush.linearGradient(
                    0.00f to Color.Transparent,
                    0.22f to Color.Transparent,
                    0.42f to sheenColor.copy(alpha = sheenMaxAlpha * 0.36f),
                    0.50f to sheenColor.copy(alpha = sheenMaxAlpha),
                    0.58f to sheenColor.copy(alpha = sheenMaxAlpha * 0.36f),
                    0.78f to Color.Transparent,
                    1.00f to Color.Transparent,
                    start = Offset(featherPad * 0.6f, 0f),
                    end = Offset(sheenBoxWidth - featherPad * 0.6f, size.height),
                )
                val topGlintWidth = (size.width * 0.44f).coerceAtLeast(64.dp.toPx())
                val topGlintBrush = Brush.horizontalGradient(
                    0.0f to Color.Transparent,
                    0.5f to primary.copy(alpha = 0.55f),
                    1.0f to Color.Transparent,
                    startX = 0f,
                    endX = topGlintWidth,
                )
                onDrawBehind {
                    val localWave = if (phase != null) {
                        ((phase.value * 6f - stagger) % 1f + 1f) % 1f
                    } else {
                        -1f
                    }
                    // 波峰掠过当前卡片时的柔和包络（0..1）
                    val crestIntensity = if (localWave in 0f..1f) {
                        val dist = kotlin.math.abs(localWave - 0.5f) * 2f
                        (1f - dist).coerceIn(0f, 1f).pow(2.2f)
                    } else {
                        0f
                    }

                    val padX = 12.dp.toPx()
                    val padY = 10.dp.toPx()
                    val thumbSize = 48.dp.toPx()
                    val gap = 10.dp.toPx()

                    // 左侧 48dp 地图缩略图占位与极淡战术地形线条
                    val thumbTopLeft = Offset(padX, padY)
                    val thumbCorner = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    drawRoundRect(
                        color = thumbBg,
                        topLeft = thumbTopLeft,
                        size = Size(thumbSize, thumbSize),
                        cornerRadius = thumbCorner,
                    )
                    drawRoundRect(
                        color = blockMuted,
                        topLeft = thumbTopLeft,
                        size = Size(thumbSize, thumbSize),
                        cornerRadius = thumbCorner,
                    )
                    val gridColor = onSurface.copy(alpha = if (isDark) 0.085f else 0.065f)
                    val third = thumbSize / 3f
                    drawLine(gridColor, Offset(padX + third, padY + 4.dp.toPx()), Offset(padX + third, padY + thumbSize - 4.dp.toPx()), 1.dp.toPx())
                    drawLine(gridColor, Offset(padX + third * 2f, padY + 4.dp.toPx()), Offset(padX + third * 2f, padY + thumbSize - 4.dp.toPx()), 1.dp.toPx())
                    drawLine(gridColor, Offset(padX + 4.dp.toPx(), padY + third), Offset(padX + thumbSize - 4.dp.toPx(), padY + third), 1.dp.toPx())
                    drawLine(gridColor, Offset(padX + 4.dp.toPx(), padY + third * 2f), Offset(padX + thumbSize - 4.dp.toPx(), padY + third * 2f), 1.dp.toPx())
                    drawCircle(
                        color = primary.copy(alpha = 0.26f + 0.20f * crestIntensity),
                        radius = 2.6.dp.toPx(),
                        center = Offset(padX + thumbSize * 0.64f, padY + thumbSize * 0.38f),
                    )

                    // 右侧三行骨架占位（与 RoomListCard 精确对应）
                    val contentLeft = padX + thumbSize + gap
                    val contentWidth = (size.width - contentLeft - padX).coerceAtLeast(40.dp.toPx())
                    val pillCorner = CornerRadius(6.dp.toPx(), 6.dp.toPx())

                    // 第一行：房间标题 + 右侧人数胶囊
                    val playerPillW = 46.dp.toPx().coerceAtMost(contentWidth * 0.34f)
                    val playerPillH = 20.dp.toPx()
                    val titleMaxW = (contentWidth - playerPillW - 10.dp.toPx()).coerceAtLeast(24.dp.toPx())
                    val titleW = (titleMaxW * blueprint.titleFraction).coerceAtLeast(24.dp.toPx())
                    val titleH = 15.dp.toPx()
                    drawRoundRect(
                        color = blockBase,
                        topLeft = Offset(contentLeft, padY + 2.dp.toPx()),
                        size = Size(titleW, titleH),
                        cornerRadius = pillCorner,
                    )
                    drawRoundRect(
                        color = blockMuted,
                        topLeft = Offset(contentLeft + contentWidth - playerPillW, padY),
                        size = Size(playerPillW, playerPillH),
                        cornerRadius = pillCorner,
                    )

                    // 第二行：房间类型 / 模组同步 / 标签胶囊
                    val badgesTop = padY + 26.dp.toPx()
                    val badgeH = 19.dp.toPx()
                    val badgeGap = 6.dp.toPx()
                    var cursorX = contentLeft
                    blueprint.badgeWidths.forEachIndexed { idx, dpWidth ->
                        val w = dpWidth.dp.toPx()
                        if (cursorX + w <= contentLeft + contentWidth) {
                            val isSyncAccent = blueprint.highlightSecondBadge && idx == 1
                            drawRoundRect(
                                color = if (isSyncAccent) accentPillBase else blockMuted,
                                topLeft = Offset(cursorX, badgesTop),
                                size = Size(w, badgeH),
                                cornerRadius = pillCorner,
                            )
                            cursorX += w + badgeGap
                        }
                    }

                    // 第三行：地图名称细条
                    val mapTop = badgesTop + badgeH + 8.dp.toPx()
                    val mapW = (contentWidth * blueprint.mapFraction).coerceAtLeast(36.dp.toPx())
                    drawRoundRect(
                        color = blockMuted,
                        topLeft = Offset(contentLeft, mapTop),
                        size = Size(mapW, 11.dp.toPx()),
                        cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                    )

                    // 跨越整张卡片的斜向柔光波带（复用带羽化翼的缓存画刷平移，全程无硬边）
                    if (localWave in 0f..1f) {
                        val easedWave = FastOutSlowInEasing.transform(localWave)
                        val travel = size.width + sheenBoxWidth
                        val bandLeft = -sheenBoxWidth + travel * easedWave
                        withTransform({ translate(left = bandLeft) }) {
                            drawRect(
                                brush = sheenBrush,
                                topLeft = Offset.Zero,
                                size = Size(sheenBoxWidth, size.height),
                            )
                        }
                        val glintLeft = -topGlintWidth + (size.width + topGlintWidth) * easedWave
                        withTransform({ translate(left = glintLeft) }) {
                            drawLine(
                                brush = topGlintBrush,
                                start = Offset(0f, 1.dp.toPx()),
                                end = Offset(topGlintWidth, 1.dp.toPx()),
                                strokeWidth = 1.dp.toPx(),
                                alpha = 0.25f + 0.55f * crestIntensity,
                            )
                        }
                    }

                    // 细微边框 + 随光波轻拂的边缘高光
                    drawCardBorderWithSheen(
                        cornerRadius = 14.dp.toPx(),
                        borderBase = borderBase,
                        primary = primary,
                        crestIntensity = crestIntensity,
                    )
                }
            },
    )
}

private fun DrawScope.drawCardBorderWithSheen(
    cornerRadius: Float,
    borderBase: Color,
    primary: Color,
    crestIntensity: Float,
) {
    val strokePx = 1.dp.toPx()
    val inset = strokePx / 2f
    val borderColor = if (crestIntensity > 0.01f) {
        lerp(borderBase, primary.copy(alpha = 0.55f), crestIntensity * 0.55f)
    } else {
        borderBase
    }
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokePx, size.height - strokePx),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
        style = Stroke(strokePx),
    )
}
