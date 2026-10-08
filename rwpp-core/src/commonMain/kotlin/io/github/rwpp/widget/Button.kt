/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.rwpp.i18n.readI18n

@Composable
fun MenuButton(
    content: String,
    icon: Any? = null,
    modifier: Modifier = Modifier.size(140.dp).padding(10.dp),
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 10.dp,
        tonalElevation = 10.dp,
        modifier = modifier,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(5.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (icon is Painter)
                Icon(icon, null, tint = MaterialTheme.colorScheme.surfaceTint, modifier = Modifier.padding(5.dp))
            else if (icon is ImageVector)
                Icon(icon, null, tint = MaterialTheme.colorScheme.surfaceTint, modifier = Modifier.padding(5.dp))

            Text(
                content,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(5.dp)
            )
        }
    }
}

@Composable
fun BoxScope.ExitButton(onClick: () -> Unit) {
    FilledIconButton(
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(
                0.8f
            )
        ),
        // 部分页面先绘制关闭按钮再绘制正文；保持按钮在正文之上，触摸热区至少 48dp。
        modifier = Modifier.size(48.dp).align(Alignment.TopEnd).zIndex(1f),
        onClick = onClick,
    ) {
        Icon(
            Icons.Default.Close,
            tint = MaterialTheme.colorScheme.surfaceTint,
            contentDescription = readI18n("common.close"),
            modifier = Modifier.size(24.dp),
        )
    }
}


