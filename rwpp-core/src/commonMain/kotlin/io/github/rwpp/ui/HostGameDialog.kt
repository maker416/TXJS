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
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.game.mod.Mod
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.HostCommandPrefix
import io.github.rwpp.net.QuickHostLimits
import io.github.rwpp.net.QuickHostRoomOptions
import io.github.rwpp.net.T_ROOM_ADDRESS_PREFIX
import io.github.rwpp.net.quickHostSkipsClientVanillaModClear
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.extension_30
import io.github.rwpp.widget.AnimatedAlertDialogWithMotion
import io.github.rwpp.widget.LocalAccountAuthDialogEscape
import io.github.rwpp.widget.RWSelectionColors
import io.github.rwpp.widget.UiSwitch
import io.github.rwpp.widget.accountTextFieldEdit
import io.github.rwpp.widget.animateUiColorAsState
import io.github.rwpp.widget.animateUiFloatAsState
import io.github.rwpp.widget.desktopEscapeToBackDispatcher
import io.github.rwpp.widget.uiAnimationsEnabled
import org.jetbrains.compose.resources.painterResource

private const val HOST_DIALOG_MAX_WIDTH_DP = 628
private const val HOST_CONFIRM_MAX_WIDTH_DP = 468
private const val HOST_ROOM_ID_MAX_LEN = 10
private const val HOST_NUMERIC_FIELD_MAX_LEN = 9
private const val HOST_MAX_PLAYER_CAP = 100

/** 开房弹窗当前模组状态只读快照，避免组合期间反复扫描引擎模组表。 */
@Stable
internal data class HostModInventory(
    val enabledLocalCount: Int = 0,
    val hasEnabledMods: Boolean = false,
    val hasNetworkMods: Boolean = false,
    val hasEnabledNetworkMods: Boolean = false,
) {
    companion object {
        fun fromMods(mods: Collection<Mod>): HostModInventory {
            var enabledLocal = 0
            var hasEnabled = false
            var hasNetwork = false
            var hasEnabledNetwork = false
            for (mod in mods) {
                if (mod.isNetworkMod) {
                    hasNetwork = true
                    if (mod.isEnabled) {
                        hasEnabled = true
                        hasEnabledNetwork = true
                    }
                } else if (mod.isEnabled) {
                    hasEnabled = true
                    enabledLocal++
                }
            }
            return HostModInventory(
                enabledLocalCount = enabledLocal,
                hasEnabledMods = hasEnabled,
                hasNetworkMods = hasNetwork,
                hasEnabledNetworkMods = hasEnabledNetwork,
            )
        }
    }
}

/** 创建房间表单状态；协议切换、参数重置与最终指令构建共用单一数据源。 */
@Stable
internal data class HostGameFormState(
    val prefix: HostCommandPrefix = HostCommandPrefix.Q,
    val enableMods: Boolean = false,
    val transferMod: Boolean = false,
    val roomId: String = "",
    val maxPlayer: Int? = null,
    val unitLimit: Int? = null,
    val credits: Int? = null,
    val speedMultiplier: Int? = null,
) {
    val canSetRoomId: Boolean
        get() = prefix != HostCommandPrefix.R

    val customParamCount: Int
        get() = listOf(maxPlayer, unitLimit, credits, speedMultiplier).count { it != null }

    val hasAnyCustomInput: Boolean
        get() = customParamCount > 0 || (canSetRoomId && roomId.isNotBlank())

    fun toQuickHostRoomOptions(): QuickHostRoomOptions = QuickHostRoomOptions(
        prefix = prefix,
        enableMods = enableMods,
        roomId = if (canSetRoomId) roomId.ifBlank { null } else null,
        limits = QuickHostLimits(
            maxPlayer = maxPlayer,
            unitLimit = unitLimit,
            credits = credits,
            speedMultiplier = speedMultiplier,
        ),
    )

    fun summaryParamDigest(): String = buildList {
        if (canSetRoomId && roomId.isNotBlank()) {
            val formattedId = when (prefix) {
                HostCommandPrefix.T -> "$T_ROOM_ADDRESS_PREFIX$roomId"
                HostCommandPrefix.Q -> "Q$roomId"
                HostCommandPrefix.R -> "R$roomId"
            }
            add(formattedId)
        }
        maxPlayer?.let { add("P$it") }
        unitLimit?.let { add("U$it") }
        credits?.let { add("C$it") }
        speedMultiplier?.let { add("Z${it}x") }
    }.joinToString(" · ")
}

internal enum class HostDialogStage {
    Configure,
    NetworkModHint,
    TransferModDisclaimer,
    VanillaClearModsConfirm,
}

internal data class HostGameSubmission(
    val options: QuickHostRoomOptions,
    val enableMods: Boolean,
    val transferMod: Boolean,
)

private val HostProtocolOrder = listOf(
    HostCommandPrefix.T,
    HostCommandPrefix.Q,
    HostCommandPrefix.R,
)

@Stable
private data class HostDialogPalette(
    val isDark: Boolean,
    val cardSurface: Color,
    val stageSurface: Color,
    val panelSurface: Color,
    val panelHoverSurface: Color,
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
    val warningAccent: Color,
)

@Composable
private fun rememberHostDialogPalette(colors: ColorScheme): HostDialogPalette {
    val isDark = colors.background.luminance() < 0.5f
    return remember(colors, isDark) {
        val cardSurface = if (isDark) {
            lerp(colors.background, Color(0xFF0E1110), 0.54f).copy(alpha = 0.97f)
        } else {
            colors.surface.copy(alpha = 0.985f)
        }
        val stageSurface = if (isDark) {
            lerp(colors.background, Color(0xFF060807), 0.72f)
        } else {
            lerp(colors.surface, colors.primary, 0.045f)
        }
        val panelSurface = if (isDark) {
            lerp(colors.background, Color(0xFF101312), 0.36f).copy(alpha = 0.78f)
        } else {
            lerp(colors.surface, colors.onSurface, 0.04f)
        }
        val panelHoverSurface = if (isDark) {
            lerp(colors.background, Color(0xFF1C211E), 0.32f).copy(alpha = 0.88f)
        } else {
            lerp(colors.surface, colors.onSurface, 0.075f)
        }
        val panelHighlight = if (isDark) {
            lerp(colors.background, colors.primary, 0.16f).copy(alpha = 0.84f)
        } else {
            colors.primary.copy(alpha = 0.10f)
        }
        val highlight = lerp(
            colors.primary,
            if (isDark) Color.White else colors.secondary,
            if (isDark) 0.46f else 0.25f,
        )
        val warningAccent = if (isDark) {
            Color(0xFFF3AA4E)
        } else {
            Color(0xFFC56B0C)
        }
        HostDialogPalette(
            isDark = isDark,
            cardSurface = cardSurface,
            stageSurface = stageSurface,
            panelSurface = panelSurface,
            panelHoverSurface = panelHoverSurface,
            panelHighlight = panelHighlight,
            borderTop = lerp(colors.onSurface, colors.primary, 0.42f).copy(alpha = if (isDark) 0.24f else 0.22f),
            borderBottom = colors.onSurface.copy(alpha = if (isDark) 0.09f else 0.12f),
            hairlineBorder = colors.onSurface.copy(alpha = if (isDark) 0.11f else 0.13f),
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
            warningAccent = warningAccent,
        )
    }
}

