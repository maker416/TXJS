/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.browser.BrowserKernelException
import io.github.rwpp.net.browser.BrowserKernelFailureKind
import io.github.rwpp.net.browser.BrowserKernelProgress
import io.github.rwpp.net.browser.BrowserKernelRequirement
import io.github.rwpp.net.browser.BrowserKernelStage
import io.github.rwpp.widget.UiLinearProgressIndicator
import io.github.rwpp.widget.uiAnimationsEnabled
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowserKernelPanel(
    visible: Boolean,
    checking: Boolean,
    running: Boolean,
    failure: BrowserKernelException?,
    restartNeeded: Boolean,
    clientUpdateRequired: Boolean,
    local: BrowserKernelRequirement?,
    target: BrowserKernelRequirement?,
    progress: BrowserKernelProgress?,
    onContinue: (() -> Unit)?,
    onPrimaryAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = uiAnimationsEnabled()
    val busy = checking || running
    val retry = progress?.takeIf { running && it.stage == BrowserKernelStage.RETRYING }
    val download = progress?.takeIf { running && it.stage == BrowserKernelStage.DOWNLOADING && it.total > 0 }
    val fraction = download?.let { (it.bytes.toFloat() / it.total).coerceIn(0f, 1f) }
    val status = when {
        retry != null -> readI18n("browser.kernelAutoRetrying", arg = arrayOf(retry.retryAttempt.toString(), retry.maxRetries.toString()))
        fraction != null -> readI18n("browser.kernelDownloading")
        checking || !visible -> readI18n("browser.kernelChecking")
        restartNeeded -> readI18n("browser.kernelRestart")
        failure != null -> readI18n(when (failure.kind) {
            BrowserKernelFailureKind.NETWORK -> "browser.kernelNetworkFailed"
            BrowserKernelFailureKind.VERIFICATION -> "browser.kernelVerificationFailed"
            BrowserKernelFailureKind.FILE_ACCESS -> "browser.kernelFileAccessFailed"
            BrowserKernelFailureKind.INSTALLATION -> "browser.kernelFailed"
        })
        running -> readI18n("browser.kernelPreparing")
        local != null -> readI18n("browser.kernelUpdateRequired")
        else -> readI18n("browser.kernelRequired")
    }
    val primaryAction = if (!visible || checking || restartNeeded) null else readI18n(when {
        running -> "browser.kernelCancel"
        failure != null -> "browser.kernelRetry"
        local != null -> "browser.kernelUpgrade"
        else -> "browser.kernelDownload"
    })

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val padding = if (maxHeight < 300.dp) 12.dp else 20.dp
        val appearance = remember { MutableTransitionState(visible && !motion) }
        appearance.targetState = visible
        AnimatedVisibility(
            visibleState = appearance,
            enter = if (motion) fadeIn(tween(190)) + slideInVertically(tween(230)) { it / 12 } else EnterTransition.None,
            exit = if (motion) fadeOut(tween(130)) + slideOutVertically(tween(160)) { -it / 20 } else ExitTransition.None,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = padding),
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 452.dp).fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = lerp(colors.surface, colors.primary, 0.05f),
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.onSurface.copy(alpha = 0.15f)),
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = if (motion) Modifier.animateContentSize(tween(200)) else Modifier,
                ) {
                    Column(
                        modifier = Modifier.weight(1f, fill = false)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .testTag("browser-kernel-body")
                            .padding(padding),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BrowserKernelGlobe(scanning = visible && busy, modifier = Modifier.size(56.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    readI18n("browser.kernelTitle"),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = colors.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(
                                    status,
                                    modifier = Modifier.testTag("browser-kernel-status").semantics { liveRegion = LiveRegionMode.Polite },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (failure != null) colors.error else colors.onSurfaceVariant,
                                )
                            }
                        }

                        if (busy || !visible) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val bar = Modifier.fillMaxWidth().height(4.dp)
                                val track = colors.onSurface.copy(alpha = 0.12f)
                                if (download != null && fraction != null) {
                                    LinearProgressIndicator(progress = { fraction }, modifier = bar, trackColor = track)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${kernelSize(download.bytes)} / ${kernelSize(download.total)}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                        Text("${(fraction * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                                    }
                                } else if (visible) {
                                    UiLinearProgressIndicator(modifier = bar, trackColor = track)
                                } else {
                                    LinearProgressIndicator(progress = { 1f }, modifier = bar, trackColor = track)
                                }
                            }
                        }
                        if (visible && !checking && fraction == null && (target != null || local != null)) {
                            val currentVersion = local?.manifest?.componentVersion
                            val targetVersion = target?.manifest?.componentVersion
                            val version = when {
                                currentVersion == null -> targetVersion.orEmpty()
                                targetVersion == null || currentVersion == targetVersion -> currentVersion
                                else -> "$currentVersion → $targetVersion"
                            }
                            Surface(shape = RoundedCornerShape(14.dp), color = colors.onSurface.copy(alpha = 0.055f)) {
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    KernelMetadata(readI18n("browser.kernelVersion"), version)
                                    if (!restartNeeded && target != null) {
                                        KernelMetadata(readI18n("browser.kernelDownloadSize"), kernelSize(target.manifest.parts.sumOf { it.size }))
                                    }
                                }
                            }
                        }
                        if (failure != null) BrowserKernelFailureDetails(failure)
                        if (clientUpdateRequired) {
                            Text(readI18n("browser.kernelClientUpdate"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }

                    if (primaryAction != null || onContinue != null) {
                        HorizontalDivider(color = colors.onSurface.copy(alpha = 0.1f))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = padding, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            if (onContinue != null) {
                                    TextButton(onClick = onContinue, enabled = visible) {
                                    Text(readI18n("browser.kernelUseCurrent"), color = colors.onSurface)
                                }
                            }
                            if (primaryAction != null) {
                                if (running) {
                                    TextButton(onClick = onPrimaryAction, enabled = visible) { Text(primaryAction, color = colors.onSurface) }
                                } else {
                                    Button(onClick = onPrimaryAction, enabled = visible) { Text(primaryAction, color = colors.onPrimary) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KernelMetadata(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun BrowserKernelFailureDetails(failure: BrowserKernelException) {
    val details = buildList {
        failure.path?.takeIf { it.isNotBlank() }?.let { add(readI18n("browser.kernelFilePath") + " " + it) }
        val reason = failure.systemReason?.takeIf { it.isNotBlank() }
            ?: failure.cause?.message?.takeIf { it.isNotBlank() }
            ?: failure.message.orEmpty()
        if (reason.isNotBlank()) add(readI18n("browser.kernelFailureReason") + " " + reason)
    }.joinToString("\n")
    if (details.isNotBlank()) SelectionContainer {
        Text(
            details,
            modifier = Modifier.fillMaxWidth().testTag("browser-kernel-failure-details"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(readI18n("browser.kernelRetryCached"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun BrowserKernelReadyNotice(version: String?, showNotice: Boolean) {
    val motion = uiAnimationsEnabled()
    val visibility = remember { MutableTransitionState(!motion).apply { targetState = true } }
    LaunchedEffect(Unit) {
        delay(1800)
        visibility.targetState = false
    }
    if (!showNotice || (!visibility.currentState && !visibility.targetState)) return

    // Keep the short notice above the native web view without taking keyboard focus.
    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = if (motion) fadeIn(tween(170)) + slideInVertically(tween(230)) { -it / 4 } else EnterTransition.None,
            exit = if (motion) fadeOut(tween(150)) + slideOutVertically(tween(190)) { -it / 5 } else ExitTransition.None,
        ) {
            val colors = MaterialTheme.colorScheme
            Surface(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                color = lerp(colors.surface, colors.primary, 0.07f),
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.onSurface.copy(alpha = 0.15f)),
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                    Text(readI18n("browser.kernelReady"), style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                    if (version != null) Text(version, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

private fun kernelSize(bytes: Long): String {
    val mib = 1024 * 1024
    return if (bytes in 1 until mib) "${(bytes + 1023) / 1024} KiB" else "${(bytes.toDouble() / mib).roundToInt()} MiB"
}
