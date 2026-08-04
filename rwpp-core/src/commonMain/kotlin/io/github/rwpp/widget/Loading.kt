/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rwpp.core.LoadingContext
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

var loadingMessage by mutableStateOf("")

private data class StructuredLoadingMessage(
    val stage: String,
    val count: String,
    val detail: String?
)

@Composable
fun LoadingView(
    visible: Boolean,
    onLoaded: () -> Unit,
    enableAnimation: Boolean = true,
    cancellable: Boolean = false,
    loadContent: suspend LoadingContext.() -> Boolean?
) {
    // 必须持有可取消的 Job：关闭对话框时立刻 cancel，避免退出动画期间后台仍跑完
    // preJoinSync / directJoinServer 导致「取消后仍进房」。
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var loadFinished by remember { mutableStateOf(false) }

    fun cancelLoadIfRunning() {
        if (!loadFinished) {
            loadJob?.cancel()
            loadJob = null
        }
    }

    AnimatedAlertDialog(
        visible,
        onDismissRequest = {
            cancelLoadIfRunning()
            onLoaded()
        },
        enableDismiss = cancellable
    ) { dismiss ->
        var cancel by remember { mutableStateOf(false) }

        fun dismissAndCancel() {
            cancelLoadIfRunning()
            dismiss()
        }

        BorderCard(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .widthIn(min = 300.dp, max = 500.dp)
                .wrapContentHeight(),
            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ) {
            LaunchedEffect(Unit) {
                val job = launch(Dispatchers.IO) {
                    try {
                        val result = loadContent(LoadingContext { loadingMessage = it })
                        loadFinished = true
                        withContext(Dispatchers.Main.immediate) {
                            when (result) {
                                true -> {
                                    loadingMessage = ""
                                    dismiss()
                                }
                                false -> cancel = true
                                null -> Unit
                            }
                        }
                    } catch (_: CancellationException) {
                        // 用户关闭对话框或组合销毁：中止后续进房/开房逻辑
                    }
                }
                loadJob = job
                job.join()
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                // 可取消时点关闭必须走 dismissAndCancel，在退出动画开始前就掐断 IO 协程
                if (cancellable || cancel) {
                    ExitButton(if (cancellable) ({ dismissAndCancel() }) else dismiss)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.Start
                ) {
                    val text = loadingMessage.ifBlank {
                        if (cancel) "Loading failed" else "Loading..."
                    }
                    val structuredMessage = remember(text) { text.toStructuredLoadingMessage() }

                    if (structuredMessage != null && !cancel) {
                        StructuredLoadingContent(
                            message = structuredMessage,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = if (cancellable) 36.dp else 0.dp)
                        )
                    } else {
                        Text(
                            text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = if (cancellable || cancel) 36.dp else 0.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = "tnum"
                            ),
                            textAlign = TextAlign.Start,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (enableAnimation && !cancel) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StructuredLoadingContent(
    message: StructuredLoadingMessage,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                message.stageLabel(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                message.detail ?: message.stage,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            message.count,
            modifier = Modifier
                .widthIn(min = 58.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum"
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

private fun String.toStructuredLoadingMessage(): StructuredLoadingMessage? {
    val text = trim()
    if (!text.startsWith("Loading ", ignoreCase = true)) return null

    val separator = " - "
    val separatorIndex = text.indexOf(separator)
    if (separatorIndex < 0) return null

    val stage = text.substring("Loading ".length, separatorIndex).trim()
    if (stage.isBlank()) return null

    val countAndDetail = text.substring(separatorIndex + separator.length).trim()
    val count = countAndDetail.takeWhile { it.isDigit() }
    if (count.isBlank()) return null

    val detail = countAndDetail
        .drop(count.length)
        .trim()
        .removeSurrounding("(", ")")
        .trim()
        .takeIf { it.isNotBlank() }

    return StructuredLoadingMessage(stage, count, detail)
}

private fun StructuredLoadingMessage.stageLabel(): String {
    return when (stage.lowercase()) {
        "units" -> readI18n("mod.loadingUnits")
        "mod", "mods" -> readI18n("mod.loadingMods")
        else -> readI18n("mod.loadingStage", I18nType.RWPP, stage)
    }
}
