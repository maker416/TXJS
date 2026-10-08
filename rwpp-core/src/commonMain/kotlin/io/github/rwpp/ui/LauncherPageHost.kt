/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import io.github.rwpp.platform.BackHandlerScope
import io.github.rwpp.widget.LocalDialogInteractive

@Composable
internal fun LauncherPageHost(
    page: LauncherPage,
    enter: EnterTransition,
    exit: ExitTransition,
    content: @Composable () -> Unit,
) {
    LauncherOverlayHost(
        visible = launcherPage == page,
        isInteractive = { launcherPage == page && !UI.showFriendsView && !UI.showAccountView },
        enter = enter,
        exit = exit,
        content = content,
    )
}

/** 逻辑可见性立即决定交互资格，退出动画只保留绘制。 */
@Composable
internal fun LauncherOverlayHost(
    visible: Boolean,
    isInteractive: () -> Boolean,
    enter: EnterTransition,
    exit: ExitTransition,
    content: @Composable () -> Unit,
) {
    val parentInteractive = LocalDialogInteractive.current
    val currentInteractive = rememberUpdatedState { visible && parentInteractive && isInteractive() }
    AnimatedVisibility(
        visible = visible,
        // 退出中的整屏页仍参与命中测试。把它放到活动页下方，避免旧页吞掉新页的 X。
        // -1 也让外层旧页退到 Scaffold 后方，覆盖任务页在 Scaffold 内的情况。
        modifier = Modifier.zIndex(if (currentInteractive.value()) 1f else -1f).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (!currentInteractive.value()) event.changes.forEach { it.consume() }
                }
            }
        },
        enter = enter,
        exit = exit,
    ) {
        CompositionLocalProvider(LocalDialogInteractive provides currentInteractive.value()) {
            BackHandlerScope(enabled = { currentInteractive.value() }, content = content)
        }
    }
}
