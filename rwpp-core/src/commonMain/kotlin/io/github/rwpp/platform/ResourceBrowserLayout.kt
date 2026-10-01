/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.rwpp.config.ResourceBrowserOrientation

/** Android 临时切换屏幕方向；桌面端提供竖屏视口。null 立即恢复原方向。 */
@Composable
expect fun ResourceBrowserLayout(
    orientation: ResourceBrowserOrientation?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)
