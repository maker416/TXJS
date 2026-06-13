/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.v2.LineSpinFadeLoaderIndicator
import io.github.rwpp.widget.v2.bounceClick

@Composable
fun InjectSetupScreen(
    uiState: InjectBuildUiState,
    log: AnnotatedString,
    copyLogText: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = readI18n("inject.title", I18nType.RWPP),
    showSteps: Boolean = true,
    autoRestart: Boolean = false,
    restartCountdownSeconds: Int = 3,
    onRestart: (() -> Unit)? = null,
) {
    var logExpanded by remember { mutableStateOf(false) }
    var copiedHint by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val actionsEnabled = uiState.overall == InjectBuildOverall.Success || uiState.overall == InjectBuildOverall.Failed
    val bodyScrollState = rememberScrollState()
    val logScrollState = rememberScrollState()

    LaunchedEffect(log, logExpanded) {
        if (logExpanded) {
            logScrollState.animateScrollTo(logScrollState.maxValue)
        }
    }

    LaunchedEffect(copiedHint) {
        if (copiedHint) {
            delay(2000)
            copiedHint = false
        }
    }

    BorderCard(
        modifier = modifier
            .fillMaxWidth(0.9f)
            .fillMaxHeight(0.82f)
            .navigationBarsPadding()
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .align(Alignment.CenterHorizontally),
            )

            HorizontalDivider(
                thickness = 2.dp,
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.primary,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(bodyScrollState)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showSteps) {
                    InjectBuildStep.entries.forEach { step ->
                        InjectStepRow(
                            title = stepTitle(step),
                            status = uiState.steps[step] ?: StepStatus.Pending,
                        )
                    }
                }

                when (uiState.overall) {
                    InjectBuildOverall.Success -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                readI18n("inject.restartTitle", I18nType.RWPP),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (autoRestart && onRestart != null) {
                                var remaining by remember { mutableStateOf(restartCountdownSeconds) }
                                LaunchedEffect(Unit) {
                                    while (remaining > 0) {
                                        delay(1000)
                                        remaining -= 1
                                    }
                                    onRestart()
                                }
                                Text(
                                    readI18n("inject.restarting", I18nType.RWPP, remaining.toString()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                Text(
                                    readI18n("inject.restartHint", I18nType.RWPP),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }

                    InjectBuildOverall.Failed -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                readI18n("inject.failedTitle", I18nType.RWPP),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            uiState.errorSummary?.let { summary ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                readI18n("inject.failedHint", I18nType.RWPP),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    else -> Unit
                }

                if (log.text.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (logExpanded) {
                                readI18n("inject.hideDetails", I18nType.RWPP)
                            } else {
                                readI18n("inject.viewDetails", I18nType.RWPP)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.bounceClick { logExpanded = !logExpanded },
                        )

                        if (logExpanded) {
                            Text(
                                text = if (copiedHint) {
                                    readI18n("inject.copied", I18nType.RWPP)
                                } else {
                                    readI18n("inject.copyDetails", I18nType.RWPP)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = if (copiedHint) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.bounceClick {
                                    clipboardManager.setText(AnnotatedString(copyLogText.ifBlank { log.text }))
                                    copiedHint = true
                                },
                            )
                        }
                    }

                    if (logExpanded) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 72.dp, max = 100.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = log,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(logScrollState)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.surfaceContainer,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (onRestart != null) {
                    Button(
                        onClick = onRestart,
                        enabled = actionsEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .alpha(if (actionsEnabled) 1f else 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            disabledContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(
                            readI18n("inject.restartNow", I18nType.RWPP),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                Button(
                    onClick = onExit,
                    enabled = actionsEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (actionsEnabled) 1f else 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (onRestart != null) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (onRestart != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text(
                        readI18n("inject.exit", I18nType.RWPP),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun InjectStepRow(
    title: String,
    status: StepStatus,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                StepStatus.Running -> LineSpinFadeLoaderIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    radius = 12f,
                    penThickness = 4f,
                    elementHeight = 8f,
                )

                StepStatus.Done -> Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(22.dp),
                )

                StepStatus.Failed -> Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )

                StepStatus.Pending -> Box(
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = when (status) {
                StepStatus.Running -> MaterialTheme.colorScheme.primary
                StepStatus.Done -> MaterialTheme.colorScheme.onSurface
                StepStatus.Failed -> MaterialTheme.colorScheme.error
                StepStatus.Pending -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            },
        )
    }
}

@Composable
private fun stepTitle(step: InjectBuildStep): String = when (step) {
    InjectBuildStep.Prepare -> readI18n("inject.step.prepare", I18nType.RWPP)
    InjectBuildStep.ApplyInject -> readI18n("inject.step.apply", I18nType.RWPP)
    InjectBuildStep.CompileDex -> readI18n("inject.step.compile", I18nType.RWPP)
}
