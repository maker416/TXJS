/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.rwpp.platform.BackHandlerScope
import io.github.rwpp.widget.LocalDialogInteractive
import io.github.rwpp.widget.uiAnimationsEnabled
import java.util.concurrent.atomic.AtomicBoolean

internal val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
internal val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
internal val StandardDecelerateEasing = LinearOutSlowInEasing

internal const val MAIN_MENU_PAIR_ENTER_MS = 320
internal const val MAIN_MENU_PAIR_ENTER_FADE_MS = 240
internal const val MAIN_MENU_PAIR_ENTER_FADE_DELAY_MS = 35
internal const val MAIN_MENU_PAIR_EXIT_MS = 190
internal const val MAIN_MENU_PAIR_EXIT_FADE_MS = 180
internal const val HALL_BACKDROP_ENTER_MS = 230
internal const val HALL_EXIT_MS = 210

internal const val MAIN_MENU_DEPTH_ENTER_MS = 300
internal const val MAIN_MENU_DEPTH_ENTER_FADE_MS = 230
internal const val MAIN_MENU_DEPTH_ENTER_FADE_DELAY_MS = 30
internal const val MAIN_MENU_DEPTH_EXIT_MS = 180
internal const val MAIN_MENU_DEPTH_EXIT_FADE_MS = 165

internal const val PANEL_ENTER_MS = 320
internal const val PANEL_REENTER_MS = 290
internal const val PANEL_ENTER_FADE_MS = 230
internal const val PANEL_ENTER_FADE_DELAY_MS = 30
internal const val PANEL_EXIT_MS = 190
internal const val PANEL_EXIT_FADE_MS = 175
internal const val PANEL_RECEDE_MS = 185
internal const val PANEL_RECEDE_FADE_MS = 165

internal const val SHEET_ENTER_MS = 300
internal const val SHEET_ENTER_FADE_MS = 220
internal const val SHEET_ENTER_FADE_DELAY_MS = 25
internal const val SHEET_EXIT_MS = 185
internal const val SHEET_EXIT_FADE_MS = 170

internal const val RESOURCE_CENTER_BACKDROP_ENTER_MS = 220
internal const val RESOURCE_CENTER_EXIT_MS = 195

internal const val BROWSER_FADE_ENTER_MS = 220
internal const val BROWSER_FADE_EXIT_MS = 170

internal enum class MainMenuTransitionStyle { None, HorizontalHub, DepthZoom }

internal data class MainMenuMotionConfig(
    val animated: Boolean,
    val enterStyle: MainMenuTransitionStyle,
    val exitStyle: MainMenuTransitionStyle,
)

internal data class GameplayHallMotionConfig(
    val animated: Boolean,
    val enterFromRight: Boolean,
    val exitToRight: Boolean,
)

internal data class ResourceCenterMotionConfig(
    val animated: Boolean,
    val enterForward: Boolean,
    val exitToChild: Boolean,
)

@Stable
internal class MainMenuForegroundMotionHolder {
    var enabled: Boolean = false
    var hiddenStyle: MainMenuTransitionStyle = MainMenuTransitionStyle.None
    var offsetXFraction: State<Float>? = null
    var offsetYFraction: State<Float>? = null
    var scaleState: State<Float>? = null
}

internal val LocalMainMenuMotionConfig = compositionLocalOf<MainMenuMotionConfig?> { null }
internal val LocalMainMenuVisibilityTransition = compositionLocalOf<Transition<Boolean>?> { null }
internal val LocalMainMenuForegroundMotion = compositionLocalOf<MainMenuForegroundMotionHolder?> { null }
internal val LocalGameplayHallMotionConfig = compositionLocalOf<GameplayHallMotionConfig?> { null }
internal val LocalResourceCenterMotionConfig = compositionLocalOf<ResourceCenterMotionConfig?> { null }

/**
 * 主菜单保活宿主：退场动画期间保留绘制，收束完成后仅跳过放置，
 * 避免每次切回主菜单都从零重建整棵 Compose 树并触发多轮子组合。
 */
