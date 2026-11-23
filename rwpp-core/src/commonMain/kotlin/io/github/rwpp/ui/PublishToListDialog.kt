/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.DEFAULT_PUBLISH_ROOM_TYPE
import io.github.rwpp.net.composePublishRoomType
import io.github.rwpp.widget.AnimatedAlertDialog
import io.github.rwpp.widget.LocalAccountAuthDialogEscape
import io.github.rwpp.widget.accountTextFieldEdit
import io.github.rwpp.widget.animateUiColorAsState
import io.github.rwpp.widget.desktopEscapeToBackDispatcher
import io.github.rwpp.widget.uiAnimationsEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

internal enum class PublishStep {
    FetchingTypes,
    Publishing,
}

internal sealed class PublishToListUiState {
    data object Hidden : PublishToListUiState()

    data class Loading(val step: PublishStep) : PublishToListUiState()

    data class SelectRoomType(
        val types: List<String>,
        val allowCustomRoomName: Boolean,
        /** 房主开启传输模组时为 true：普通标签仍可多选，模组同步标签会被自动追加。 */
        val modSyncEnabled: Boolean = false,
    ) : PublishToListUiState()

    data class Success(val roomId: String, val serverId: String) : PublishToListUiState()

    data class Failure(val message: String, val step: PublishStep) : PublishToListUiState()
}

private const val PUBLISH_MASTER_CYCLE_MS = 12_000
private const val PUBLISH_TIP_ROTATE_MS = 3_800L
private const val PUBLISH_PING_POOL_SIZE = 3
private const val PUBLISH_PING_DURATION_MS = 920
private const val PUBLISH_DIALOG_MAX_WIDTH_DP = 492

private data class RelayNodeSpec(
    val xFraction: Float,
    val yFraction: Float,
    val packetOffset: Float,
    val isPrimaryRelay: Boolean,
)

private val PublishRelayNodes = listOf(
    RelayNodeSpec(xFraction = 0.14f, yFraction = 0.27f, packetOffset = 0.00f, isPrimaryRelay = true),
    RelayNodeSpec(xFraction = 0.21f, yFraction = 0.70f, packetOffset = 0.34f, isPrimaryRelay = false),
    RelayNodeSpec(xFraction = 0.31f, yFraction = 0.19f, packetOffset = 0.62f, isPrimaryRelay = false),
    RelayNodeSpec(xFraction = 0.70f, yFraction = 0.21f, packetOffset = 0.18f, isPrimaryRelay = false),
    RelayNodeSpec(xFraction = 0.85f, yFraction = 0.31f, packetOffset = 0.50f, isPrimaryRelay = true),
    RelayNodeSpec(xFraction = 0.79f, yFraction = 0.70f, packetOffset = 0.80f, isPrimaryRelay = true),
)

@Stable
private data class PublishDialogPalette(
    val isDark: Boolean,
    val cardSurface: Color,
    val stageSurface: Color,
    val panelSurface: Color,
    val panelHighlight: Color,
    val borderTop: Color,
    val borderBottom: Color,
    val hairlineBorder: Color,
    val primary: Color,
    val secondary: Color,
    val highlight: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onPrimary: Color,
    val successAccent: Color,
    val warningAccent: Color,
)

@Composable
private fun rememberPublishDialogPalette(colors: ColorScheme): PublishDialogPalette {
    val isDark = colors.background.luminance() < 0.5f
    return remember(colors, isDark) {
        val cardSurface = if (isDark) {
            lerp(colors.background, Color(0xFF0E1110), 0.54f).copy(alpha = 0.97f)
        } else {
            colors.surface.copy(alpha = 0.98f)
        }
        val stageSurface = if (isDark) {
            lerp(colors.background, Color(0xFF060807), 0.74f)
        } else {
            lerp(colors.surface, colors.primary, 0.05f)
        }
        val panelSurface = if (isDark) {
            lerp(colors.background, Color(0xFF101312), 0.36f).copy(alpha = 0.76f)
        } else {
            lerp(colors.surface, colors.onSurface, 0.045f)
        }
        val panelHighlight = if (isDark) {
            lerp(colors.background, colors.primary, 0.14f).copy(alpha = 0.72f)
        } else {
            colors.primary.copy(alpha = 0.09f)
        }
        val highlight = lerp(
            colors.primary,
            if (isDark) Color.White else colors.secondary,
            if (isDark) 0.46f else 0.25f,
        )
        val successAccent = if (isDark) {
            lerp(colors.secondary, Color(0xFF56DF8D), 0.42f)
        } else {
            Color(0xFF1D8A4E)
        }
        val warningAccent = if (isDark) {
            Color(0xFFF3AA4E)
        } else {
            Color(0xFFC56B0C)
        }
        PublishDialogPalette(
            isDark = isDark,
            cardSurface = cardSurface,
            stageSurface = stageSurface,
            panelSurface = panelSurface,
            panelHighlight = panelHighlight,
            borderTop = lerp(colors.onSurface, colors.primary, 0.42f).copy(alpha = if (isDark) 0.24f else 0.22f),
            borderBottom = colors.onSurface.copy(alpha = if (isDark) 0.09f else 0.12f),
            hairlineBorder = colors.onSurface.copy(alpha = if (isDark) 0.11f else 0.12f),
            primary = colors.primary,
            secondary = colors.secondary,
            highlight = highlight,
            onSurface = colors.onSurface,
            onSurfaceVariant = if (isDark) {
                colors.onSurface.copy(alpha = 0.68f)
            } else {
                colors.onSurfaceVariant
            },
            onPrimary = colors.onPrimary,
            successAccent = successAccent,
            warningAccent = warningAccent,
        )
    }
}