@Composable
internal fun HostGameDialog(
    visible: Boolean,
    modInventory: HostModInventory,
    isAccountLoggedIn: Boolean,
    onDismissRequest: () -> Unit,
    onRequireAccountLogin: () -> Unit,
    onSubmitHost: (HostGameSubmission) -> Boolean,
) {
    val animated = uiAnimationsEnabled()
    val density = LocalDensity.current
    val maxLiftPx = with(density) { 20.dp.roundToPx() }
    val maxDropPx = with(density) { 14.dp.roundToPx() }

    val enter = if (animated) {
        fadeIn(tween(210, delayMillis = 15, easing = StandardDecelerateEasing)) +
            slideInVertically(tween(280, easing = EmphasizedDecelerateEasing)) { minOf(it / 18, maxLiftPx) } +
            scaleIn(
                tween(280, easing = EmphasizedDecelerateEasing),
                initialScale = 0.962f,
                transformOrigin = TransformOrigin(0.5f, 0.46f),
            )
    } else {
        EnterTransition.None
    }
    val exit = if (animated) {
        fadeOut(tween(155, easing = EmphasizedAccelerateEasing)) +
            slideOutVertically(tween(175, easing = EmphasizedAccelerateEasing)) { minOf(it / 24, maxDropPx) } +
            scaleOut(
                tween(175, easing = EmphasizedAccelerateEasing),
                targetScale = 0.972f,
                transformOrigin = TransformOrigin(0.5f, 0.46f),
            )
    } else {
        ExitTransition.None
    }

    AnimatedAlertDialogWithMotion(
        visible = visible,
        onDismissRequest = onDismissRequest,
        enter = enter,
        exit = exit,
    ) { dismiss ->
        var form by remember { mutableStateOf(HostGameFormState()) }
        var stage by remember { mutableStateOf(HostDialogStage.Configure) }

        BackHandler(stage != HostDialogStage.Configure) {
            stage = HostDialogStage.Configure
        }

        HostGameDialogCard(
            form = form,
            stage = stage,
            modInventory = modInventory,
            isAccountLoggedIn = isAccountLoggedIn,
            onFormChange = { form = it },
            onStageChange = { stage = it },
            onRequireAccountLogin = onRequireAccountLogin,
            onDismiss = dismiss,
            onSubmit = { submission ->
                if (onSubmitHost(submission)) {
                    dismiss()
                }
            },
        )
    }
}

/**
 * 创建房间弹窗主体（独立提取，便于桌面离线、动效、主题及多视口回归测试）。
 */
@Composable
internal fun HostGameDialogCard(
    form: HostGameFormState,
    stage: HostDialogStage,
    modInventory: HostModInventory,
    isAccountLoggedIn: Boolean,
    onFormChange: (HostGameFormState) -> Unit,
    onStageChange: (HostDialogStage) -> Unit,
    onRequireAccountLogin: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (HostGameSubmission) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enableAnimations = uiAnimationsEnabled()
    val palette = rememberHostDialogPalette(MaterialTheme.colorScheme)
    val cardShape = RoundedCornerShape(22.dp)
    val borderBrush = remember(palette.borderTop, palette.hairlineBorder, palette.borderBottom) {
        Brush.verticalGradient(
            0.0f to palette.borderTop,
            0.35f to palette.hairlineBorder,
            1.0f to palette.borderBottom,
        )
    }

    val targetAuraTint = when {
        stage != HostDialogStage.Configure -> palette.warningAccent
        form.enableMods && form.transferMod -> lerp(palette.primary, palette.secondary, 0.35f)
        else -> palette.primary
    }
    val auraTintState = animateUiColorAsState(
        targetValue = targetAuraTint,
        animationSpec = tween(220, easing = StandardDecelerateEasing),
        label = "hostDialogAuraTint",
    )

    fun submitNow() {
        if (form.transferMod && form.enableMods && !isAccountLoggedIn) {
            onRequireAccountLogin()
            return
        }
        onSubmit(
            HostGameSubmission(
                options = form.toQuickHostRoomOptions(),
                enableMods = form.enableMods,
                transferMod = form.transferMod && form.enableMods,
            ),
        )
    }

    fun requestHost() {
        if (form.transferMod && form.enableMods && !isAccountLoggedIn) {
            onRequireAccountLogin()
            return
        }
        val needsVanillaClearConfirm = !form.enableMods &&
            modInventory.hasEnabledMods &&
            !quickHostSkipsClientVanillaModClear(form.prefix)
        if (needsVanillaClearConfirm) {
            onStageChange(HostDialogStage.VanillaClearModsConfirm)
        } else {
            submitNow()
        }
    }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // 关键约束顺序：先 padding + widthIn(max) 再让内部按阶段宽度自适应，
    // 宽屏稳定锁在精致战术弹窗比例，确认子阶段可平滑收窄并保持高度过渡零裁剪。
    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .widthIn(max = HOST_DIALOG_MAX_WIDTH_DP.dp)
            .clip(cardShape)
            .background(palette.cardSurface)
            .border(BorderStroke(1.dp, borderBrush), cardShape)
            .pointerInput(Unit) {
                detectTapGestures {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
            }
            .testTag("host-game-dialog"),
    ) {
        val compactHeight = constraints.hasBoundedHeight && maxHeight < 460.dp
        val compactWidth = maxWidth < 480.dp
        val maxCardHeight = if (constraints.hasBoundedHeight) (maxHeight - 4.dp).coerceAtLeast(240.dp) else Dp.Unspecified

        // 顶部环境极光与镜面细线层：在绘制阶段读取 tint，阶段切换时零重组
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val auraTint = auraTintState.value
                    val auraHeight = 250.dp.toPx().coerceAtMost(size.height)
                    val leftRadius = (size.width * 0.46f)
                        .coerceAtMost(auraHeight * 0.94f)
                        .coerceAtLeast(110.dp.toPx())
                    val topLeftAura = Brush.radialGradient(
                        0.00f to auraTint.copy(alpha = if (palette.isDark) 0.14f else 0.085f),
                        0.58f to auraTint.copy(alpha = if (palette.isDark) 0.045f else 0.024f),
                        1.00f to Color.Transparent,
                        center = Offset(size.width * 0.16f, 0f),
                        radius = leftRadius,
                    )
                    val rightCenterY = 18.dp.toPx()
                    val rightRadius = (size.width * 0.36f)
                        .coerceAtMost((auraHeight - rightCenterY).coerceAtLeast(60.dp.toPx()) * 0.92f)
                        .coerceAtLeast(90.dp.toPx())
                    val topRightAura = Brush.radialGradient(
                        0.00f to palette.secondary.copy(alpha = if (palette.isDark) 0.07f else 0.045f),
                        0.60f to palette.secondary.copy(alpha = if (palette.isDark) 0.02f else 0.012f),
                        1.00f to Color.Transparent,
                        center = Offset(size.width * 0.85f, rightCenterY),
                        radius = rightRadius,
                    )
                    val edgeInset = 24.dp.toPx()
                    val strokePx = 1.dp.toPx()
                    val topCatchlight = Brush.horizontalGradient(
                        0.00f to Color.Transparent,
                        0.24f to auraTint.copy(alpha = if (palette.isDark) 0.62f else 0.44f),
                        0.55f to palette.highlight.copy(alpha = if (palette.isDark) 0.80f else 0.54f),
                        0.82f to palette.secondary.copy(alpha = if (palette.isDark) 0.42f else 0.28f),
                        1.00f to Color.Transparent,
                        startX = edgeInset,
                        endX = (size.width - edgeInset).coerceAtLeast(edgeInset + 1f),
                    )
                    drawRect(topLeftAura, size = Size(size.width, auraHeight))
                    drawRect(topRightAura, size = Size(size.width, auraHeight))
                    drawLine(
                        brush = topCatchlight,
                        start = Offset(edgeInset, strokePx),
                        end = Offset((size.width - edgeInset).coerceAtLeast(edgeInset), strokePx),
                        strokeWidth = strokePx,
                    )
                },
        )

        AnimatedContent(
            targetState = stage,
            contentAlignment = Alignment.Center,
            transitionSpec = {
                if (enableAnimations) {
                    // 编排式 Fade-Through：旧态 80ms 先退场，55ms 后平滑过渡容器尺寸，75ms 后新态柔入，避免重影与底边硬切。
                    (fadeIn(tween(190, delayMillis = 75, easing = StandardDecelerateEasing)) +
                        scaleIn(
                            tween(210, delayMillis = 75, easing = EmphasizedDecelerateEasing),
                            initialScale = 0.985f,
                        ))
                        .togetherWith(fadeOut(tween(80, easing = FastOutSlowInEasing)))
                        .using(
                            SizeTransform(clip = true) { _, _ ->
                                tween(durationMillis = 210, delayMillis = 55, easing = EmphasizedDecelerateEasing)
                            },
                        )
                } else {
                    (EnterTransition.None togetherWith ExitTransition.None).using(null)
                }
            },
            label = "hostGameDialogStage",
        ) { currentStage ->
            when (currentStage) {
                HostDialogStage.Configure -> HostConfigureStage(
                    form = form,
                    modInventory = modInventory,
                    isAccountLoggedIn = isAccountLoggedIn,
                    palette = palette,
                    animated = enableAnimations,
                    compactWidth = compactWidth,
                    compactHeight = compactHeight,
                    maxCardHeight = maxCardHeight,
                    onFormChange = onFormChange,
                    onToggleEnableMods = {
                        if (!form.enableMods) {
                            onFormChange(form.copy(enableMods = true))
                            if (modInventory.hasNetworkMods) {
                                onStageChange(HostDialogStage.NetworkModHint)
                            }
                        } else {
                            onFormChange(form.copy(enableMods = false, transferMod = false))
                        }
                    },
                    onToggleTransferMod = {
                        if (!form.enableMods) return@HostConfigureStage
                        if (!form.transferMod) {
                            if (!isAccountLoggedIn) {
                                onRequireAccountLogin()
                            } else {
                                onStageChange(HostDialogStage.TransferModDisclaimer)
                            }
                        } else {
                            onFormChange(form.copy(transferMod = false))
                        }
                    },
                    onDismiss = onDismiss,
                    onHostClick = ::requestHost,
                )

                HostDialogStage.NetworkModHint -> HostNoticeStage(
                    badgeText = readI18n("multiplayer.hostDialog.noticeBadge"),
                    title = readI18n("multiplayer.networkModHostHintTitle"),
                    message = readI18n("multiplayer.networkModHostBlocked"),
                    confirmLabel = readI18n("multiplayer.networkModHostHintConfirm"),
                    cancelLabel = null,
                    palette = palette,
                    compactHeight = compactHeight,
                    maxCardHeight = maxCardHeight,
                    stageTag = "host-network-mod-hint-stage",
                    onBack = { onStageChange(HostDialogStage.Configure) },
                    onConfirm = { onStageChange(HostDialogStage.Configure) },
                )

                HostDialogStage.TransferModDisclaimer -> HostNoticeStage(
                    badgeText = readI18n("multiplayer.hostDialog.confirmBadge"),
                    title = readI18n("multiplayer.transferModDisclaimerTitle"),
                    message = readI18n("multiplayer.transferModDisclaimerMessage").trimIndent(),
                    confirmLabel = readI18n("multiplayer.transferModDisclaimerAccept"),
                    cancelLabel = readI18n("common.cancel"),
                    palette = palette,
                    compactHeight = compactHeight,
                    maxCardHeight = maxCardHeight,
                    stageTag = "host-transfer-disclaimer-stage",
                    onBack = { onStageChange(HostDialogStage.Configure) },
                    onConfirm = {
                        onFormChange(form.copy(transferMod = true))
                        onStageChange(HostDialogStage.Configure)
                    },
                )

                HostDialogStage.VanillaClearModsConfirm -> HostNoticeStage(
                    badgeText = readI18n("multiplayer.hostDialog.confirmBadge"),
                    title = readI18n("multiplayer.vanillaHostClearModsTitle"),
                    message = readI18n("multiplayer.vanillaHostClearModsMessage").trimIndent(),
                    confirmLabel = readI18n("multiplayer.vanillaHostClearModsAccept"),
                    cancelLabel = readI18n("common.cancel"),
                    palette = palette,
                    compactHeight = compactHeight,
                    maxCardHeight = maxCardHeight,
                    stageTag = "host-vanilla-clear-confirm-stage",
                    onBack = { onStageChange(HostDialogStage.Configure) },
                    onConfirm = ::submitNow,
                )
            }
        }
    }
}

