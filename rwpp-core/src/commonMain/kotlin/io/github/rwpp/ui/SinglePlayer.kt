/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.LocalWindowManager
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.destruction_30
import io.github.rwpp.rwpp_core.generated.resources.stacks_30
import io.github.rwpp.rwpp_core.generated.resources.swords_30
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.WindowManager
import io.github.rwpp.widget.v2.ExpandedCard
import io.github.rwpp.widget.v2.bounceClick
import org.jetbrains.compose.resources.painterResource

/**
 * 单人游戏入口列表，整合主菜单中的「战役」「遭遇战」「沙盒编辑器」。
 * 整体风格与多人游戏、任务等面板保持一致：深色半透明卡片 + surfaceContainer 边框，
 * 不使用彩色渐变徽章或分隔线，避免与主界面其他页面风格割裂。
 */
@Composable
fun SinglePlayerView(
    onExit: () -> Unit,
    onMission: () -> Unit,
    onSkirmish: () -> Unit,
    onSandbox: () -> Unit,
) {
    BackHandler(true, onExit)
    DisposableEffect(Unit) {
        onDispose {
            CloseUIPanelEvent("singlePlayer").broadcastIn()
        }
    }

    ExpandedCard(
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        Box {
            ExitButton(onExit)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(36.dp))

                // 标题区
                Text(
                    readI18n("menu.singlePlayer.title"),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    readI18n("menu.singlePlayer.subtitle"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(28.dp))

                val cardWidth = when (LocalWindowManager.current) {
                    WindowManager.Small -> 1f
                    WindowManager.Middle -> 0.88f
                    WindowManager.Large -> 0.7f
                }

                SinglePlayerEntry(
                    modifier = Modifier.fillMaxWidth(cardWidth),
                    icon = painterResource(Res.drawable.destruction_30),
                    title = readI18n("menu.singlePlayer.mission"),
                    description = readI18n("menu.singlePlayer.missionDesc"),
                    onClick = onMission,
                )

                Spacer(Modifier.height(12.dp))

                SinglePlayerEntry(
                    modifier = Modifier.fillMaxWidth(cardWidth),
                    icon = painterResource(Res.drawable.swords_30),
                    title = readI18n("menu.singlePlayer.skirmish"),
                    description = readI18n("menu.singlePlayer.skirmishDesc"),
                    onClick = onSkirmish,
                )

                Spacer(Modifier.height(12.dp))

                SinglePlayerEntry(
                    modifier = Modifier.fillMaxWidth(cardWidth),
                    icon = painterResource(Res.drawable.stacks_30),
                    title = readI18n("menu.singlePlayer.sandbox"),
                    description = readI18n("menu.singlePlayer.sandboxDesc"),
                    onClick = onSandbox,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SinglePlayerEntry(
    modifier: Modifier = Modifier,
    icon: Painter,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    BorderCard(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .bounceClick(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 图标徽章：使用主题色轻量背景，替代原彩色渐变，保持与多人游戏/任务面板一致
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        RoundedCornerShape(14.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
