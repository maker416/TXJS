/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证:
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rwpp.coreVersion
import io.github.rwpp.net.Net
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.projectVersion
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.ExitButton
import io.github.rwpp.widget.LargeDividingLine
import io.github.rwpp.widget.v2.ExpandedCard
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

private const val OFFICIAL_QQ_GROUP_NUMBER = "219678874"

@Composable
fun OpenSourceInfoView(
    onExit: () -> Unit,
) = ExpandedCard(
    modifier = Modifier.verticalScroll(rememberScrollState())
) {
    Box {
        ExitButton(onExit)
        Column(modifier = Modifier.padding(18.dp)) {
            BackHandler(true, onExit)

            val net = koinInject<Net>()

            Text(
                "开源说明与许可",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 4.dp)
            )
            Text(
                "RWPP $projectVersion (core $coreVersion)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            LargeDividingLine { 10.dp }

            OfficialQqGroupCard()

            InfoSection(
                title = "项目来源",
                lines = listOf(
                    "铁锈战争极速版基于上游 RWPP 体系 fork 并独立维护。",
                    "本项目开源仓库：maker416/TXJS（https://github.com/maker416/TXJS）。",
                    "项目面向萌新云生态与更清爽的铁锈战争联机体验，在 RWPP 能力之上继续优化上手流程、服务器列表与公开房间曝光。",
                    "本项目不是 Rusted Warfare 官方项目，也不代表原游戏开发者。"
                )
            )

            InfoSection(
                title = "开源许可",
                lines = listOf(
                    "本仓库沿用 GNU Affero General Public License v3.0（AGPL-3.0）。",
                    "二次开发、fork、修改和再分发时，请保留版权声明、许可证文本和源代码提供方式。",
                    "如果你通过网络提供修改后的版本，也需要遵守 AGPL-3.0 对网络交互版本的源代码提供义务。"
                )
            )

            InfoSection(
                title = "游戏资源与依赖",
                lines = listOf(
                    "Rusted Warfare 游戏本体、名称、素材与资源归其原权利方所有。",
                    "本仓库的纯源码发布不包含原版游戏库、商业游戏本体或需要用户自行准备的游戏资源。",
                    "Android 资源或桌面游戏库缺失时，请从你合法拥有的本地客户端中自行补齐。"
                )
            )

            InfoSection(
                title = "再分发建议",
                lines = listOf(
                    "发布修改版时，请清楚标注你修改了什么、基于哪个版本、源码在哪里获取。",
                    "不要移除上游 RWPP、本项目贡献者以及相关第三方库的版权与许可信息。",
                    "如果额外加入素材、脚本或扩展，请同时标注它们各自的来源和授权。"
                )
            )

            InfoSection(
                title = "致谢",
                lines = listOf(
                    "感谢上游 RWPP 项目提供技术基底。",
                    "感谢 RW-HPS 等铁锈战争生态项目，以及所有参与反馈、测试和贡献的玩家。",
                    "感谢 Corroding Games / Luke Hoschke 创作 Rusted Warfare。"
                )
            )

            LinkCard("开源仓库 maker416/TXJS", "https://github.com/maker416/TXJS", net)
            LinkCard("上游 RWPP", "https://github.com/Minxyzgo/RWPP", net)
            LinkCard("相关生态 RW-HPS", "https://github.com/deng-rui/RW-HPS", net)
            LinkCard("AGPL-3.0 许可证文本", "https://www.gnu.org/licenses/agpl-3.0.html", net)

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun OfficialQqGroupCard() {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    BorderCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        onClick = {
            clipboardManager.setText(AnnotatedString(OFFICIAL_QQ_GROUP_NUMBER))
            copied = true
        },
        backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.14f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "QQ",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "官方交流群",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f),
                    ) {
                        Text(
                            text = "官方",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "反馈问题、获取更新公告、与玩家交流联机心得",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = OFFICIAL_QQ_GROUP_NUMBER,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 2.sp,
                )
            }
        }

        Text(
            text = if (copied) {
                "群号已复制，打开 QQ 搜索并加入"
            } else {
                "点击卡片复制群号 · 在 QQ 中搜索群号加入"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (copied) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
            },
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun InfoSection(
    title: String,
    lines: List<String>,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
    )
    BorderCard(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        lines.forEach { line ->
            Text(
                "• $line",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun ColumnScope.LinkCard(
    title: String,
    url: String,
    net: Net,
) {
    BorderCard(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        onClick = { net.openUriInBrowser(url) }
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp)
        )
        Text(
            url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
        )
    }
}