@Composable
private fun HostConfigureStage(
    form: HostGameFormState,
    modInventory: HostModInventory,
    isAccountLoggedIn: Boolean,
    palette: HostDialogPalette,
    animated: Boolean,
    compactWidth: Boolean,
    compactHeight: Boolean,
    maxCardHeight: Dp,
    onFormChange: (HostGameFormState) -> Unit,
    onToggleEnableMods: () -> Unit,
    onToggleTransferMod: () -> Unit,
    onDismiss: () -> Unit,
    onHostClick: () -> Unit,
) {
    val horizontalPad = if (compactWidth) 16.dp else 22.dp
    val sectionSpacing = if (compactHeight) 11.dp else 13.dp
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxCardHeight)
            .testTag("host-configure-stage"),
    ) {
        // 顶部固定标题区：胶囊徽标 + 协议识别码 + 标题 + 副标题 + 48dp 关闭按钮
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = horizontalPad,
                        end = 60.dp,
                        top = if (compactHeight) 10.dp else 14.dp,
                        bottom = if (compactHeight) 8.dp else 11.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = palette.primary.copy(alpha = if (palette.isDark) 0.14f else 0.11f),
                        border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.30f)),
                    ) {
                        Text(
                            text = readI18n("multiplayer.hostDialog.badge"),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.35.sp,
                            ),
                            color = palette.primary,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
                        )
                    }
                    HostProtocolModeChip(
                        prefix = form.prefix,
                        animated = animated,
                        palette = palette,
                    )
                }
                Text(
                    text = readI18n("multiplayer.host"),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("host-dialog-title"),
                )
                if (!compactHeight) {
                    Text(
                        text = readI18n("multiplayer.hostDialog.subtitle"),
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                        color = palette.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HostDialogCloseButton(
                onClick = onDismiss,
                palette = palette,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp),
            )
        }

        HorizontalDivider(thickness = 1.dp, color = palette.hairlineBorder)

        // 中部内容区：桌面自然包裹不留空洞，窄屏或低高度横屏平滑滚动并在绘制层提示滚动条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .drawWithContent {
                    drawContent()
                    val maxScroll = scrollState.maxValue
                    if (maxScroll > 8.dp.toPx() && size.height > 24.dp.toPx()) {
                        val trackPadY = 8.dp.toPx()
                        val trackHeight = (size.height - trackPadY * 2f).coerceAtLeast(1f)
                        val viewportRatio = size.height / (size.height + maxScroll.toFloat())
                        val thumbHeight = (trackHeight * viewportRatio).coerceIn(24.dp.toPx(), trackHeight)
                        val progress = (scrollState.value.toFloat() / maxScroll.toFloat()).coerceIn(0f, 1f)
                        val thumbY = trackPadY + (trackHeight - thumbHeight) * progress
                        val thumbWidth = 3.dp.toPx()
                        val thumbX = size.width - thumbWidth - 4.dp.toPx()
                        drawRoundRect(
                            color = palette.primary.copy(alpha = if (palette.isDark) 0.42f else 0.36f),
                            topLeft = Offset(thumbX, thumbY),
                            size = Size(thumbWidth, thumbHeight),
                            cornerRadius = CornerRadius(thumbWidth * 0.5f, thumbWidth * 0.5f),
                        )
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = horizontalPad, vertical = if (compactHeight) 10.dp else 13.dp)
                    .testTag("host-configure-scroll"),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing),
            ) {
                // 1. 开房协议与指定房号
                HostProtocolSection(
                    prefix = form.prefix,
                    roomId = form.roomId,
                    palette = palette,
                    animated = animated,
                    compactWidth = compactWidth,
                    onSelectPrefix = { nextPrefix ->
                        onFormChange(
                            form.copy(
                                prefix = nextPrefix,
                                roomId = if (nextPrefix == HostCommandPrefix.R) "" else form.roomId,
                            ),
                        )
                    },
                    onRoomIdChange = { nextRoomId ->
                        onFormChange(form.copy(roomId = nextRoomId))
                    },
                )

                // 2. 模组与同步选项
                HostModsSection(
                    enableMods = form.enableMods,
                    transferMod = form.transferMod,
                    modInventory = modInventory,
                    isAccountLoggedIn = isAccountLoggedIn,
                    palette = palette,
                    compactWidth = compactWidth,
                    onToggleEnableMods = onToggleEnableMods,
                    onToggleTransferMod = onToggleTransferMod,
                )

                // 3. 房间信息（2×2 规则参数矩阵）
                HostParametersSection(
                    form = form,
                    palette = palette,
                    animated = animated,
                    onFormChange = onFormChange,
                    onResetAll = {
                        onFormChange(
                            form.copy(
                                roomId = "",
                                maxPlayer = null,
                                unitLimit = null,
                                credits = null,
                                speedMultiplier = null,
                            ),
                        )
                    },
                )
            }
        }

        HorizontalDivider(thickness = 1.dp, color = palette.hairlineBorder)

        // 底部固定操作栏：实时配置摘要 + 高对比创建房间按钮
        HostConfigureFooter(
            form = form,
            palette = palette,
            animated = animated,
            compactWidth = compactWidth,
            compactHeight = compactHeight,
            onHostClick = onHostClick,
        )
    }
}