@Composable
internal fun MainMenuOverlayHost(
    visible: Boolean,
    isInteractive: () -> Boolean,
    enterStyle: MainMenuTransitionStyle,
    exitStyle: MainMenuTransitionStyle,
    content: @Composable () -> Unit,
) {
    val animated = uiAnimationsEnabled()
    val parentInteractive = LocalDialogInteractive.current
    val currentInteractive = rememberUpdatedState { visible && parentInteractive && isInteractive() }
    val transition = if (animated) {
        updateTransition(targetState = visible, label = "MainMenuKeepAlive")
    } else {
        null
    }
    val backdropAlpha = transition?.animateFloat(
        transitionSpec = {
            if (targetState) {
                when (enterStyle) {
                    MainMenuTransitionStyle.HorizontalHub -> tween(
                        MAIN_MENU_PAIR_ENTER_FADE_MS,
                        delayMillis = MAIN_MENU_PAIR_ENTER_FADE_DELAY_MS,
                        easing = StandardDecelerateEasing,
                    )
                    MainMenuTransitionStyle.DepthZoom -> tween(
                        MAIN_MENU_DEPTH_ENTER_FADE_MS,
                        delayMillis = MAIN_MENU_DEPTH_ENTER_FADE_DELAY_MS,
                        easing = StandardDecelerateEasing,
                    )
                    MainMenuTransitionStyle.None -> tween(0)
                }
            } else {
                when (exitStyle) {
                    MainMenuTransitionStyle.HorizontalHub -> tween(
                        MAIN_MENU_PAIR_EXIT_FADE_MS,
                        easing = EmphasizedAccelerateEasing,
                    )
                    MainMenuTransitionStyle.DepthZoom -> tween(
                        MAIN_MENU_DEPTH_EXIT_FADE_MS,
                        easing = EmphasizedAccelerateEasing,
                    )
                    MainMenuTransitionStyle.None -> tween(0)
                }
            }
        },
        label = "MainMenuBackdropAlpha",
    ) { shown ->
        if (shown) 1f else 0f
    }
    val hasStyle = enterStyle != MainMenuTransitionStyle.None || exitStyle != MainMenuTransitionStyle.None
    val hiddenStyle = if (visible) enterStyle else exitStyle
    val offsetXFraction = transition?.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(
                    if (enterStyle == MainMenuTransitionStyle.HorizontalHub) MAIN_MENU_PAIR_ENTER_MS else MAIN_MENU_DEPTH_ENTER_MS,
                    easing = EmphasizedDecelerateEasing,
                )
            } else {
                tween(
                    if (exitStyle == MainMenuTransitionStyle.HorizontalHub) MAIN_MENU_PAIR_EXIT_MS else MAIN_MENU_DEPTH_EXIT_MS,
                    easing = EmphasizedAccelerateEasing,
                )
            }
        },
        label = "MainMenuOffsetXFraction",
    ) { shown ->
        if (shown) 0f else if (hiddenStyle == MainMenuTransitionStyle.HorizontalHub) -1f else 0f
    }
    val offsetYFraction = transition?.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(
                    if (enterStyle == MainMenuTransitionStyle.HorizontalHub) MAIN_MENU_PAIR_ENTER_MS else MAIN_MENU_DEPTH_ENTER_MS,
                    easing = EmphasizedDecelerateEasing,
                )
            } else {
                tween(
                    if (exitStyle == MainMenuTransitionStyle.HorizontalHub) MAIN_MENU_PAIR_EXIT_MS else MAIN_MENU_DEPTH_EXIT_MS,
                    easing = EmphasizedAccelerateEasing,
                )
            }
        },
        label = "MainMenuOffsetYFraction",
    ) { shown ->
        if (shown) 0f else if (hiddenStyle == MainMenuTransitionStyle.DepthZoom) -1f else 0f
    }
    val scaleState = transition?.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(
                    if (enterStyle == MainMenuTransitionStyle.HorizontalHub) MAIN_MENU_PAIR_ENTER_MS else MAIN_MENU_DEPTH_ENTER_MS,
                    easing = EmphasizedDecelerateEasing,
                )
            } else {
                tween(
                    if (exitStyle == MainMenuTransitionStyle.HorizontalHub) MAIN_MENU_PAIR_EXIT_MS else MAIN_MENU_DEPTH_EXIT_MS,
                    easing = EmphasizedAccelerateEasing,
                )
            }
        },
        label = "MainMenuScale",
    ) { shown ->
        if (shown) {
            1f
        } else {
            when (hiddenStyle) {
                MainMenuTransitionStyle.HorizontalHub -> 0.968f
                MainMenuTransitionStyle.DepthZoom -> 0.958f
                MainMenuTransitionStyle.None -> 1f
            }
        }
    }
    val foregroundMotion = remember { MainMenuForegroundMotionHolder() }.also {
        it.enabled = animated && hasStyle && transition != null
        it.hiddenStyle = hiddenStyle
        it.offsetXFraction = offsetXFraction
        it.offsetYFraction = offsetYFraction
        it.scaleState = scaleState
    }
    val scopeEnabled = remember { { currentInteractive.value() } }
    val latestContent = rememberUpdatedState(content)
    val keepAliveLeaf = remember<@Composable () -> Unit> {
        {
            MainMenuKeepAliveContent(latestContent.value)
        }
    }
    val keepAliveScoped = remember<@Composable () -> Unit> {
        {
            BackHandlerScope(enabled = scopeEnabled, content = keepAliveLeaf)
        }
    }

    Box(
        modifier = Modifier
            .zIndex(if (currentInteractive.value()) 1f else -1f)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val shouldPlace = visible || (transition != null && (transition.currentState || transition.targetState))
                if (shouldPlace) {
                    layout(placeable.width, placeable.height) {
                        placeable.place(0, 0)
                    }
                } else {
                    layout(0, 0) {}
                }
            }
            .let {
                if (transition != null && backdropAlpha != null) {
                    it.graphicsLayer { alpha = backdropAlpha.value }
                } else {
                    it
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!currentInteractive.value()) event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        CompositionLocalProvider(
            LocalMainMenuForegroundMotion provides foregroundMotion,
            content = keepAliveScoped,
        )
    }
}