@Composable
internal fun PublishToListDialog(
    state: PublishToListUiState,
    defaultRoomName: String,
    roomCode: String = "",
    pendingRoomName: String = "",
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onCloseButtonClick: () -> Unit,
    onPublish: (String, String) -> Unit,
    onRetry: (PublishStep) -> Unit,
) {
    AnimatedAlertDialog(
        visible = state != PublishToListUiState.Hidden,
        onDismissRequest = {
            if (state !is PublishToListUiState.Loading) onDismiss()
        },
        enableDismiss = state !is PublishToListUiState.Loading,
    ) { dismiss ->
        PublishToListDialogCard(
            state = state,
            defaultRoomName = defaultRoomName,
            roomCode = roomCode,
            pendingRoomName = pendingRoomName,
            onDismiss = dismiss,
            onCancel = onCancel,
            onCloseButtonClick = onCloseButtonClick,
            onPublish = onPublish,
            onRetry = onRetry,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * 申请开放到列表弹窗卡片主体（独立提取便于桌面离线/动效回归测试与多主题预览）。
 */
@Composable
internal fun PublishToListDialogCard(
    state: PublishToListUiState,
    defaultRoomName: String,
    roomCode: String = "",
    pendingRoomName: String = "",
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onCloseButtonClick: () -> Unit,
    onPublish: (String, String) -> Unit,
    onRetry: (PublishStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == PublishToListUiState.Hidden) return

    val enableAnimations = uiAnimationsEnabled()
    val palette = rememberPublishDialogPalette(MaterialTheme.colorScheme)
    val cardShape = RoundedCornerShape(22.dp)

    // 仅在 Loading 且开启动效时持有单一谐波时钟；禁用动效或离开加载态立即退出组合，零帧开销。
    val loadingPhase: State<Float>? = if (state is PublishToListUiState.Loading && enableAnimations) {
        val transition = rememberInfiniteTransition(label = "publishUplinkMaster")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = PUBLISH_MASTER_CYCLE_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "publishUplinkMasterPhase",
        )
    } else {
        null
    }

    val auraTint = when (state) {
        is PublishToListUiState.Success -> palette.successAccent
        is PublishToListUiState.Failure -> palette.warningAccent
        else -> palette.primary
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth(0.94f)
            .widthIn(max = PUBLISH_DIALOG_MAX_WIDTH_DP.dp)
            .clip(cardShape)
            .background(palette.cardSurface)
            .drawWithCache {
                val topLeftAura = Brush.radialGradient(
                    colors = listOf(auraTint.copy(alpha = if (palette.isDark) 0.13f else 0.08f), Color.Transparent),
                    center = Offset(size.width * 0.18f, 0f),
                    radius = (size.maxDimension * 0.62f).coerceAtLeast(160.dp.toPx()),
                )
                val topRightAura = Brush.radialGradient(
                    colors = listOf(palette.secondary.copy(alpha = if (palette.isDark) 0.07f else 0.05f), Color.Transparent),
                    center = Offset(size.width * 0.88f, size.height * 0.10f),
                    radius = (size.maxDimension * 0.45f).coerceAtLeast(120.dp.toPx()),
                )
                val edgeInset = 26.dp.toPx()
                val topCatchlight = Brush.horizontalGradient(
                    0.00f to Color.Transparent,
                    0.26f to auraTint.copy(alpha = if (palette.isDark) 0.62f else 0.45f),
                    0.56f to palette.highlight.copy(alpha = if (palette.isDark) 0.80f else 0.55f),
                    0.82f to palette.secondary.copy(alpha = if (palette.isDark) 0.42f else 0.30f),
                    1.00f to Color.Transparent,
                    startX = edgeInset,
                    endX = (size.width - edgeInset).coerceAtLeast(edgeInset + 1f),
                )
                val borderBrush = Brush.verticalGradient(
                    0.0f to palette.borderTop,
                    0.35f to palette.hairlineBorder,
                    1.0f to palette.borderBottom,
                )
                val strokePx = 1.dp.toPx()
                val cornerPx = 22.dp.toPx()
                onDrawBehind {
                    drawRect(topLeftAura)
                    drawRect(topRightAura)
                    drawLine(
                        brush = topCatchlight,
                        start = Offset(edgeInset, strokePx),
                        end = Offset((size.width - edgeInset).coerceAtLeast(edgeInset), strokePx),
                        strokeWidth = strokePx,
                    )
                    drawRoundRect(
                        brush = borderBrush,
                        topLeft = Offset(strokePx / 2f, strokePx / 2f),
                        size = Size(size.width - strokePx, size.height - strokePx),
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                        style = Stroke(strokePx),
                    )
                }
            }
            .testTag("publish-to-list-dialog"),
    ) {
        val compactHeight = maxHeight < 430.dp
        val compactWidth = maxWidth < 380.dp

        AnimatedContent(
            targetState = state,
            transitionSpec = {
                if (enableAnimations) {
                    (fadeIn(tween(240, easing = StandardDecelerateEasing)) +
                        scaleIn(tween(240, easing = EmphasizedDecelerateEasing), initialScale = 0.965f))
                        .togetherWith(fadeOut(tween(150, easing = EmphasizedAccelerateEasing)))
                        .using(SizeTransform(clip = false))
                } else {
                    (EnterTransition.None togetherWith ExitTransition.None).using(null)
                }
            },
            label = "publishToListContent",
        ) { current ->
            when (current) {
                PublishToListUiState.Hidden -> Unit
                is PublishToListUiState.Loading -> PublishLoadingStage(
                    step = current.step,
                    roomCode = roomCode,
                    targetRoomName = pendingRoomName.ifBlank { defaultRoomName },
                    phase = loadingPhase,
                    palette = palette,
                    animated = enableAnimations,
                    compactHeight = compactHeight,
                    compactWidth = compactWidth,
                    onCancel = onCancel,
                )
                is PublishToListUiState.SelectRoomType -> PublishConfigureStage(
                    state = current,
                    defaultRoomName = defaultRoomName,
                    roomCode = roomCode,
                    palette = palette,
                    animated = enableAnimations,
                    compactHeight = compactHeight,
                    compactWidth = compactWidth,
                    onClose = onCloseButtonClick,
                    onPublish = onPublish,
                )
                is PublishToListUiState.Success -> PublishSuccessStage(
                    roomId = current.roomId.ifBlank { roomCode },
                    roomName = pendingRoomName.ifBlank { defaultRoomName },
                    palette = palette,
                    animated = enableAnimations,
                    compactHeight = compactHeight,
                    onClose = onCloseButtonClick,
                    onConfirm = onDismiss,
                )
                is PublishToListUiState.Failure -> PublishFailureStage(
                    message = current.message,
                    step = current.step,
                    palette = palette,
                    compactHeight = compactHeight,
                    onClose = onCloseButtonClick,
                    onDismiss = onDismiss,
                    onRetry = { onRetry(current.step) },
                )
            }
        }
    }
}

@Composable
private fun PublishLoadingStage(
    step: PublishStep,
    roomCode: String,
    targetRoomName: String,
    phase: State<Float>?,
    palette: PublishDialogPalette,
    animated: Boolean,
    compactHeight: Boolean,
    compactWidth: Boolean,
    onCancel: () -> Unit,
) {
    val tips = remember(step) {
        if (step == PublishStep.FetchingTypes) {
            listOf(
                readI18n("multiplayer.room.publishWaitTipConnect1"),
                readI18n("multiplayer.room.publishWaitTipConnect2"),
                readI18n("multiplayer.room.publishWaitTipConnect3"),
            )
        } else {
            listOf(
                readI18n("multiplayer.room.publishWaitTipSubmit1"),
                readI18n("multiplayer.room.publishWaitTipSubmit2"),
                readI18n("multiplayer.room.publishWaitTipSubmit3"),
                readI18n("multiplayer.room.publishWaitTipSubmit4"),
            )
        }
    }
    var tipIndex by remember(step, tips) { mutableIntStateOf(0) }
    LaunchedEffect(animated, step, tips) {
        tipIndex = 0
        if (!animated || tips.size <= 1) return@LaunchedEffect
        while (true) {
            delay(PUBLISH_TIP_ROTATE_MS)
            tipIndex = (tipIndex + 1) % tips.size
        }
    }

    var pingCount by remember { mutableIntStateOf(0) }
    val statusBadgeText = readI18n(
        if (step == PublishStep.FetchingTypes) {
            "multiplayer.room.publishBadgeConnecting"
        } else {
            "multiplayer.room.publishBadgeBroadcasting"
        },
    )
    val subStatusText = readI18n(
        if (step == PublishStep.FetchingTypes) {
            "multiplayer.room.publishFetchingTypes"
        } else {
            "multiplayer.room.publishSubmitting"
        },
    )
    val activeStepIndex = if (step == PublishStep.FetchingTypes) 0 else 2

    val contentPaddingH = if (compactWidth) 16.dp else 22.dp
    val contentPaddingV = if (compactHeight) 11.dp else 20.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = contentPaddingH, vertical = contentPaddingV)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
            .testTag("publish-loading-stage"),
        verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 14.dp),
    ) {
        // 顶部状态胶囊 + 房间号识别码
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PublishLiveStatusPill(
                text = statusBadgeText,
                phase = phase,
                palette = palette,
            )
            val identityLabel = roomCode.ifBlank { targetRoomName }
            if (identityLabel.isNotBlank()) {
                PublishCodeChip(
                    text = identityLabel,
                    palette = palette,
                )
            }
        }

        // 战术信标视觉舞台（支持轻点发射探测脉冲，缓解等待枯燥感又保持克制高级感）
        PublishBeaconStage(
            step = step,
            phase = phase,
            palette = palette,
            animated = animated,
            pingCount = pingCount,
            onEmitPing = { pingCount++ },
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compactHeight) 106.dp else 158.dp),
        )

        // 主标题与实时子阶段文案
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = readI18n("multiplayer.room.publishLoading"),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                ),
                color = palette.onSurface,
                textAlign = TextAlign.Center,
            )
            if (animated) {
                AnimatedContent(
                    targetState = subStatusText,
                    transitionSpec = {
                        fadeIn(tween(240, easing = StandardDecelerateEasing)) togetherWith
                            fadeOut(tween(180, easing = EmphasizedAccelerateEasing))
                    },
                    label = "publishSubStatus",
                ) { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.primary,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(
                    text = subStatusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.primary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 三段流程进度轨：连接大厅 -> 配置标签 -> 核验公开
        PublishStepRail(
            activeStepIndex = activeStepIndex,
            phase = phase,
            palette = palette,
            modifier = Modifier.fillMaxWidth(),
        )

        // 柔和的上下文等待小贴士条
        PublishWaitingTipStrip(
            tip = tips[tipIndex],
            animated = animated,
            palette = palette,
            modifier = Modifier.fillMaxWidth(),
        )

        // 底部克制的操作区
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            PublishSecondaryButton(
                label = readI18n("multiplayer.room.cancelPublish"),
                palette = palette,
                leadingIcon = {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = palette.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = onCancel,
                modifier = Modifier
                    .widthIn(min = 148.dp)
                    .testTag("publish-cancel-button"),
            )
        }
    }
}

/**
 * 战术信标上行舞台：
 * - 单一谐波时钟驱动星环扫掠与外放广播波纹，全量在绘制阶段读取，零逐帧重组。
 * - 支持玩家轻点任意位置释放探测脉冲（最多 3 路并发复用槽位），波峰掠过中继节点时会点亮节点与微弱回传连线。
 */
@Composable
private fun PublishBeaconStage(
    step: PublishStep,
    phase: State<Float>?,
    palette: PublishDialogPalette,
    animated: Boolean,
    pingCount: Int,
    onEmitPing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stageShape = RoundedCornerShape(16.dp)
    val scope = rememberCoroutineScope()

    val pingProgress = remember { List(PUBLISH_PING_POOL_SIZE) { Animatable(1f) } }
    val pingOrigins = remember { Array(PUBLISH_PING_POOL_SIZE) { Offset.Unspecified } }
    var nextPingSlot by remember { mutableIntStateOf(0) }

    LaunchedEffect(animated) {
        if (!animated) {
            pingProgress.forEach { it.snapTo(1f) }
        }
    }

    val coreDiamondPath = remember { Path() }
    val outerSweepBrush = remember(palette.primary, palette.secondary, palette.highlight) {
        Brush.sweepGradient(
            0.00f to Color.Transparent,
            0.54f to Color.Transparent,
            0.70f to palette.secondary.copy(alpha = 0.14f),
            0.84f to palette.secondary.copy(alpha = 0.45f),
            0.93f to palette.primary.copy(alpha = 0.88f),
            0.97f to palette.highlight,
            0.99f to Color.Transparent,
            1.00f to Color.Transparent,
        )
    }
    val innerSweepBrush = remember(palette.primary, palette.secondary) {
        Brush.sweepGradient(
            0.00f to Color.Transparent,
            0.58f to Color.Transparent,
            0.76f to palette.primary.copy(alpha = 0.20f),
            0.92f to palette.secondary.copy(alpha = 0.72f),
            0.97f to palette.secondary.copy(alpha = 0.90f),
            0.99f to Color.Transparent,
            1.00f to Color.Transparent,
        )
    }
    val sectorWashBrush = remember(palette.primary, palette.secondary) {
        Brush.sweepGradient(
            0.00f to Color.Transparent,
            0.76f to Color.Transparent,
            0.88f to palette.secondary.copy(alpha = 0.045f),
            0.96f to palette.primary.copy(alpha = 0.16f),
            0.988f to Color.Transparent,
            1.00f to Color.Transparent,
        )
    }
    val centerGlowBrush = remember(palette.primary, palette.secondary) {
        Brush.radialGradient(
            colors = listOf(
                palette.primary.copy(alpha = 0.34f),
                palette.secondary.copy(alpha = 0.12f),
                Color.Transparent,
            ),
        )
    }

    Box(
        modifier = modifier
            .clip(stageShape)
            .background(palette.stageSurface)
            .border(BorderStroke(1.dp, palette.hairlineBorder), stageShape)
            .pointerInput(animated) {
                if (!animated) return@pointerInput
                detectTapGestures { tapOffset ->
                    val slot = nextPingSlot % PUBLISH_PING_POOL_SIZE
                    nextPingSlot++
                    pingOrigins[slot] = tapOffset
                    onEmitPing()
                    scope.launch {
                        pingProgress[slot].snapTo(0f)
                        pingProgress[slot].animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = PUBLISH_PING_DURATION_MS,
                                easing = FastOutSlowInEasing,
                            ),
                        )
                    }
                }
            }
            .testTag("publish-beacon-stage"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val width = size.width
                    val height = size.height
                    val stageCenter = Offset(width * 0.5f, height * (if (animated) 0.45f else 0.50f))
                    val dotSpacing = 16.dp.toPx()
                    val dotRadius = 0.85.dp.toPx()
                    val maxDist = hypot(width * 0.5f, height * 0.5f).coerceAtLeast(1f)
                    val baseDotAlpha = if (palette.isDark) 0.095f else 0.075f
                    val gridDots = buildList {
                        var gx = dotSpacing * 0.75f
                        while (gx < width - dotSpacing * 0.5f) {
                            var gy = dotSpacing * 0.75f
                            while (gy < height - dotSpacing * 0.5f) {
                                val distRatio = (hypot(gx - stageCenter.x, gy - stageCenter.y) / maxDist).coerceIn(0f, 1f)
                                val alpha = baseDotAlpha * (1f - distRatio * 0.65f)
                                if (alpha > 0.012f) {
                                    add(Offset(gx, gy) to palette.onSurface.copy(alpha = alpha))
                                }
                                gy += dotSpacing
                            }
                            gx += dotSpacing
                        }
                    }
                    val nodePositions = PublishRelayNodes.map { node ->
                        Offset(width * node.xFraction, height * node.yFraction)
                    }
                    val nodeAngles = nodePositions.map { pos ->
                        val deg = Math.toDegrees(atan2((pos.y - stageCenter.y).toDouble(), (pos.x - stageCenter.x).toDouble())).toFloat()
                        ((deg % 360f) + 360f) % 360f
                    }
                    val cornerLen = 7.dp.toPx()
                    val cornerPad = 7.dp.toPx()
                    val cornerColor = palette.onSurface.copy(alpha = if (palette.isDark) 0.17f else 0.14f)
                    val hairlinePx = 1.dp.toPx()

                    onDrawBehind {
                        val t = phase?.value
                        val breath = if (t != null) 0.5f - 0.5f * cos(t * 6.0 * PI).toFloat() else 0.55f
                        val outerDeg = if (t != null) t * 3f * 360f else -36f
                        val innerDeg = if (t != null) -t * 2f * 360f + 135f else 140f
                        val beaconRadius = (height * 0.34f).coerceAtMost(width * 0.23f)

                        // 1. 细腻坐标点阵与四角微刻度
                        for ((pos, color) in gridDots) {
                            drawCircle(color = color, radius = dotRadius, center = pos)
                        }
                        drawCornerReticles(cornerPad, cornerLen, cornerColor, hairlinePx)

                        // 2. 中心呼吸极光晕
                        drawCircle(
                            brush = centerGlowBrush,
                            radius = beaconRadius * (1.35f + 0.14f * breath),
                            center = stageCenter,
                            alpha = 0.55f + 0.35f * breath,
                        )

                        // 3. 中继节点连接轨与动态信号包
                        val worldSweepAngle = ((outerDeg + 346f) % 360f + 360f) % 360f
                        val maxWaveSpan = hypot(width, height) * 0.58f

                        PublishRelayNodes.forEachIndexed { idx, node ->
                            val nodePos = nodePositions[idx]
                            val isBroadcast = step == PublishStep.Publishing

                            // 扫掠激活度（按真实屏幕几何角对齐扫掠光束）
                            val sweepLag = ((worldSweepAngle - nodeAngles[idx]) % 360f + 360f) % 360f
                            val sweepHit = if (sweepLag < 115f) (1f - sweepLag / 115f).pow(2.2f) else 0f

                            // 点击脉冲波峰掠过该节点时的共振激活度
                            var pingHit = 0f
                            for (slot in 0 until PUBLISH_PING_POOL_SIZE) {
                                val p = pingProgress[slot].value
                                val origin = pingOrigins[slot]
                                if (p < 0.995f && origin != Offset.Unspecified) {
                                    val waveRadius = maxWaveSpan * p
                                    val dist = hypot(nodePos.x - origin.x, nodePos.y - origin.y)
                                    val band = 26.dp.toPx()
                                    val delta = kotlin.math.abs(dist - waveRadius)
                                    if (delta < band) {
                                        val intensity = (1f - delta / band).pow(1.6f) * (1f - p * 0.65f)
                                        if (intensity > pingHit) pingHit = intensity
                                    }
                                }
                            }

                            val nodeActivation = (sweepHit * 0.75f + pingHit).coerceIn(0f, 1f)
                            val linkBaseAlpha = if (isBroadcast && node.isPrimaryRelay) 0.16f else 0.08f
                            drawLine(
                                color = lerp(palette.onSurface, palette.primary, 0.55f)
                                    .copy(alpha = linkBaseAlpha + nodeActivation * 0.34f),
                                start = stageCenter,
                                end = nodePos,
                                strokeWidth = (0.9f + nodeActivation * 0.6f).dp.toPx(),
                                cap = StrokeCap.Round,
                            )

                            // 阶段二（核验公开）时沿通道推送柔和上行光粒
                            if (isBroadcast && t != null) {
                                val travel = (t * 4f + node.packetOffset) % 1f
                                val easedTravel = FastOutSlowInEasing.transform(travel)
                                val packetPos = Offset(
                                    stageCenter.x + (nodePos.x - stageCenter.x) * easedTravel,
                                    stageCenter.y + (nodePos.y - stageCenter.y) * easedTravel,
                                )
                                val envelope = sin(easedTravel * PI).toFloat().coerceIn(0f, 1f)
                                drawCircle(
                                    color = palette.primary.copy(alpha = envelope * 0.34f),
                                    radius = 4.2.dp.toPx(),
                                    center = packetPos,
                                )
                                drawCircle(
                                    color = palette.highlight.copy(alpha = envelope * 0.92f),
                                    radius = 1.7.dp.toPx(),
                                    center = packetPos,
                                )
                            }

                            // 中继节点本体与共振光环
                            if (nodeActivation > 0.05f) {
                                drawCircle(
                                    color = palette.secondary.copy(alpha = nodeActivation * 0.38f),
                                    radius = (4.5f + (1f - nodeActivation) * 6.5f).dp.toPx(),
                                    center = nodePos,
                                    style = Stroke(1.1.dp.toPx()),
                                )
                                drawCircle(
                                    color = palette.primary.copy(alpha = nodeActivation * 0.24f),
                                    radius = 6.dp.toPx(),
                                    center = nodePos,
                                )
                            }
                            drawCircle(
                                color = lerp(palette.secondary, palette.highlight, nodeActivation)
                                    .copy(alpha = (0.34f + nodeActivation * 0.66f).coerceIn(0f, 1f)),
                                radius = (2.1f + nodeActivation * 1.1f).dp.toPx(),
                                center = nodePos,
                            )
                        }

                        // 4. 同心定位环 + 四向微刻度
                        val outerOrbit = beaconRadius * 0.92f
                        val innerOrbit = beaconRadius * 0.62f
                        drawCircle(palette.onSurface.copy(alpha = 0.13f), outerOrbit, center = stageCenter, style = Stroke(hairlinePx))
                        drawCircle(palette.onSurface.copy(alpha = 0.09f), innerOrbit, center = stageCenter, style = Stroke(hairlinePx))
                        val tickColor = palette.onSurface.copy(alpha = 0.20f)
                        val tickIn = outerOrbit - 2.5.dp.toPx()
                        val tickOut = outerOrbit + 2.5.dp.toPx()
                        drawLine(tickColor, Offset(stageCenter.x, stageCenter.y - tickOut), Offset(stageCenter.x, stageCenter.y - tickIn), hairlinePx, StrokeCap.Round)
                        drawLine(tickColor, Offset(stageCenter.x, stageCenter.y + tickIn), Offset(stageCenter.x, stageCenter.y + tickOut), hairlinePx, StrokeCap.Round)
                        drawLine(tickColor, Offset(stageCenter.x - tickOut, stageCenter.y), Offset(stageCenter.x - tickIn, stageCenter.y), hairlinePx, StrokeCap.Round)
                        drawLine(tickColor, Offset(stageCenter.x + tickIn, stageCenter.y), Offset(stageCenter.x + tickOut, stageCenter.y), hairlinePx, StrokeCap.Round)

                        // 5. 自动声纳/广播扩散波纹（阶段二为双波交错广播，阶段一为柔和单波探测）
                        val waveCount = if (step == PublishStep.Publishing) 2 else 1
                        for (w in 0 until waveCount) {
                            val raw = if (t != null) (t * 4f + w * 0.5f) % 1f else (0.38f + w * 0.35f)
                            val eased = FastOutSlowInEasing.transform(raw)
                            val waveRadius = beaconRadius * (0.32f + 1.08f * eased)
                            val waveAlpha = (1f - eased).pow(1.85f) * (if (step == PublishStep.Publishing) 0.40f else 0.30f)
                            if (waveAlpha > 0.01f) {
                                drawCircle(
                                    color = lerp(palette.primary, palette.secondary, eased).copy(alpha = waveAlpha),
                                    radius = waveRadius,
                                    center = stageCenter,
                                    style = Stroke(1.35.dp.toPx()),
                                )
                            }
                        }

                        // 6. 内外反向星轨弧 + 扇形余晖
                        val innerSize = Size(innerOrbit * 2f, innerOrbit * 2f)
                        val innerTopLeft = Offset(stageCenter.x - innerOrbit, stageCenter.y - innerOrbit)
                        rotate(degrees = innerDeg, pivot = stageCenter) {
                            drawArc(
                                brush = innerSweepBrush,
                                startAngle = 222f,
                                sweepAngle = 126f,
                                useCenter = false,
                                topLeft = innerTopLeft,
                                size = innerSize,
                                style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
                            )
                        }

                        val outerSize = Size(outerOrbit * 2f, outerOrbit * 2f)
                        val outerTopLeft = Offset(stageCenter.x - outerOrbit, stageCenter.y - outerOrbit)
                        rotate(degrees = outerDeg, pivot = stageCenter) {
                            drawArc(
                                brush = sectorWashBrush,
                                startAngle = 272f,
                                sweepAngle = 74f,
                                useCenter = true,
                                topLeft = outerTopLeft,
                                size = outerSize,
                            )
                            drawArc(
                                brush = outerSweepBrush,
                                startAngle = 198f,
                                sweepAngle = 148f,
                                useCenter = false,
                                topLeft = outerTopLeft,
                                size = outerSize,
                                style = Stroke(2.3.dp.toPx(), cap = StrokeCap.Round),
                            )
                            val tipAngleRad = 346.0 * PI / 180.0
                            val tipCenter = Offset(
                                stageCenter.x + cos(tipAngleRad).toFloat() * outerOrbit,
                                stageCenter.y + sin(tipAngleRad).toFloat() * outerOrbit,
                            )
                            drawCircle(palette.primary.copy(alpha = 0.28f), radius = 6.dp.toPx(), center = tipCenter)
                            drawCircle(palette.highlight, radius = 2.4.dp.toPx(), center = tipCenter)
                        }

                        // 7. 玩家轻点触发的互动探测脉冲（最多 3 路平滑叠加）
                        var centerBoost = 0f
                        for (slot in 0 until PUBLISH_PING_POOL_SIZE) {
                            val p = pingProgress[slot].value
                            val origin = pingOrigins[slot]
                            if (p < 0.995f && origin != Offset.Unspecified) {
                                val fade = (1f - p).pow(1.65f)
                                val radius = maxWaveSpan * p
                                drawCircle(
                                    color = palette.highlight.copy(alpha = fade * 0.58f),
                                    radius = radius,
                                    center = origin,
                                    style = Stroke(1.8.dp.toPx()),
                                )
                                if (p > 0.10f) {
                                    val echo = ((p - 0.10f) / 0.90f).coerceIn(0f, 1f)
                                    drawCircle(
                                        color = palette.primary.copy(alpha = (1f - echo).pow(2.0f) * 0.34f),
                                        radius = maxWaveSpan * echo * 0.86f,
                                        center = origin,
                                        style = Stroke(1.1.dp.toPx()),
                                    )
                                }
                                // 点击点中心微星芒
                                if (p < 0.32f) {
                                    val sparkFade = (1f - p / 0.32f)
                                    drawCircle(
                                        color = palette.highlight.copy(alpha = sparkFade * 0.72f),
                                        radius = (2.5f + p * 8f).dp.toPx(),
                                        center = origin,
                                    )
                                }
                                val boost = (1f - p) * 0.25f
                                if (boost > centerBoost) centerBoost = boost
                            }
                        }

                        // 8. 中心房间信标内核与呼吸菱晶
                        val coreRadius = beaconRadius * 0.30f
                        drawCircle(palette.cardSurface.copy(alpha = 0.95f), coreRadius, center = stageCenter)
                        drawCircle(
                            color = palette.primary.copy(alpha = (0.48f + 0.32f * breath + centerBoost).coerceAtMost(1f)),
                            radius = coreRadius,
                            center = stageCenter,
                            style = Stroke(1.3.dp.toPx()),
                        )
                        val gem = coreRadius * (0.44f + 0.10f * breath + centerBoost * 0.16f)
                        coreDiamondPath.reset()
                        coreDiamondPath.moveTo(stageCenter.x, stageCenter.y - gem)
                        coreDiamondPath.lineTo(stageCenter.x + gem, stageCenter.y)
                        coreDiamondPath.lineTo(stageCenter.x, stageCenter.y + gem)
                        coreDiamondPath.lineTo(stageCenter.x - gem, stageCenter.y)
                        coreDiamondPath.close()
                        drawPath(
                            path = coreDiamondPath,
                            color = lerp(palette.primary, palette.highlight, 0.36f + 0.34f * breath),
                        )
                    }
                },
        )

        // 舞台底部克制的互动微提示与右侧微信号律动（与四角微刻度留出安全间距）
        if (animated) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(palette.primary.copy(alpha = if (pingCount > 0) 0.90f else 0.55f)),
                    )
                    Text(
                        text = if (pingCount > 0) {
                            readI18n("multiplayer.room.publishBeaconPinged")
                        } else {
                            readI18n("multiplayer.room.publishBeaconHint")
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = palette.onSurfaceVariant.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (pingCount > 0) {
                        Surface(
                            shape = CircleShape,
                            color = palette.primary.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.34f)),
                        ) {
                            Text(
                                text = readI18n(
                                    "multiplayer.room.publishBeaconPingCount",
                                    I18nType.RWPP,
                                    pingCount.toString(),
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = palette.primary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                maxLines = 1,
                            )
                        }
                    }
                    PublishMicroSignalBars(
                        phase = phase,
                        palette = palette,
                        modifier = Modifier.width(18.dp).height(12.dp),
                    )
                }
            }
        }
    }
}