@Composable
private fun HostProtocolModeChip(
    prefix: HostCommandPrefix,
    animated: Boolean,
    palette: HostDialogPalette,
) {
    val label = protocolTitle(prefix)
    val subtitle = protocolSubtitle(prefix, compactWidth = false)
    Surface(
        shape = CircleShape,
        color = palette.panelSurface,
        border = BorderStroke(1.dp, palette.hairlineBorder),
    ) {
        if (animated) {
            AnimatedContent(
                targetState = "$label · $subtitle",
                transitionSpec = {
                    fadeIn(tween(150, easing = StandardDecelerateEasing)) togetherWith
                        fadeOut(tween(90, easing = FastOutSlowInEasing))
                },
                label = "hostHeaderProtocolChip",
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                    ),
                    color = palette.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
                )
            }
        } else {
            Text(
                text = "$label · $subtitle",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                ),
                color = palette.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun HostSectionHeader(
    title: String,
    palette: HostDialogPalette,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .clip(CircleShape)
                    .background(palette.primary),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = palette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun HostProtocolSection(
    prefix: HostCommandPrefix,
    roomId: String,
    palette: HostDialogPalette,
    animated: Boolean,
    compactWidth: Boolean,
    onSelectPrefix: (HostCommandPrefix) -> Unit,
    onRoomIdChange: (String) -> Unit,
) {
    val selectedIndex = HostProtocolOrder.indexOf(prefix).coerceAtLeast(0)
    // 滑块进度仅在 drawBehind 中读取，滑动全程零重组
    val glidingIndex = animateUiFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = tween(durationMillis = 240, easing = EmphasizedDecelerateEasing),
        label = "hostProtocolGlider",
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HostSectionHeader(
            title = readI18n("multiplayer.hostPrefix"),
            palette = palette,
        )

        // 三段式平滑滑轨选择器（T / Q / R）
        val railShape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(railShape)
                .background(palette.stageSurface)
                .border(BorderStroke(1.dp, palette.hairlineBorder), railShape)
                .padding(4.dp)
                .drawBehind {
                    val count = HostProtocolOrder.size
                    val gapPx = 4.dp.toPx()
                    val segWidth = ((size.width - gapPx * (count - 1)) / count).coerceAtLeast(0f)
                    val leftX = glidingIndex.value * (segWidth + gapPx)
                    val radius = 10.dp.toPx()
                    val strokePx = 1.2.dp.toPx()

                    drawRoundRect(
                        color = palette.panelHighlight,
                        topLeft = Offset(leftX, 0f),
                        size = Size(segWidth, size.height),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                    drawRoundRect(
                        color = palette.primary.copy(alpha = if (palette.isDark) 0.72f else 0.62f),
                        topLeft = Offset(leftX + strokePx * 0.5f, strokePx * 0.5f),
                        size = Size((segWidth - strokePx).coerceAtLeast(0f), (size.height - strokePx).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(strokePx),
                    )
                    val shimmerInset = 12.dp.toPx().coerceAtMost(segWidth * 0.25f)
                    if (segWidth > shimmerInset * 2f) {
                        drawLine(
                            brush = Brush.horizontalGradient(
                                0.0f to Color.Transparent,
                                0.5f to palette.highlight.copy(alpha = if (palette.isDark) 0.75f else 0.45f),
                                1.0f to Color.Transparent,
                                startX = leftX + shimmerInset,
                                endX = leftX + segWidth - shimmerInset,
                            ),
                            start = Offset(leftX + shimmerInset, strokePx),
                            end = Offset(leftX + segWidth - shimmerInset, strokePx),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HostProtocolOrder.forEach { option ->
                    HostProtocolSegmentItem(
                        prefix = option,
                        selected = prefix == option,
                        palette = palette,
                        compactWidth = compactWidth,
                        onClick = { onSelectPrefix(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 指定房号输入框 / R 系列自动分配提示（保持等高，切换协议不造成整卡高度跳变）
        HostRoomIdStrip(
            prefix = prefix,
            roomId = roomId,
            palette = palette,
            animated = animated,
            onRoomIdChange = onRoomIdChange,
        )

        // 随协议平滑淡换的说明条
        Surface(
            shape = RoundedCornerShape(11.dp),
            color = if (prefix == HostCommandPrefix.T) {
                palette.primary.copy(alpha = if (palette.isDark) 0.09f else 0.07f)
            } else {
                palette.panelSurface
            },
            border = BorderStroke(
                1.dp,
                if (prefix == HostCommandPrefix.T) {
                    palette.primary.copy(alpha = 0.28f)
                } else {
                    palette.hairlineBorder
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = if (prefix == HostCommandPrefix.T) palette.primary else palette.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp).size(15.dp),
                )
                val hintText = protocolHint(prefix)
                if (animated) {
                    AnimatedContent(
                        targetState = hintText,
                        transitionSpec = {
                            fadeIn(tween(170, delayMillis = 40, easing = StandardDecelerateEasing)) togetherWith
                                fadeOut(tween(75, easing = FastOutSlowInEasing))
                        },
                        label = "hostProtocolHintText",
                        modifier = Modifier.weight(1f),
                    ) { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                            color = palette.onSurface.copy(alpha = if (palette.isDark) 0.84f else 0.82f),
                        )
                    }
                } else {
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                        color = palette.onSurface.copy(alpha = if (palette.isDark) 0.84f else 0.82f),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HostProtocolSegmentItem(
    prefix: HostCommandPrefix,
    selected: Boolean,
    palette: HostDialogPalette,
    compactWidth: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = prefix != HostCommandPrefix.T
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val hoverBg by animateUiColorAsState(
        targetValue = if (!selected && hovered) {
            palette.onSurface.copy(alpha = if (palette.isDark) 0.05f else 0.04f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(130),
        label = "protocolSegmentHover",
    )
    val badgeBg by animateUiColorAsState(
        targetValue = if (selected) {
            palette.primary
        } else {
            palette.onSurface.copy(alpha = if (palette.isDark) 0.09f else 0.08f)
        },
        animationSpec = tween(180),
        label = "protocolLetterBg",
    )
    val badgeFg by animateUiColorAsState(
        targetValue = if (selected) palette.onPrimary else palette.onSurface.copy(alpha = 0.82f),
        animationSpec = tween(180),
        label = "protocolLetterFg",
    )
    val titleColor by animateUiColorAsState(
        targetValue = if (selected) palette.onSurface else palette.onSurface.copy(alpha = 0.78f),
        animationSpec = tween(180),
        label = "protocolTitleColor",
    )
    val subColor by animateUiColorAsState(
        targetValue = if (selected) palette.primary else palette.onSurfaceVariant,
        animationSpec = tween(180),
        label = "protocolSubColor",
    )

    val shape = RoundedCornerShape(10.dp)
    val codeChar = when (prefix) {
        HostCommandPrefix.T -> "T"
        HostCommandPrefix.Q -> "Q"
        HostCommandPrefix.R -> "R"
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(hoverBg, shape)
            .hoverable(interactionSource, enabled = enabled)
            .selectable(
                selected = selected,
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(
                horizontal = if (compactWidth) 8.dp else 11.dp,
                vertical = if (compactWidth) 8.dp else 8.5.dp,
            )
            .testTag("host-protocol-${codeChar.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = codeChar,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = badgeFg,
                )
            }
            Text(
                text = protocolTitle(prefix),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = protocolSubtitle(prefix, compactWidth),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
            color = subColor,
            maxLines = if (enabled) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HostRoomIdStrip(
    prefix: HostCommandPrefix,
    roomId: String,
    palette: HostDialogPalette,
    animated: Boolean,
    onRoomIdChange: (String) -> Unit,
) {
    val canSetRoomId = prefix != HostCommandPrefix.R
    val shape = RoundedCornerShape(12.dp)

    if (animated) {
        AnimatedContent(
            targetState = canSetRoomId,
            transitionSpec = {
                fadeIn(tween(165, delayMillis = 35, easing = StandardDecelerateEasing)) togetherWith
                    fadeOut(tween(75, easing = FastOutSlowInEasing))
            },
            label = "hostRoomIdMode",
        ) { editable ->
            if (editable) {
                HostRoomIdEditableBar(
                    prefix = prefix,
                    roomId = roomId,
                    shape = shape,
                    palette = palette,
                    animated = true,
                    onRoomIdChange = onRoomIdChange,
                )
            } else {
                HostRoomIdAutoBar(shape = shape, palette = palette)
            }
        }
    } else if (canSetRoomId) {
        HostRoomIdEditableBar(
            prefix = prefix,
            roomId = roomId,
            shape = shape,
            palette = palette,
            animated = false,
            onRoomIdChange = onRoomIdChange,
        )
    } else {
        HostRoomIdAutoBar(shape = shape, palette = palette)
    }
}

@Composable
private fun HostRoomIdEditableBar(
    prefix: HostCommandPrefix,
    roomId: String,
    shape: RoundedCornerShape,
    palette: HostDialogPalette,
    animated: Boolean,
    onRoomIdChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val borderColor by animateUiColorAsState(
        targetValue = when {
            focused -> palette.primary.copy(alpha = 0.88f)
            roomId.isNotBlank() -> palette.primary.copy(alpha = 0.42f)
            else -> palette.hairlineBorder
        },
        animationSpec = tween(150),
        label = "hostRoomIdBorder",
    )
    val prefixBadgeText = if (prefix == HostCommandPrefix.T) T_ROOM_ADDRESS_PREFIX else "Q"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(shape)
            .background(palette.stageSurface, shape)
            .border(BorderStroke(if (focused) 1.3.dp else 1.dp, borderColor), shape)
            .pointerInput(Unit) {
                detectTapGestures { focusRequester.requestFocus() }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = readI18n("multiplayer.hostDialog.roomIdLabel"),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = if (focused) palette.primary else palette.onSurfaceVariant,
            maxLines = 1,
        )

        Surface(
            shape = RoundedCornerShape(7.dp),
            color = palette.primary.copy(alpha = if (palette.isDark) 0.14f else 0.10f),
            border = BorderStroke(1.dp, palette.primary.copy(alpha = 0.30f)),
        ) {
            if (animated) {
                AnimatedContent(
                    targetState = prefixBadgeText,
                    transitionSpec = {
                        fadeIn(tween(140, easing = StandardDecelerateEasing)) togetherWith
                            fadeOut(tween(75, easing = FastOutSlowInEasing))
                    },
                    label = "hostRoomIdPrefix",
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.primary,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            } else {
                Text(
                    text = prefixBadgeText,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.primary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }

        HostNumericBasicField(
            value = roomId,
            placeholder = readI18n("multiplayer.hostDialog.roomIdPlaceholder"),
            maxLength = HOST_ROOM_ID_MAX_LEN,
            palette = palette,
            focusRequester = focusRequester,
            onFocusChange = { focused = it },
            onValueChange = onRoomIdChange,
            modifier = Modifier.weight(1f).testTag("host-field-room-id"),
        )

        if (roomId.isNotEmpty()) {
            HostInlineClearIcon(
                palette = palette,
                onClick = { onRoomIdChange("") },
                modifier = Modifier.testTag("host-clear-room-id"),
            )
        }
    }
}

@Composable
private fun HostRoomIdAutoBar(
    shape: RoundedCornerShape,
    palette: HostDialogPalette,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(shape)
            .background(palette.panelSurface, shape)
            .border(BorderStroke(1.dp, palette.hairlineBorder), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("host-room-id-auto"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = palette.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = readI18n("multiplayer.hostDialog.roomIdAutoHint"),
            style = MaterialTheme.typography.bodySmall,
            color = palette.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HostModsSection(
    enableMods: Boolean,
    transferMod: Boolean,
    modInventory: HostModInventory,
    isAccountLoggedIn: Boolean,
    palette: HostDialogPalette,
    compactWidth: Boolean,
    onToggleEnableMods: () -> Unit,
    onToggleTransferMod: () -> Unit,
) {
    val modStatusBadge = when {
        modInventory.enabledLocalCount > 0 -> readI18n(
            "multiplayer.hostDialog.modsReadyCount",
            I18nType.RWPP,
            modInventory.enabledLocalCount.toString(),
        )
        modInventory.hasEnabledNetworkMods -> readI18n("multiplayer.hostDialog.modsNetworkOnly")
        else -> readI18n("multiplayer.hostDialog.modsNoneEnabled")
    }
    val modSubtitle = if (enableMods) {
        modStatusBadge
    } else {
        readI18n("multiplayer.hostDialog.modsHintOff")
    }
    val transferSubtitle = when {
        !enableMods -> readI18n("multiplayer.hostDialog.transferModNeedMods")
        !isAccountLoggedIn -> readI18n("multiplayer.hostDialog.transferModNeedLogin")
        transferMod -> readI18n("multiplayer.hostDialog.transferModHintOn")
        else -> readI18n("multiplayer.hostDialog.transferModHintOff")
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HostSectionHeader(
            title = readI18n("multiplayer.hostDialog.sectionMods"),
            palette = palette,
            trailing = {
                Surface(
                    shape = CircleShape,
                    color = if (modInventory.enabledLocalCount > 0) {
                        palette.primary.copy(alpha = if (palette.isDark) 0.13f else 0.10f)
                    } else {
                        palette.panelSurface
                    },
                    border = BorderStroke(
                        1.dp,
                        if (modInventory.enabledLocalCount > 0) {
                            palette.primary.copy(alpha = 0.28f)
                        } else {
                            palette.hairlineBorder
                        },
                    ),
                ) {
                    Text(
                        text = modStatusBadge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = if (modInventory.enabledLocalCount > 0) palette.primary else palette.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            },
        )

        val enableModsCard: @Composable (Modifier) -> Unit = { cardModifier ->
            HostToggleCard(
                title = readI18n("multiplayer.enableMods"),
                subtitle = modSubtitle,
                badge = null,
                checked = enableMods,
                enabled = true,
                painter = painterResource(Res.drawable.extension_30),
                icon = null,
                palette = palette,
                onToggle = onToggleEnableMods,
                modifier = cardModifier.testTag("host-toggle-enable-mods"),
            )
        }
        val transferModCard: @Composable (Modifier) -> Unit = { cardModifier ->
            HostToggleCard(
                title = readI18n("multiplayer.hostDialog.transferModTitle"),
                subtitle = transferSubtitle,
                badge = readI18n("multiplayer.hostDialog.transferModBadge"),
                checked = transferMod,
                enabled = enableMods,
                painter = null,
                icon = Icons.Default.Refresh,
                palette = palette,
                onToggle = onToggleTransferMod,
                modifier = cardModifier.testTag("host-toggle-transfer-mod"),
            )
        }

        if (compactWidth) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                enableModsCard(Modifier.fillMaxWidth())
                transferModCard(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                enableModsCard(Modifier.weight(1f))
                transferModCard(Modifier.weight(1f))
            }
        }

        if (modInventory.hasNetworkMods) {
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = palette.warningAccent.copy(alpha = if (palette.isDark) 0.11f else 0.08f),
                border = BorderStroke(1.dp, palette.warningAccent.copy(alpha = 0.32f)),
                modifier = Modifier.fillMaxWidth().testTag("host-network-mod-banner"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = palette.warningAccent,
                        modifier = Modifier.padding(top = 1.dp).size(15.dp),
                    )
                    Text(
                        text = if (modInventory.hasEnabledNetworkMods) {
                            readI18n("multiplayer.networkModHostHint")
                        } else {
                            readI18n("multiplayer.networkModHostHintDisabled")
                        },
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                        color = palette.onSurface.copy(alpha = 0.88f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HostToggleCard(
    title: String,
    subtitle: String,
    badge: String?,
    checked: Boolean,
    enabled: Boolean,
    painter: Painter?,
    icon: ImageVector?,
    palette: HostDialogPalette,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val active = checked && enabled

    val contentAlpha = animateUiFloatAsState(
        targetValue = if (enabled) 1f else 0.52f,
        animationSpec = tween(180),
        label = "hostToggleAlpha",
    )
    val bgColor by animateUiColorAsState(
        targetValue = when {
            active -> palette.panelHighlight
            enabled && hovered -> palette.panelHoverSurface
            else -> palette.panelSurface
        },
        animationSpec = tween(160),
        label = "hostToggleBg",
    )
    val borderColor by animateUiColorAsState(
        targetValue = when {
            active -> palette.primary.copy(alpha = 0.72f)
            enabled && hovered -> palette.onSurface.copy(alpha = if (palette.isDark) 0.22f else 0.24f)
            else -> palette.hairlineBorder
        },
        animationSpec = tween(160),
        label = "hostToggleBorder",
    )
    val iconContainerColor by animateUiColorAsState(
        targetValue = if (active) {
            palette.primary.copy(alpha = if (palette.isDark) 0.22f else 0.16f)
        } else {
            palette.stageSurface
        },
        animationSpec = tween(160),
        label = "hostToggleIconBg",
    )
    val iconTint by animateUiColorAsState(
        targetValue = if (active) palette.primary else palette.onSurfaceVariant,
        animationSpec = tween(160),
        label = "hostToggleIconTint",
    )

    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier = modifier
            .graphicsLayer { alpha = contentAlpha.value }
            .clip(shape)
            .background(bgColor, shape)
            .border(BorderStroke(if (active) 1.2.dp else 1.dp, borderColor), shape)
            .hoverable(interactionSource, enabled = enabled)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconContainerColor)
                .border(
                    BorderStroke(1.dp, if (active) palette.primary.copy(alpha = 0.32f) else palette.hairlineBorder),
                    RoundedCornerShape(9.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (painter != null) {
                Icon(
                    painter = painter,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(17.dp),
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!badge.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = if (active) {
                            palette.primary.copy(alpha = if (palette.isDark) 0.18f else 0.14f)
                        } else {
                            palette.onSurface.copy(alpha = if (palette.isDark) 0.08f else 0.07f)
                        },
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (active) palette.primary else palette.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp),
                color = if (active) palette.primary.copy(alpha = 0.92f) else palette.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        UiSwitch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onPrimary,
                checkedTrackColor = palette.primary,
                checkedBorderColor = palette.primary,
                uncheckedThumbColor = palette.onSurfaceVariant,
                uncheckedTrackColor = palette.stageSurface,
                uncheckedBorderColor = palette.hairlineBorder,
                disabledCheckedThumbColor = palette.onSurfaceVariant.copy(alpha = 0.6f),
                disabledCheckedTrackColor = palette.panelSurface,
                disabledUncheckedThumbColor = palette.onSurfaceVariant.copy(alpha = 0.55f),
                disabledUncheckedTrackColor = palette.stageSurface,
                disabledUncheckedBorderColor = palette.hairlineBorder,
            ),
            modifier = Modifier.graphicsLayer {
                scaleX = 0.86f
                scaleY = 0.86f
            },
        )
    }
}

@Composable
private fun HostParametersSection(
    form: HostGameFormState,
    palette: HostDialogPalette,
    animated: Boolean,
    onFormChange: (HostGameFormState) -> Unit,
    onResetAll: () -> Unit,
) {
    val customCount = form.customParamCount
    val statusText = if (customCount == 0) {
        readI18n("multiplayer.hostDialog.paramsAllDefault")
    } else {
        readI18n("multiplayer.hostDialog.paramsCustomCount", I18nType.RWPP, customCount.toString())
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HostSectionHeader(
            title = readI18n("multiplayer.room.roomDetails"),
            palette = palette,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = if (customCount > 0) palette.primary else palette.onSurfaceVariant,
                    )
                    AnimatedVisibility(
                        visible = form.hasAnyCustomInput,
                        enter = if (animated) fadeIn(tween(150)) else EnterTransition.None,
                        exit = if (animated) fadeOut(tween(100)) else ExitTransition.None,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = palette.panelSurface,
                            border = BorderStroke(1.dp, palette.hairlineBorder),
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClick = onResetAll)
                                .testTag("host-reset-params"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = palette.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = readI18n("multiplayer.hostDialog.paramsReset"),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = palette.onSurface.copy(alpha = 0.85f),
                                )
                            }
                        }
                    }
                }
            },
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val effectiveWidth = maxWidth / LocalDensity.current.fontScale
            val twoColumns = effectiveWidth >= 320.dp

            val maxPlayerCell: @Composable (Modifier) -> Unit = { cellMod ->
                HostNumericParamCell(
                    label = readI18n("multiplayer.room.maxPlayer"),
                    codeTag = "P",
                    value = form.maxPlayer?.toString().orEmpty(),
                    placeholder = readI18n("multiplayer.hostDialog.placeholderMaxPlayer"),
                    maxLength = 3,
                    palette = palette,
                    fieldTag = "host-field-max-player",
                    onValueChange = { raw ->
                        onFormChange(form.copy(maxPlayer = raw.toIntOrNull()?.coerceAtMost(HOST_MAX_PLAYER_CAP)))
                    },
                    modifier = cellMod,
                )
            }
            val unitLimitCell: @Composable (Modifier) -> Unit = { cellMod ->
                HostNumericParamCell(
                    label = stripTrailingCodeSuffix(readI18n("multiplayer.room.unitLimit")),
                    codeTag = "U",
                    value = form.unitLimit?.toString().orEmpty(),
                    placeholder = readI18n("multiplayer.hostDialog.placeholderDefault"),
                    maxLength = HOST_NUMERIC_FIELD_MAX_LEN,
                    palette = palette,
                    fieldTag = "host-field-unit-limit",
                    onValueChange = { raw ->
                        onFormChange(form.copy(unitLimit = raw.toIntOrNull()))
                    },
                    modifier = cellMod,
                )
            }
            val creditsCell: @Composable (Modifier) -> Unit = { cellMod ->
                HostNumericParamCell(
                    label = stripTrailingCodeSuffix(readI18n("multiplayer.room.credits")),
                    codeTag = "C",
                    value = form.credits?.toString().orEmpty(),
                    placeholder = readI18n("multiplayer.hostDialog.placeholderDefault"),
                    maxLength = HOST_NUMERIC_FIELD_MAX_LEN,
                    palette = palette,
                    fieldTag = "host-field-credits",
                    onValueChange = { raw ->
                        onFormChange(form.copy(credits = raw.toIntOrNull()))
                    },
                    modifier = cellMod,
                )
            }
            val speedCell: @Composable (Modifier) -> Unit = { cellMod ->
                HostNumericParamCell(
                    label = stripTrailingCodeSuffix(readI18n("multiplayer.room.speedMultiplier")),
                    codeTag = "Z",
                    value = form.speedMultiplier?.toString().orEmpty(),
                    placeholder = readI18n("multiplayer.hostDialog.placeholderDefault"),
                    maxLength = HOST_NUMERIC_FIELD_MAX_LEN,
                    palette = palette,
                    fieldTag = "host-field-speed",
                    onValueChange = { raw ->
                        onFormChange(form.copy(speedMultiplier = raw.toIntOrNull()))
                    },
                    modifier = cellMod,
                )
            }

            if (twoColumns) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        maxPlayerCell(Modifier.weight(1f))
                        unitLimitCell(Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        creditsCell(Modifier.weight(1f))
                        speedCell(Modifier.weight(1f))
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    maxPlayerCell(Modifier.fillMaxWidth())
                    unitLimitCell(Modifier.fillMaxWidth())
                    creditsCell(Modifier.fillMaxWidth())
                    speedCell(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun HostNumericParamCell(
    label: String,
    codeTag: String,
    value: String,
    placeholder: String,
    maxLength: Int,
    palette: HostDialogPalette,
    fieldTag: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val filled = value.isNotEmpty()

    val borderColor by animateUiColorAsState(
        targetValue = when {
            focused -> palette.primary.copy(alpha = 0.88f)
            filled -> palette.primary.copy(alpha = 0.42f)
            else -> palette.hairlineBorder
        },
        animationSpec = tween(150),
        label = "hostParamBorder",
    )
    val tagBg by animateUiColorAsState(
        targetValue = if (focused || filled) {
            palette.primary.copy(alpha = if (palette.isDark) 0.18f else 0.13f)
        } else {
            palette.onSurface.copy(alpha = if (palette.isDark) 0.07f else 0.06f)
        },
        animationSpec = tween(150),
        label = "hostParamTagBg",
    )
    val tagFg by animateUiColorAsState(
        targetValue = if (focused || filled) palette.primary else palette.onSurfaceVariant,
        animationSpec = tween(150),
        label = "hostParamTagFg",
    )

    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(palette.stageSurface, shape)
            .border(BorderStroke(if (focused) 1.3.dp else 1.dp, borderColor), shape)
            .pointerInput(Unit) {
                detectTapGestures { focusRequester.requestFocus() }
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = if (focused) palette.primary else palette.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = tagBg,
            ) {
                Text(
                    text = codeTag,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = tagFg,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HostNumericBasicField(
                value = value,
                placeholder = placeholder,
                maxLength = maxLength,
                palette = palette,
                focusRequester = focusRequester,
                onFocusChange = { focused = it },
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).testTag(fieldTag),
            )
            if (filled) {
                HostInlineClearIcon(
                    palette = palette,
                    onClick = { onValueChange("") },
                )
            }
        }
    }
}

@Composable
private fun HostNumericBasicField(
    value: String,
    placeholder: String,
    maxLength: Int,
    palette: HostDialogPalette,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val forwardEscapeOnDesktop = LocalAccountAuthDialogEscape.current
    val escapeScope = rememberCoroutineScope()

    fun filterDigits(raw: String): String =
        raw.filter { it.isDigit() }.take(maxLength)

    // 保留 TextFieldValue 以确保桌面原生 Dialog 内编辑光标始终正常绘制
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    SideEffect {
        if (textFieldValue.text != value) {
            textFieldValue = TextFieldValue(text = value, selection = TextRange(value.length))
        }
    }

    CompositionLocalProvider(LocalTextSelectionColors provides RWSelectionColors) {
        BasicTextField(
            value = textFieldValue,
            onValueChange = { incoming ->
                val filtered = filterDigits(incoming.text)
                textFieldValue = accountTextFieldEdit(incoming, filtered)
                onValueChange(filtered)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = palette.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            ),
            cursorBrush = SolidColor(palette.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            modifier = modifier
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChange(it.isFocused) }
                .then(
                    if (forwardEscapeOnDesktop) {
                        Modifier.desktopEscapeToBackDispatcher(escapeScope)
                    } else {
                        Modifier
                    },
                ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (textFieldValue.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = palette.onSurfaceVariant.copy(alpha = 0.56f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun HostInlineClearIcon(
    palette: HostDialogPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(palette.onSurface.copy(alpha = if (palette.isDark) 0.11f else 0.09f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = readI18n("common.close"),
            tint = palette.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun HostConfigureFooter(
    form: HostGameFormState,
    palette: HostDialogPalette,
    animated: Boolean,
    compactWidth: Boolean,
    compactHeight: Boolean,
    onHostClick: () -> Unit,
) {
    val summaryTitle = buildList {
        add(protocolTitle(form.prefix))
        add(
            readI18n(
                if (form.enableMods) {
                    "multiplayer.hostDialog.summaryModded"
                } else {
                    "multiplayer.hostDialog.summaryVanilla"
                },
            ),
        )
        if (form.enableMods && form.transferMod) {
            add(readI18n("multiplayer.hostDialog.summaryModSync"))
        }
    }.joinToString(" · ")

    val digest = form.summaryParamDigest()
    val summaryHint = when {
        digest.isNotBlank() && compactWidth -> digest
        digest.isNotBlank() -> readI18n("multiplayer.hostDialog.summaryCustomHint", I18nType.RWPP, digest)
        compactWidth -> readI18n("multiplayer.hostDialog.paramsAllDefault")
        else -> readI18n("multiplayer.hostDialog.summaryDefaultHint")
    }

    val horizontalPad = if (compactWidth) 16.dp else 22.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.stageSurface.copy(alpha = if (palette.isDark) 0.60f else 0.76f))
            .padding(horizontal = horizontalPad, vertical = if (compactHeight) 9.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(palette.primary),
                )
                if (animated) {
                    AnimatedContent(
                        targetState = summaryTitle,
                        transitionSpec = {
                            fadeIn(tween(150, easing = StandardDecelerateEasing)) togetherWith
                                fadeOut(tween(80, easing = FastOutSlowInEasing))
                        },
                        label = "hostFooterSummaryTitle",
                    ) { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = palette.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = summaryTitle,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = summaryHint,
                style = MaterialTheme.typography.labelSmall,
                color = palette.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        HostPrimaryButton(
            label = readI18n("multiplayer.host"),
            palette = palette,
            leadingIcon = {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = palette.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = onHostClick,
            modifier = Modifier
                .widthIn(min = if (compactWidth) 116.dp else 134.dp)
                .testTag("host-submit-button"),
        )
    }
}

@Composable
private fun HostNoticeStage(
    badgeText: String,
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String?,
    palette: HostDialogPalette,
    compactHeight: Boolean,
    maxCardHeight: Dp,
    stageTag: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(
        modifier = Modifier
            .widthIn(max = HOST_CONFIRM_MAX_WIDTH_DP.dp)
            .fillMaxWidth()
            .heightIn(max = maxCardHeight)
            .testTag(stageTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = 24.dp,
                    vertical = if (compactHeight) 16.dp else 24.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compactHeight) 10.dp else 14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = palette.warningAccent.copy(alpha = if (palette.isDark) 0.14f else 0.11f),
                border = BorderStroke(1.dp, palette.warningAccent.copy(alpha = 0.34f)),
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.35.sp,
                    ),
                    color = palette.warningAccent,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }

            HostWarningEmblem(
                accent = palette.warningAccent,
                palette = palette,
                size = if (compactHeight) 56.dp else 68.dp,
            )

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = palette.onSurface,
                textAlign = TextAlign.Center,
            )

            Surface(
                shape = RoundedCornerShape(13.dp),
                color = palette.warningAccent.copy(alpha = if (palette.isDark) 0.09f else 0.07f),
                border = BorderStroke(1.dp, palette.warningAccent.copy(alpha = 0.28f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                    color = palette.onSurface.copy(alpha = 0.90f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 15.dp, vertical = 13.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!cancelLabel.isNullOrBlank()) {
                    HostSecondaryButton(
                        label = cancelLabel,
                        palette = palette,
                        onClick = onBack,
                        modifier = Modifier
                            .widthIn(min = 104.dp)
                            .testTag("host-notice-cancel"),
                    )
                }
                HostPrimaryButton(
                    label = confirmLabel,
                    palette = palette,
                    onClick = onConfirm,
                    modifier = Modifier
                        .widthIn(min = 136.dp)
                        .testTag("host-notice-confirm"),
                )
            }
        }

        HostDialogCloseButton(
            onClick = onBack,
            palette = palette,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
        )
    }
}

@Composable
private fun HostWarningEmblem(
    accent: Color,
    palette: HostDialogPalette,
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
                color = accent.copy(alpha = 0.66f),
                radius = radius * 0.62f,
                style = Stroke(1.3.dp.toPx()),
            )
        }
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

@Composable
private fun HostDialogCloseButton(
    onClick: () -> Unit,
    palette: HostDialogPalette,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val bgColor by animateUiColorAsState(
        targetValue = if (hovered) palette.panelHoverSurface else palette.panelSurface,
        animationSpec = tween(130),
        label = "hostCloseBg",
    )

    IconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .size(48.dp)
            .hoverable(interactionSource)
            .testTag("host-close-button"),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(bgColor)
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

@Composable
private fun HostPrimaryButton(
    label: String,
    palette: HostDialogPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateUiFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = tween(110, easing = StandardDecelerateEasing),
        label = "hostPrimaryScale",
    )
    val bgColor by animateUiColorAsState(
        targetValue = if (hovered) {
            lerp(palette.primary, palette.highlight, 0.16f)
        } else {
            palette.primary
        },
        animationSpec = tween(130),
        label = "hostPrimaryBg",
    )

    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .heightIn(min = 42.dp)
            .clip(shape)
            .background(bgColor, shape)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leadingIcon?.invoke()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = palette.onPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HostSecondaryButton(
    label: String,
    palette: HostDialogPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateUiFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = tween(110, easing = StandardDecelerateEasing),
        label = "hostSecondaryScale",
    )
    val bgColor by animateUiColorAsState(
        targetValue = if (hovered) palette.panelHoverSurface else palette.panelSurface,
        animationSpec = tween(130),
        label = "hostSecondaryBg",
    )

    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .heightIn(min = 42.dp)
            .clip(shape)
            .background(bgColor, shape)
            .border(1.dp, palette.hairlineBorder, shape)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = palette.onSurface.copy(alpha = 0.88f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun protocolTitle(prefix: HostCommandPrefix): String = when (prefix) {
    HostCommandPrefix.T -> readI18n("multiplayer.hostPrefixT")
    HostCommandPrefix.Q -> readI18n("multiplayer.hostPrefixQ")
    HostCommandPrefix.R -> readI18n("multiplayer.hostPrefixR")
}

private fun protocolSubtitle(prefix: HostCommandPrefix, compactWidth: Boolean): String = when (prefix) {
    HostCommandPrefix.T -> protocolHint(prefix)
    HostCommandPrefix.Q -> readI18n(
        if (compactWidth) "multiplayer.hostDialog.protocolBadgeQ" else "multiplayer.hostDialog.protocolFormatQ",
    )
    HostCommandPrefix.R -> readI18n(
        if (compactWidth) "multiplayer.hostDialog.protocolBadgeR" else "multiplayer.hostDialog.protocolFormatR",
    )
}

private val trailingCodeSuffixRegex = Regex("""\s*\([A-Za-z]\)$""")

private fun stripTrailingCodeSuffix(label: String): String =
    label.replace(trailingCodeSuffixRegex, "").trim()

private fun protocolHint(prefix: HostCommandPrefix): String = when (prefix) {
    HostCommandPrefix.T -> readI18n("multiplayer.hostDialog.protocolHintT")
    HostCommandPrefix.Q -> readI18n("multiplayer.hostDialog.protocolHintQ")
    HostCommandPrefix.R -> readI18n("multiplayer.hostDialog.protocolHintR")
}
