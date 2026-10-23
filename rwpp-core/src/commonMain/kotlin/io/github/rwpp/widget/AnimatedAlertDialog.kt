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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import io.github.rwpp.config.Settings
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.platform.BackHandlerScope
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

/** 保留扩展已经编译使用的原签名。 */
@Composable
fun AnimatedTransitionDialog(
    onDismissRequest: () -> Unit,
    enableDismiss: Boolean,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (AnimatedTransitionDialogHelper) -> Unit,
) = AnimatedTransitionDialog(onDismissRequest, enableDismiss, contentAlignment, inlineDesktop = false, content)

/**
 * [inlineDesktop] 仅由桌面账号页在整窗根 Box 下显式开启；普通调用和嵌套弹层必须保留原生窗口。
 */
@Composable
fun AnimatedTransitionDialog(
    onDismissRequest: () -> Unit,
    enableDismiss: Boolean,
    contentAlignment: Alignment = Alignment.Center,
    inlineDesktop: Boolean,
    content: @Composable (AnimatedTransitionDialogHelper) -> Unit
) {
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    var dismissRequested by remember { mutableStateOf(false) }
    val enableAnimations = koinInject<Settings>().enableAnimations
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val currentEnableDismiss by rememberUpdatedState(enableDismiss)
    val modalFocus = remember { FocusRequester() }
    val escapeScope = rememberCoroutineScope()

    val helper = remember {
        AnimatedTransitionDialogHelper {
            if (!dismissRequested) {
                if (inlineDesktop) modalFocus.requestFocus()
                dismissRequested = true
                visibility.targetState = false
            }
        }
    }
    // 等动画真正结束再移除 Dialog；重复关闭不会重置计时，快速关闭也不会被延迟入场重新打开。
    LaunchedEffect(dismissRequested, visibility.isIdle, visibility.currentState) {
        if (dismissRequested && visibility.isIdle && !visibility.currentState) currentOnDismissRequest()
    }

    // 即使调用方没有立即复位 visible，也不能留下看不见、仍吞掉鼠标/触摸的原生窗口。
    if (dismissRequested && visibility.isIdle && !visibility.currentState) return

    // 模态框在忙碌和退出动画期间仍独占返回，避免下一次 Esc 关闭底层页面。
    BackHandler(true) {
        if (currentEnableDismiss && !dismissRequested) helper.triggerAnimatedDismiss()
    }
    if (inlineDesktop) {
        LaunchedEffect(Unit) { modalFocus.requestFocus() }
    }

    val dialogSurface: @Composable () -> Unit = {
        CompositionLocalProvider(LocalDialogInteractive provides !dismissRequested) {
            val interactive by rememberUpdatedState(!dismissRequested)
            Box(
                contentAlignment = contentAlignment,
                modifier = Modifier
                    .onPreviewKeyEvent { !interactive }
                    .focusProperties { canFocus = interactive }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (!interactive) event.changes.forEach { it.consume() }
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
                    BackHandlerScope(enabled = { !dismissRequested }) {
                        content(helper)
                    }
                }
            }
        }
    }

    if (inlineDesktop) {
        // 仅供账号页根 Box 下的弹窗显式使用；普通/嵌套调用继续使用原生 Dialog，
        // 不能用局部 fillMaxSize/zIndex 冒充整窗模态层（尤其桌面原生浏览器上方）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(DESKTOP_INLINE_DIALOG_Z_INDEX)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .focusProperties { canFocus = false }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (currentEnableDismiss && !dismissRequested) helper.triggerAnimatedDismiss()
                        },
                    ),
            )
            Box(
                Modifier.fillMaxSize()
                    .desktopEscapeToBackDispatcher(escapeScope)
                    .onPreviewKeyEvent { dismissRequested }
                    .focusRequester(modalFocus)
                    .focusable(),
                contentAlignment = contentAlignment,
            ) {
                dialogSurface()
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

/** 保留扩展已经编译使用的原签名。 */
@Composable
fun AnimatedAlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    enableDismiss: Boolean = true,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) = AnimatedAlertDialog(visible, onDismissRequest, enableDismiss, inlineDesktop = false, content)

@Composable
fun AnimatedAlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    enableDismiss: Boolean = true,
    inlineDesktop: Boolean,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    if (visible && LocalDialogInteractive.current) {
        AnimatedTransitionDialog(
            onDismissRequest = onDismissRequest,
            enableDismiss = enableDismiss,
            inlineDesktop = inlineDesktop,
        ) { animatedTransitionDialogHelper ->
            content(animatedTransitionDialogHelper::triggerAnimatedDismiss)
        }
    }
}
