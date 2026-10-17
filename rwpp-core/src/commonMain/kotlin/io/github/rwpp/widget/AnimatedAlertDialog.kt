/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import io.github.rwpp.AppContext
import io.github.rwpp.config.Settings
import io.github.rwpp.platform.BackHandler
import org.koin.compose.koinInject

internal const val ANIMATION_TIME = 200L
// 页面/弹窗的退出动画只保留绘制，其弹窗与下拉层不能继续抢占输入。
internal val LocalDialogInteractive = staticCompositionLocalOf { true }

private const val DESKTOP_INLINE_DIALOG_Z_INDEX = 32f

// Inspired by https://medium.com/tech-takeaways/ios-like-modal-view-dialog-animation-in-jetpack-compose-fac5778969af

@Composable
internal fun AnimatedModalBottomSheetTransition(
    visible: Boolean,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    val enableAnimations = koinInject<Settings>().enableAnimations

    AnimatedVisibility(
        visible = visible,
        enter = if (enableAnimations) slideInVertically(
            animationSpec = tween(ANIMATION_TIME.toInt()),
            initialOffsetY = { fullHeight -> fullHeight }
        ) else EnterTransition.None,
        exit = if (enableAnimations) slideOutVertically(
            animationSpec = tween(ANIMATION_TIME.toInt()),
            targetOffsetY = { fullHeight -> fullHeight }
        ) else ExitTransition.None,
        content = content
    )
}

@Composable
internal fun AnimatedScaleInTransition(
    visible: Boolean,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    val enableAnimations = koinInject<Settings>().enableAnimations

    AnimatedVisibility(
        visible = visible,
        enter = if (enableAnimations) scaleIn(
            animationSpec = tween(ANIMATION_TIME.toInt()), initialScale = 0.96f
        ) else EnterTransition.None,
        exit = if (enableAnimations) scaleOut(
            animationSpec = tween(ANIMATION_TIME.toInt()), targetScale = 0.96f
        ) else ExitTransition.None,
        content = content
    )
}

@Composable
fun AnimatedTransitionDialog(
    onDismissRequest: () -> Unit,
    enableDismiss: Boolean,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (AnimatedTransitionDialogHelper) -> Unit
) {
    val appContext = koinInject<AppContext>()
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    var dismissRequested by remember { mutableStateOf(false) }
    val enableAnimations = koinInject<Settings>().enableAnimations
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val currentEnableDismiss by rememberUpdatedState(enableDismiss)
    val focusManager = LocalFocusManager.current

    val helper = remember {
        AnimatedTransitionDialogHelper {
            if (!dismissRequested) {
                dismissRequested = true
                visibility.targetState = false
            }
        }
    }
    fun dismissAnimatedDialog() {
        if (appContext.isDesktop()) {
            focusManager.clearFocus()
        }
        helper.triggerAnimatedDismiss()
    }
    // 等动画真正结束再移除 Dialog；重复关闭不会重置计时，快速关闭也不会被延迟入场重新打开。
    LaunchedEffect(dismissRequested, visibility.isIdle, visibility.currentState) {
        if (dismissRequested && visibility.isIdle && !visibility.currentState) currentOnDismissRequest()
    }

    // 即使调用方没有立即复位 visible，也不能留下看不见、仍吞掉鼠标/触摸的原生窗口。
    if (dismissRequested && visibility.isIdle && !visibility.currentState) return

    BackHandler(currentEnableDismiss && !dismissRequested) {
        dismissAnimatedDialog()
    }

    val dialogSurface: @Composable () -> Unit = {
        CompositionLocalProvider(LocalDialogInteractive provides !dismissRequested) {
            val interactive by rememberUpdatedState(!dismissRequested)
            Box(
                contentAlignment = contentAlignment,
                modifier = Modifier.pointerInput(interactive) {
                    if (!interactive) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
            ) {
                AnimatedVisibility(
                    visibleState = visibility,
                    enter = if (enableAnimations) fadeIn(tween(ANIMATION_TIME.toInt())) +
                        scaleIn(tween(ANIMATION_TIME.toInt()), initialScale = 0.96f) else EnterTransition.None,
                    exit = if (enableAnimations) fadeOut(tween(ANIMATION_TIME.toInt())) +
                        scaleOut(tween(ANIMATION_TIME.toInt()), targetScale = 0.96f) else ExitTransition.None,
                ) {
                    content(helper)
                }
            }
        }
    }

    if (appContext.isDesktop()) {
        // RWJS 主 UI 在透明 ComposePanel 内；独立 Dialog 子窗口里 TextField 光标常不绘制，改为同窗口遮罩。
        CompositionLocalProvider(LocalDialogInteractive provides !dismissRequested) {
            val interactive by rememberUpdatedState(!dismissRequested)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(DESKTOP_INLINE_DIALOG_Z_INDEX),
            ) {
                if (currentEnableDismiss) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { dismissAnimatedDialog() },
                            ),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f),
                    contentAlignment = contentAlignment,
                ) {
                    AnimatedVisibility(
                        visibleState = visibility,
                        enter = if (enableAnimations) fadeIn(tween(ANIMATION_TIME.toInt())) +
                            scaleIn(tween(ANIMATION_TIME.toInt()), initialScale = 0.96f) else EnterTransition.None,
                        exit = if (enableAnimations) fadeOut(tween(ANIMATION_TIME.toInt())) +
                            scaleOut(tween(ANIMATION_TIME.toInt()), targetScale = 0.96f) else ExitTransition.None,
                    ) {
                        CompositionLocalProvider(LocalDialogInteractive provides interactive) {
                            content(helper)
                        }
                    }
                }
                if (!interactive) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(2f)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            },
                    )
                }
            }
        }
    } else {
        Dialog(
            onDismissRequest = {
                if (currentEnableDismiss) {
                    helper.triggerAnimatedDismiss()
                }
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            dialogSurface()
        }
    }
}


class AnimatedTransitionDialogHelper(
    private val onDismiss: () -> Unit
) {

    fun triggerAnimatedDismiss() {
        onDismiss()
    }
}

@Composable
fun AnimatedAlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    enableDismiss: Boolean = true,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    if (visible && LocalDialogInteractive.current) {
        AnimatedTransitionDialog(onDismissRequest = onDismissRequest, enableDismiss = enableDismiss) { animatedTransitionDialogHelper ->
            content(animatedTransitionDialogHelper::triggerAnimatedDismiss)
        }
    }
}
