/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.ConnectionFailure
import io.github.rwpp.net.localized

/** 可直接放入现有对话框的完整卡片，避免 LoadingView 重复嵌套旧卡片。 */
@Composable
fun ConnectionFailureCard(failure: ConnectionFailure, onDismiss: () -> Unit) {
    BorderCard(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 620.dp),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        ConnectionFailureContent(failure, onDismiss)
    }
}

/** 可复用于其他宿主；关闭区与确认按钮不参与正文滚动，窄小视口仍可退出。 */
@Composable
fun ConnectionFailureContent(failure: ConnectionFailure, onDismiss: () -> Unit) {
    val text = failure.localized()
    var showDetails by remember(failure) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                text.title,
                modifier = Modifier.fillMaxWidth().padding(end = 56.dp, top = 8.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ExitButton(onDismiss)
        }
        Column(
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                .verticalScroll(rememberScrollState()).testTag("connection-failure-body"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(readI18n("connectionError.reasonLabel"), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(text.explanation, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Text(readI18n("connectionError.suggestionLabel"), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(text.suggestion, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            if (failure.details.isNotBlank()) {
                RWTextButton(
                    readI18n(if (showDetails) "connectionError.hideDetails" else "connectionError.showDetails"),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { showDetails = !showDetails }
                if (showDetails) {
                    Text(readI18n("connectionError.details"), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 原始诊断只在用户展开后显示，可选中复制，不挤走确认按钮。
                    SelectionContainer {
                        Text(
                            failure.details,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState()).testTag("connection-failure-details"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        RWTextButton(
            readI18n("connectionError.ok"),
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
            onClick = onDismiss,
        )
    }
}