@Composable
private fun MainMenuKeepAliveContent(content: @Composable () -> Unit) {
    content()
}

private val launcherPrewarmStarted = AtomicBoolean(false)

/** 后台预热高频子页面类与玩法大厅离屏位图，降低首次点击时的 EDT 类加载与光栅化停顿。 */
internal fun prewarmLauncherUiClassesAndCaches() {
    if (!launcherPrewarmStarted.compareAndSet(false, true)) return
    runCatching { prewarmHallBattlefieldBitmaps() }
    val loader = LauncherNavigationTransition::class.java.classLoader ?: return
    val classNames = listOf(
        "io.github.rwpp.ui.SinglePlayerKt",
        "io.github.rwpp.ui.PanelScrollRailKt",
        "io.github.rwpp.ui.PanelChromeKt",
        "io.github.rwpp.ui.GameplayHallKt",
        "io.github.rwpp.ui.ComposableSingletons\$GameplayHallKt",
        "io.github.rwpp.ui.ResourceCenterKt",
        "io.github.rwpp.ui.ComposableSingletons\$ResourceCenterKt",
        "io.github.rwpp.ui.SettingsKt",
        "io.github.rwpp.ui.ComposableSingletons\$SettingsKt",
        "io.github.rwpp.ui.SettingsLayoutKt",
        "io.github.rwpp.ui.ComposableSingletons\$SettingsLayoutKt",
        "io.github.rwpp.ui.MultiplayerKt",
        "io.github.rwpp.ui.ComposableSingletons\$MultiplayerKt",
        "io.github.rwpp.ui.HostGameDialogKt",
        "io.github.rwpp.ui.ComposableSingletons\$HostGameDialogKt",
        "io.github.rwpp.ui.MultiplayerTopBarKt",
        "io.github.rwpp.ui.ComposableSingletons\$MultiplayerTopBarKt",
        "io.github.rwpp.ui.RoomListLoadingKt",
        "io.github.rwpp.ui.ComposableSingletons\$RoomListLoadingKt",
        "io.github.rwpp.ui.ModsKt",
        "io.github.rwpp.ui.ModsAndMapsViewKt",
        "io.github.rwpp.ui.LocalContentChromeKt",
        "io.github.rwpp.ui.AccountKt",
        "io.github.rwpp.ui.AccountWelcomeKt",
        "io.github.rwpp.ui.FriendsKt",
        "io.github.rwpp.widget.v2.WidgetKt",
        "io.github.rwpp.widget.v2.ButtonAnimationsKt",
        "io.github.rwpp.widget.v2.LazyColumnScrollbarKt",
        "io.github.rwpp.widget.v2.ComposableSingletons\$LazyColumnScrollbarKt",
        "io.github.rwpp.widget.ButtonKt",
    )
    for (name in classNames) {
        runCatching { Class.forName(name, true, loader) }
    }
}