/** 舞台右下角 4 柱极简信号谐波条，增强实时遥测质感，零逐帧重组。 */
@Composable
private fun PublishMicroSignalBars(
    phase: State<Float>?,
    palette: PublishDialogPalette,
    modifier: Modifier = Modifier,
) {
    val staticHeights = remember { floatArrayOf(0.45f, 0.85f, 0.65f, 0.38f) }
    Canvas(modifier = modifier) {
        val count = 4
        val barW = 2.2.dp.toPx()
        val gap = ((size.width - barW * count) / (count - 1)).coerceAtLeast(1.5.dp.toPx())
        val corner = CornerRadius(barW / 2f, barW / 2f)
        val t = phase?.value
        for (i in 0 until count) {
            val frac = if (t != null) {
                val angle = t * 6.0 * PI * 2.0 - i * 0.85
                (0.28f + 0.68f * (0.5f + 0.5f * sin(angle).toFloat())).coerceIn(0.24f, 1f)
            } else {
                staticHeights[i]
            }
            val h = size.height * frac
            val left = i * (barW + gap)
            val top = size.height - h
            drawRoundRect(
                color = lerp(palette.primary, palette.secondary, i / (count - 1f))
                    .copy(alpha = 0.36f + 0.48f * frac),
                topLeft = Offset(left, top),
                size = Size(barW, h),
                cornerRadius = corner,
            )
        }
    }
}

