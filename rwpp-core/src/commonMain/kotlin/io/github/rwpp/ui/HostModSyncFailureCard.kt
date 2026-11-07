/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.RWTextButton

/** 复用开房加载弹层，失败期间不创建游戏连接，按钮由玩家明确决定是否放弃分享。 */
@Composable
internal fun HostModSyncFailureCard(
    message: String,
    onRetry: () -> Unit,
    onContinueWithoutSharing: () -> Unit,
    onDismiss: () -> Unit,
) {
    BorderCard(
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 620.dp),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Text(
                    readI18n("modSync.hostPreflightFailedTitle"),
                    modifier = Modifier.fillMaxWidth().padding(end = 56.dp, top = 8.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                ExitButton(onDismiss)
            }
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(message, color = MaterialTheme.colorScheme.onSurface)
                Text(readI18n("modSync.hostPreflightFailedHint"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.align(Alignment.End), horizontalAlignment = Alignment.End) {
                RWTextButton(
                    readI18n("modSync.retrySharing"),
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = onRetry,
                )
                RWTextButton(
                    readI18n("modSync.hostContinueWithoutSharing"),
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = onContinueWithoutSharing,
                )
            }
        }
    }
}