internal class MainMenuTransitionMemory(
    var lastShowMainMenu: Boolean = true,
    var lastPage: LauncherPage = LauncherPage.MainMenu,
    var lastOverlayOpen: Boolean = false,
    var enterStyle: MainMenuTransitionStyle = MainMenuTransitionStyle.None,
    var exitStyle: MainMenuTransitionStyle = MainMenuTransitionStyle.None,
)

internal class HallTransitionMemory(
    var lastVisible: Boolean = false,
    var enterFromRight: Boolean = true,
    var exitToRight: Boolean = true,
)

internal class ResourceCenterTransitionMemory(
    var lastVisible: Boolean = false,
    var enterForward: Boolean = true,
    var exitToChild: Boolean = false,
)

internal class PanelTransitionMemory(
    var lastVisible: Boolean = false,
    var enterForward: Boolean = true,
    var exitToChild: Boolean = false,
)

/**
 * 居中战术面板共用宿主：按导航方向区分压栈切入 / 后景退隐与弹栈收束 / 后景推回，
 * 全程使用 GPU 合成层动画并避免对有状态子页面重建组合树。
 */
@Composable
internal fun TacticalPanelPageHost(
    page: LauncherPage,
    content: @Composable () -> Unit,
) {
    val animated = uiAnimationsEnabled()
    val density = LocalDensity.current
    val visible = launcherPage == page
    val memory = remember { PanelTransitionMemory() }
    val enterForward =
        if (visible && !memory.lastVisible) {
            launcherNavigationTransition.direction != NavigationDirection.Backward
        } else {
            memory.enterForward
        }
    val exitToChild =
        if (!visible && memory.lastVisible) {
            launcherNavigationTransition.direction == NavigationDirection.Forward
        } else {
            memory.exitToChild
        }
    SideEffect {
        memory.lastVisible = visible
        memory.enterForward = enterForward
        memory.exitToChild = exitToChild
    }

    val maxLiftPx = with(density) { 28.dp.roundToPx() }
    val maxExitDropPx = with(density) { 20.dp.roundToPx() }
    val maxRecedePx = with(density) { 12.dp.roundToPx() }

    val enter = if (!animated) {
        EnterTransition.None
    } else if (enterForward) {
        fadeIn(tween(PANEL_ENTER_FADE_MS, delayMillis = PANEL_ENTER_FADE_DELAY_MS, easing = StandardDecelerateEasing)) +
            slideInVertically(tween(PANEL_ENTER_MS, easing = EmphasizedDecelerateEasing)) { minOf(it / 20, maxLiftPx) } +
            scaleIn(
                tween(PANEL_ENTER_MS, easing = EmphasizedDecelerateEasing),
                initialScale = 0.968f,
                transformOrigin = TransformOrigin(0.5f, 0.44f),
            )
    } else {
        fadeIn(tween(220, delayMillis = 25, easing = StandardDecelerateEasing)) +
            slideInVertically(tween(PANEL_REENTER_MS, easing = EmphasizedDecelerateEasing)) { -minOf(it / 40, maxRecedePx) } +
            scaleIn(
                tween(PANEL_REENTER_MS, easing = EmphasizedDecelerateEasing),
                initialScale = 0.966f,
                transformOrigin = TransformOrigin(0.5f, 0.48f),
            )
    }

    val exit = if (!animated) {
        ExitTransition.None
    } else if (!exitToChild) {
        fadeOut(tween(PANEL_EXIT_FADE_MS, easing = EmphasizedAccelerateEasing)) +
            slideOutVertically(tween(PANEL_EXIT_MS, easing = EmphasizedAccelerateEasing)) { minOf(it / 24, maxExitDropPx) } +
            scaleOut(
                tween(PANEL_EXIT_MS, easing = EmphasizedAccelerateEasing),
                targetScale = 0.974f,
                transformOrigin = TransformOrigin(0.5f, 0.44f),
            )
    } else {
        fadeOut(tween(PANEL_RECEDE_FADE_MS, easing = EmphasizedAccelerateEasing)) +
            slideOutVertically(tween(PANEL_RECEDE_MS, easing = EmphasizedAccelerateEasing)) { -minOf(it / 40, maxRecedePx) } +
            scaleOut(
                tween(PANEL_RECEDE_MS, easing = EmphasizedAccelerateEasing),
                targetScale = 0.966f,
                transformOrigin = TransformOrigin(0.5f, 0.48f),
            )
    }

    LauncherPageHost(page, enter, exit, content)
}