private fun DrawScope.drawCornerReticles(
    pad: Float,
    len: Float,
    color: Color,
    strokeWidth: Float,
) {
    // Top-left
    drawLine(color, Offset(pad, pad), Offset(pad + len, pad), strokeWidth, StrokeCap.Round)
    drawLine(color, Offset(pad, pad), Offset(pad, pad + len), strokeWidth, StrokeCap.Round)
    // Top-right
    drawLine(color, Offset(size.width - pad, pad), Offset(size.width - pad - len, pad), strokeWidth, StrokeCap.Round)
    drawLine(color, Offset(size.width - pad, pad), Offset(size.width - pad, pad + len), strokeWidth, StrokeCap.Round)
    // Bottom-left
    drawLine(color, Offset(pad, size.height - pad), Offset(pad + len, size.height - pad), strokeWidth, StrokeCap.Round)
    drawLine(color, Offset(pad, size.height - pad), Offset(pad, size.height - pad - len), strokeWidth, StrokeCap.Round)
    // Bottom-right
    drawLine(color, Offset(size.width - pad, size.height - pad), Offset(size.width - pad - len, size.height - pad), strokeWidth, StrokeCap.Round)
    drawLine(color, Offset(size.width - pad, size.height - pad), Offset(size.width - pad, size.height - pad - len), strokeWidth, StrokeCap.Round)
}

