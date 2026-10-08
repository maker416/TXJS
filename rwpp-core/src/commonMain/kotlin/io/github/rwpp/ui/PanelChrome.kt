/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rwpp.i18n.readI18n

/** 与 [io.github.rwpp.widget.ExitButton] 同尺寸，嵌入顶栏行内，无需单独占一行。 */
internal val PanelCloseButtonSize = 40.dp

@Composable
internal fun PanelCloseIconButton(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = onClose,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(0.85f),
        ),
        modifier = modifier.size(PanelCloseButtonSize),
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = readI18n("common.close"),
            tint = MaterialTheme.colorScheme.surfaceTint,
            modifier = Modifier.size(22.dp),
        )
    }
}
