/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.rwpp.config.ResourceBrowserOrientation

@Composable
actual fun ResourceBrowserLayout(
    orientation: ResourceBrowserOrientation?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val viewport = if (orientation == ResourceBrowserOrientation.Portrait) {
            val width = minOf(maxWidth, maxHeight * 9f / 16f)
            Modifier.size(width, width * 16f / 9f)
        } else {
            Modifier.fillMaxSize()
        }
        Box(viewport) { content() }
    }
}