@Composable
private fun PublishLiveStatusPill(
    text: String,
    phase: State<Float>?,
    palette: PublishDialogPalette,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = palette.primary.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.30f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Canvas(Modifier.size(8.dp)) {
                val t = phase?.value ?: 0.25f
                val pulse = 0.5f - 0.5f * cos(t * 6.0 * PI).toFloat()
                drawCircle(
                    color = palette.secondary.copy(alpha = 0.18f + 0.28f * pulse),
                    radius = size.minDimension * (0.34f + 0.16f * pulse),
                )
                drawCircle(
                    color = palette.primary,
                    radius = size.minDimension * 0.24f,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp,
                ),
                color = palette.primary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PublishCodeChip(
    text: String,
    palette: PublishDialogPalette,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = palette.panelSurface,
        border = BorderStroke(1.dp, palette.hairlineBorder),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            ),
            color = palette.onSurface.copy(alpha = 0.86f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

/**
 * 三阶段流程轨（连接大厅 -> 配置标签 -> 核验公开），清晰建立流程预期与进度锚点。
 */
@Composable
private fun PublishStepRail(
    activeStepIndex: Int,
    phase: State<Float>?,
    palette: PublishDialogPalette,
    modifier: Modifier = Modifier,
    allCompleted: Boolean = false,
) {
    val labels = remember {
        listOf(
            readI18n("multiplayer.room.publishStepConnect"),
            readI18n("multiplayer.room.publishStepConfigure"),
            readI18n("multiplayer.room.publishStepVerify"),
        )
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val completed = allCompleted || index < activeStepIndex
            val active = !allCompleted && index == activeStepIndex

            PublishStepNode(
                label = label,
                completed = completed,
                active = active,
                phase = phase,
                palette = palette,
            )

            if (index < labels.lastIndex) {
                val connectorCompleted = allCompleted || index < activeStepIndex
                val connectorStreaming = !allCompleted && (
                    index == activeStepIndex - 1 || (activeStepIndex == 0 && index == 0)
                )
                PublishStepConnector(
                    completed = connectorCompleted,
                    streaming = connectorStreaming,
                    phase = phase,
                    palette = palette,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                        .height(10.dp),
                )
            }
        }
    }
}

@Composable
private fun PublishStepNode(
    label: String,
    completed: Boolean,
    active: Boolean,
    phase: State<Float>?,
    palette: PublishDialogPalette,
) {
    val accent = if (completed) palette.successAccent else palette.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier.size(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (completed) {
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.20f))
                        .border(1.dp, accent.copy(alpha = 0.72f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(10.dp),
                    )
                }
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val t = phase?.value ?: 0.25f
                    val pulse = if (active) 0.5f - 0.5f * cos(t * 6.0 * PI).toFloat() else 0f
                    if (active) {
                        drawCircle(
                            color = palette.primary.copy(alpha = 0.16f + 0.24f * pulse),
                            radius = size.minDimension * (0.38f + 0.12f * pulse),
                        )
                        drawCircle(
                            color = palette.primary,
                            radius = size.minDimension * 0.24f,
                        )
                    } else {
                        drawCircle(
                            color = palette.onSurface.copy(alpha = 0.22f),
                            radius = size.minDimension * 0.24f,
                            style = Stroke(1.2.dp.toPx()),
                        )
                    }
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (active || completed) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = when {
                active -> palette.onSurface
                completed -> palette.onSurface.copy(alpha = 0.80f)
                else -> palette.onSurfaceVariant.copy(alpha = 0.62f)
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun PublishStepConnector(
    completed: Boolean,
    streaming: Boolean,
    phase: State<Float>?,
    palette: PublishDialogPalette,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val midY = size.height / 2f
        val strokePx = 1.4.dp.toPx()
        val trackColor = when {
            completed -> palette.primary.copy(alpha = 0.55f)
            else -> palette.onSurface.copy(alpha = 0.12f)
        }
        drawLine(
            color = trackColor,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
        if (streaming && phase != null && size.width > 12f) {
            val travel = (phase.value * 4f) % 1f
            val x = size.width * FastOutSlowInEasing.transform(travel)
            val dotAlpha = if (completed) 0.90f else 0.52f
            drawCircle(
                color = palette.primary.copy(alpha = dotAlpha * 0.35f),
                radius = 4.dp.toPx(),
                center = Offset(x, midY),
            )
            drawCircle(
                color = palette.highlight.copy(alpha = dotAlpha),
                radius = 1.9.dp.toPx(),
                center = Offset(x, midY),
            )
        }
    }
}

@Composable
private fun PublishWaitingTipStrip(
    tip: String,
    animated: Boolean,
    palette: PublishDialogPalette,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = palette.panelSurface,
        border = BorderStroke(1.dp, palette.hairlineBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .clip(CircleShape)
                    .background(palette.primary.copy(alpha = 0.80f)),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (animated) {
                    AnimatedContent(
                        targetState = tip,
                        transitionSpec = {
                            (fadeIn(tween(340, easing = StandardDecelerateEasing)) +
                                slideInVertically(tween(340, easing = EmphasizedDecelerateEasing)) { it / 3 })
                                .togetherWith(
                                    fadeOut(tween(220, easing = EmphasizedAccelerateEasing)) +
                                        slideOutVertically(tween(220, easing = EmphasizedAccelerateEasing)) { -it / 4 },
                                )
                        },
                        label = "publishWaitTip",
                    ) { currentTip ->
                        Text(
                            text = currentTip,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                            color = palette.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = tip,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = palette.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PublishConfigureStage(
    state: PublishToListUiState.SelectRoomType,
    defaultRoomName: String,
    roomCode: String,
    palette: PublishDialogPalette,
    animated: Boolean,
    compactHeight: Boolean,
    compactWidth: Boolean,
    onClose: () -> Unit,
    onPublish: (String, String) -> Unit,
) {
    val modSyncEnabled = state.modSyncEnabled
    // 多选状态：保持 Set 语义，但序列化时按服务端返回顺序过滤，保证请求稳定
    var selectedRoomTypes by remember(state.types) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var roomName by remember(defaultRoomName, state.allowCustomRoomName) {
        mutableStateOf(defaultRoomName)
    }
    var roomNameHintFlashTick by remember { mutableIntStateOf(0) }
    var roomNameHintHighlighted by remember { mutableStateOf(false) }

    val effectiveRoomName = if (state.allowCustomRoomName) {
        roomName.trim().ifBlank { defaultRoomName }
    } else {
        defaultRoomName
    }
    val orderedSelected = state.types.filter { it in selectedRoomTypes }
    // 未选手动标签时由 composePublishRoomType 默认补「默认」，允许直接点公开
    val effectiveRoomType = composePublishRoomType(orderedSelected, modSyncEnabled)
    val canPublish = effectiveRoomName.isNotBlank()

    val roomNameHintColor by animateUiColorAsState(
        targetValue = if (roomNameHintHighlighted) palette.primary else palette.onSurfaceVariant,
        animationSpec = tween(120),
        label = "roomNameHintColor",
    )
    val lockedCardBorderColor by animateUiColorAsState(
        targetValue = if (roomNameHintHighlighted) {
            palette.primary.copy(alpha = 0.72f)
        } else {
            palette.hairlineBorder
        },
        animationSpec = tween(120),
        label = "lockedRoomNameBorder",
    )

    LaunchedEffect(roomNameHintFlashTick, animated) {
        roomNameHintHighlighted = false
        if (!animated || roomNameHintFlashTick == 0) return@LaunchedEffect
        roomNameHintHighlighted = true
        delay(120)
        roomNameHintHighlighted = false
        delay(80)
        roomNameHintHighlighted = true
        delay(120)
        roomNameHintHighlighted = false
    }

    val horizontalPad = if (compactWidth) 16.dp else 22.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = if (compactHeight) 356.dp else 560.dp)
            .testTag("publish-select-stage"),
    ) {
        // 统一头部：状态胶囊 + 标题 + 预留 48dp 关闭按钮区域
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = horizontalPad,
                        end = 60.dp,
                        top = if (compactHeight) 14.dp else 18.dp,
                        bottom = if (compactHeight) 10.dp else 14.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = palette.primary.copy(alpha = 0.13f),
                        border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.28f)),
                    ) {
                        Text(
                            text = readI18n("multiplayer.room.publishBadgeReady"),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.3.sp,
                            ),
                            color = palette.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    if (roomCode.isNotBlank()) {
                        PublishCodeChip(text = roomCode, palette = palette)
                    }
                }
                Text(
                    text = readI18n("multiplayer.room.publishToList"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.onSurface,
                )
                Text(
                    text = readI18n("multiplayer.room.publishSelectTypeHint"),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                    color = palette.onSurfaceVariant,
                )
            }
            PublishDialogCloseButton(
                onClick = onClose,
                palette = palette,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp),
            )
        }

        // 三段流程锚点（连接大厅 ✓ -> 配置标签 ● -> 核验公开 ○）
        if (!compactHeight) {
            PublishStepRail(
                activeStepIndex = 1,
                phase = null,
                palette = palette,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = horizontalPad, end = horizontalPad, bottom = 12.dp),
            )
        }

        HorizontalDivider(thickness = 1.dp, color = palette.hairlineBorder)

        // 中部可滚动表单区
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPad, vertical = if (compactHeight) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 房间名称区块
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = readI18n("multiplayer.room.publishRoomNameLabel"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.onSurface,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (state.allowCustomRoomName) {
                            Text(
                                text = "${roomName.length}/32",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = palette.panelSurface,
                            border = BorderStroke(1.dp, palette.hairlineBorder),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (!state.allowCustomRoomName) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = palette.onSurfaceVariant,
                                        modifier = Modifier.size(11.dp),
                                    )
                                }
                                Text(
                                    text = readI18n(
                                        if (state.allowCustomRoomName) {
                                            "multiplayer.room.publishNameEditableBadge"
                                        } else {
                                            "multiplayer.room.publishNameLockedBadge"
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = palette.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (state.allowCustomRoomName) {
                    PublishRoomNameField(
                        value = roomName,
                        palette = palette,
                        onValueChange = { roomName = it },
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = palette.stageSurface,
                        border = BorderStroke(1.dp, lockedCardBorderColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) {
                                roomNameHintFlashTick++
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(palette.primary.copy(alpha = 0.85f)),
                            )
                            Text(
                                text = roomName,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = palette.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = palette.onSurfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Text(
                        text = readI18n("multiplayer.room.publishRoomNameHint"),
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                        color = roomNameHintColor,
                    )
                }
            }

            // 2. 模组同步自动附加说明（开启传输模组时展示）
            if (modSyncEnabled) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.primary.copy(alpha = if (palette.isDark) 0.11f else 0.08f),
                    border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.30f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = palette.primary,
                            modifier = Modifier.size(17.dp),
                        )
                        Text(
                            text = readI18n("multiplayer.room.publishModSyncAutoHint"),
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                            color = palette.onSurface.copy(alpha = 0.88f),
                        )
                    }
                }
            }

            // 3. 房间标签选择区
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = readI18n("multiplayer.room.publishSelectType"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.onSurface,
                    )
                    Text(
                        text = if (orderedSelected.isEmpty()) {
                            readI18n(
                                "multiplayer.room.publishTypeDefault",
                                I18nType.RWPP,
                                DEFAULT_PUBLISH_ROOM_TYPE,
                            )
                        } else {
                            readI18n(
                                "multiplayer.room.publishSelectedTypes",
                                I18nType.RWPP,
                                orderedSelected.size.toString(),
                            )
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = palette.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (modSyncEnabled) {
                        PublishLockedAccentTagChip(
                            label = readI18n("multiplayer.room.publishModSyncTagBadge"),
                            palette = palette,
                        )
                    }
                    state.types.forEach { type ->
                        val selected = type in selectedRoomTypes
                        PublishTagChip(
                            label = type,
                            selected = selected,
                            palette = palette,
                            onClick = {
                                selectedRoomTypes = if (selected) {
                                    selectedRoomTypes - type
                                } else {
                                    selectedRoomTypes + type
                                }
                            },
                        )
                    }
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = palette.hairlineBorder)

        // 底部固定操作栏：实时标签预览 + 主提交按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.stageSurface.copy(alpha = if (palette.isDark) 0.55f else 0.72f))
                .padding(horizontal = horizontalPad, vertical = if (compactHeight) 10.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = effectiveRoomName,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = effectiveRoomType.replace("|", " · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PublishPrimaryButton(
                label = readI18n("multiplayer.room.publishToList"),
                enabled = canPublish,
                palette = palette,
                leadingIcon = {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (canPublish) palette.onPrimary else palette.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                },
                onClick = { onPublish(effectiveRoomType, effectiveRoomName) },
                modifier = Modifier.testTag("publish-submit-button"),
            )
        }
    }
}

@Composable
private fun PublishRoomNameField(
    value: String,
    palette: PublishDialogPalette,
    onValueChange: (String) -> Unit,
) {
    val forwardEscapeOnDesktop = LocalAccountAuthDialogEscape.current
    val escapeScope = rememberCoroutineScope()
    fun filterInput(raw: String): String {
        val single = if (raw.contains("\n")) raw.split("\n").last() else raw
        return if (single.length <= 32) single else single.take(32)
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = palette.onSurface,
        unfocusedTextColor = palette.onSurface,
        focusedContainerColor = palette.stageSurface,
        unfocusedContainerColor = palette.stageSurface,
        cursorColor = palette.primary,
        focusedBorderColor = palette.primary.copy(alpha = 0.88f),
        unfocusedBorderColor = palette.onSurface.copy(alpha = if (palette.isDark) 0.20f else 0.24f),
    )
    val baseModifier = Modifier
        .fillMaxWidth()
        .then(
            if (forwardEscapeOnDesktop) Modifier.desktopEscapeToBackDispatcher(escapeScope) else Modifier,
        )

    if (forwardEscapeOnDesktop) {
        var textFieldValue by remember {
            mutableStateOf(TextFieldValue(value, TextRange(value.length)))
        }
        SideEffect {
            if (textFieldValue.text != value) {
                textFieldValue = TextFieldValue(text = value, selection = TextRange(value.length))
            }
        }
        OutlinedTextField(
            value = textFieldValue,
            onValueChange = { incoming ->
                val filtered = filterInput(incoming.text)
                textFieldValue = accountTextFieldEdit(incoming, filtered)
                onValueChange(filtered)
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            colors = fieldColors,
            modifier = baseModifier.testTag("publish-room-name-field"),
        )
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(filterInput(it)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            colors = fieldColors,
            modifier = baseModifier.testTag("publish-room-name-field"),
        )
    }
}

@Composable
private fun PublishTagChip(
    label: String,
    selected: Boolean,
    palette: PublishDialogPalette,
    onClick: () -> Unit,
) {
    val containerColor by animateUiColorAsState(
        targetValue = if (selected) {
            palette.primary.copy(alpha = if (palette.isDark) 0.20f else 0.14f)
        } else {
            palette.panelSurface
        },
        animationSpec = tween(150),
        label = "publishTagBg",
    )
    val borderColor by animateUiColorAsState(
        targetValue = if (selected) {
            palette.primary.copy(alpha = 0.78f)
        } else {
            palette.hairlineBorder
        },
        animationSpec = tween(150),
        label = "publishTagBorder",
    )
    val contentColor by animateUiColorAsState(
        targetValue = if (selected) palette.primary else palette.onSurface.copy(alpha = 0.86f),
        animationSpec = tween(150),
        label = "publishTagText",
    )
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .clip(shape)
            .background(containerColor, shape)
            .border(BorderStroke(if (selected) 1.2.dp else 1.dp, borderColor), shape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PublishLockedAccentTagChip(
    label: String,
    palette: PublishDialogPalette,
) {
    val accent = if (palette.isDark) {
        lerp(palette.primary, palette.secondary, 0.45f)
    } else {
        palette.primary
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(accent.copy(alpha = if (palette.isDark) 0.16f else 0.12f), shape)
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.48f)), shape)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PublishSuccessStage(
    roomId: String,
    roomName: String,
    palette: PublishDialogPalette,
    animated: Boolean,
    compactHeight: Boolean,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    // 一次性入场扩散确认环，结束后零帧调度
    val confirmWave = remember { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(animated) {
        if (animated) {
            confirmWave.snapTo(0f)
            confirmWave.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 620, easing = EmphasizedDecelerateEasing),
            )
        } else {
            confirmWave.snapTo(1f)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("publish-success-stage"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (compactHeight) 18.dp else 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compactHeight) 10.dp else 14.dp),
        ) {
            PublishResultEmblem(
                accent = palette.successAccent,
                isSuccess = true,
                palette = palette,
                waveProgress = confirmWave.asState(),
                size = if (compactHeight) 68.dp else 82.dp,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = readI18n("multiplayer.room.publishSuccess"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = readI18n("multiplayer.room.publishSuccessHint"),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = palette.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (roomName.isNotBlank() || roomId.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.panelSurface,
                    border = BorderStroke(1.dp, palette.successAccent.copy(alpha = 0.28f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(palette.successAccent),
                        )
                        Text(
                            text = roomName.ifBlank { roomId },
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (!compactHeight) {
                PublishStepRail(
                    activeStepIndex = 2,
                    phase = null,
                    palette = palette,
                    allCompleted = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }

            PublishPrimaryButton(
                label = readI18n("common.ok"),
                enabled = true,
                palette = palette,
                onClick = onConfirm,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .widthIn(min = 160.dp),
            )
        }

        PublishDialogCloseButton(
            onClick = onClose,
            palette = palette,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
        )
    }
}

@Composable
private fun PublishFailureStage(
    message: String,
    step: PublishStep,
    palette: PublishDialogPalette,
    compactHeight: Boolean,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("publish-failure-stage"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (compactHeight) 18.dp else 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compactHeight) 10.dp else 14.dp),
        ) {
            PublishResultEmblem(
                accent = palette.warningAccent,
                isSuccess = false,
                palette = palette,
                waveProgress = null,
                size = if (compactHeight) 64.dp else 76.dp,
            )

            Text(
                text = readI18n("multiplayer.room.publishFailed"),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = palette.onSurface,
                textAlign = TextAlign.Center,
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.warningAccent.copy(alpha = if (palette.isDark) 0.10f else 0.08f),
                border = BorderStroke(1.dp, palette.warningAccent.copy(alpha = 0.30f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                    color = palette.onSurface.copy(alpha = 0.88f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 112.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PublishSecondaryButton(
                    label = readI18n("common.close"),
                    palette = palette,
                    onClick = onDismiss,
                    modifier = Modifier.widthIn(min = 112.dp),
                )
                PublishPrimaryButton(
                    label = readI18n("settings.retry"),
                    enabled = true,
                    palette = palette,
                    leadingIcon = {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = palette.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    onClick = onRetry,
                    modifier = Modifier.widthIn(min = 124.dp),
                )
            }
        }

        PublishDialogCloseButton(
            onClick = onClose,
            palette = palette,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
        )
    }
}

@Composable
private fun PublishResultEmblem(
    accent: Color,
    isSuccess: Boolean,
    palette: PublishDialogPalette,
    waveProgress: State<Float>?,
    size: Dp,
) {
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = this.size.minDimension * 0.48f
            val hairline = 1.dp.toPx()

            drawCircle(
                color = accent.copy(alpha = if (palette.isDark) 0.14f else 0.11f),
                radius = radius * 0.96f,
            )
            drawCircle(
                color = accent.copy(alpha = 0.30f),
                radius = radius * 0.86f,
                style = Stroke(hairline),
            )
            drawCircle(
                color = accent.copy(alpha = if (palette.isDark) 0.22f else 0.16f),
                radius = radius * 0.62f,
            )
            drawCircle(
                color = accent.copy(alpha = 0.65f),
                radius = radius * 0.62f,
                style = Stroke(1.3.dp.toPx()),
            )
            val p = waveProgress?.value
            if (p != null && p < 0.995f) {
                val fade = (1f - p).pow(1.8f)
                drawCircle(
                    color = accent.copy(alpha = fade * 0.58f),
                    radius = radius * (0.58f + 0.52f * p),
                    style = Stroke(1.6.dp.toPx()),
                )
            }
        }
        Icon(
            imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

/** 48dp 标准命中区的现代化圆形关闭按钮。 */
@Composable
private fun PublishDialogCloseButton(
    onClick: () -> Unit,
    palette: PublishDialogPalette,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .testTag("publish-close-button"),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(palette.panelSurface)
                .border(1.dp, palette.hairlineBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = readI18n("common.close"),
                tint = palette.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 高级主题填充主按钮（桌面无动态阴影扩边，保留无障碍语义与清晰对比）。 */
@Composable
private fun PublishPrimaryButton(
    label: String,
    enabled: Boolean,
    palette: PublishDialogPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    val bgColor = if (enabled) palette.primary else palette.panelSurface
    val textColor = if (enabled) palette.onPrimary else palette.onSurfaceVariant.copy(alpha = 0.55f)

    Box(
        modifier = modifier
            .heightIn(min = 42.dp)
            .clip(shape)
            .background(bgColor, shape)
            .then(
                if (!enabled) Modifier.border(1.dp, palette.hairlineBorder, shape) else Modifier,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            leadingIcon?.invoke()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = textColor,
                maxLines = 1,
            )
        }
    }
}

/** 轻量描边次级按钮（用于「停止申请」与「关闭」）。 */
@Composable
private fun PublishSecondaryButton(
    label: String,
    palette: PublishDialogPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(palette.panelSurface, shape)
            .border(1.dp, palette.hairlineBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leadingIcon?.invoke()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = palette.onSurface.copy(alpha = 0.88f),
                maxLines = 1,
            )
        }
    }
}