@Composable
internal fun tacticalSheetEnter(animated: Boolean): EnterTransition {
    if (!animated) return EnterTransition.None
    val maxLiftPx = with(LocalDensity.current) { 28.dp.roundToPx() }
    return fadeIn(tween(SHEET_ENTER_FADE_MS, delayMillis = SHEET_ENTER_FADE_DELAY_MS, easing = StandardDecelerateEasing)) +
        slideInVertically(tween(SHEET_ENTER_MS, easing = EmphasizedDecelerateEasing)) { minOf(it / 22, maxLiftPx) } +
        scaleIn(
            tween(SHEET_ENTER_MS, easing = EmphasizedDecelerateEasing),
            initialScale = 0.968f,
            transformOrigin = TransformOrigin(0.5f, 0.44f),
        )
}

@Composable
internal fun tacticalSheetExit(animated: Boolean): ExitTransition {
    if (!animated) return ExitTransition.None
    val maxExitDropPx = with(LocalDensity.current) { 20.dp.roundToPx() }
    return fadeOut(tween(SHEET_EXIT_FADE_MS, easing = EmphasizedAccelerateEasing)) +
        slideOutVertically(tween(SHEET_EXIT_MS, easing = EmphasizedAccelerateEasing)) { minOf(it / 26, maxExitDropPx) } +
        scaleOut(
            tween(SHEET_EXIT_MS, easing = EmphasizedAccelerateEasing),
            targetScale = 0.974f,
            transformOrigin = TransformOrigin(0.5f, 0.44f),
        )
}

/** 叠加页前景卡片运动：外层全屏底幕保持贴边纯淡入淡出，卡片本体在绘制阶段做微上浮与缩放。 */
@Composable
internal fun Modifier.tacticalSheetContentMotion(): Modifier {
    val scope = LocalPageAnimatedVisibilityScope.current ?: return this
    if (!uiAnimationsEnabled()) return this
    val offsetYFraction = scope.transition.animateFloat(
        transitionSpec = {
            if (EnterExitState.PreEnter isTransitioningTo EnterExitState.Visible) {
                tween(SHEET_ENTER_MS, easing = EmphasizedDecelerateEasing)
            } else {
                tween(SHEET_EXIT_MS, easing = EmphasizedAccelerateEasing)
            }
        },
        label = "SheetContentOffsetY",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> 1f
            EnterExitState.Visible -> 0f
            EnterExitState.PostExit -> 0.72f
        }
    }
    val scaleState = scope.transition.animateFloat(
        transitionSpec = {
            if (EnterExitState.PreEnter isTransitioningTo EnterExitState.Visible) {
                tween(SHEET_ENTER_MS, easing = EmphasizedDecelerateEasing)
            } else {
                tween(SHEET_EXIT_MS, easing = EmphasizedAccelerateEasing)
            }
        },
        label = "SheetContentScale",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> 0.968f
            EnterExitState.Visible -> 1f
            EnterExitState.PostExit -> 0.974f
        }
    }
    return this.graphicsLayer {
        val maxShiftY = minOf(size.height * 0.04f, 24.dp.toPx())
        translationY = offsetYFraction.value * maxShiftY
        val s = scaleState.value
        scaleX = s
        scaleY = s
        transformOrigin = TransformOrigin(0.5f, 0.44f)
    }
}

internal fun browserPageEnter(animated: Boolean): EnterTransition =
    if (animated) fadeIn(tween(BROWSER_FADE_ENTER_MS, easing = StandardDecelerateEasing))
    else EnterTransition.None

internal fun browserPageExit(animated: Boolean): ExitTransition =
    if (animated) fadeOut(tween(BROWSER_FADE_EXIT_MS, easing = EmphasizedAccelerateEasing))
    else ExitTransition.None
