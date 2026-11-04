/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import io.github.rwpp.app.UpdateProgress
import io.github.rwpp.app.UpdateStage
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.net.LatestVersionProfile
import io.github.rwpp.platform.BackHandler
import io.github.rwpp.projectVersion
import io.github.rwpp.widget.UiLinearProgressIndicator
import io.github.rwpp.widget.UiMotionTheme

private val UpdateColors = darkColorScheme(
    primary = Color(0xFFABCF95),
    onPrimary = Color(0xFF192B12),
    background = Color(0xFF111412),
    onBackground = Color(0xFFF0F2EE),
    surface = Color(0xFF1B1F1C),
    onSurface = Color(0xFFF0F2EE),
    onSurfaceVariant = Color(0xFFADB5AD),
    outline = Color(0xFF626C62),
    outlineVariant = Color(0xFF353D35),
    error = Color(0xFFFFB4AB),
)

private fun updateText(key: String) = readI18n("mandatoryUpdate.$key", I18nType.RWPP)

/** 全屏根页面：不组合启动器，返回键不会关闭更新闸门。 */
@Composable
fun MandatoryUpdateScreen(
    release: LatestVersionProfile,
    progress: UpdateProgress,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onExit: () -> Unit,
    onOpenRelease: () -> Unit,
) {
    BackHandler(true) {}
    val inheritedTypography = MaterialTheme.typography
    // 保留美术包字体，文字颜色统一跟随本页配色，避免浅色主题的深色字落到深背景上。
    val typography = remember(inheritedTypography) {
        inheritedTypography.copy(
            displayLarge = inheritedTypography.displayLarge.copy(color = Color.Unspecified),
            displayMedium = inheritedTypography.displayMedium.copy(color = Color.Unspecified),
            displaySmall = inheritedTypography.displaySmall.copy(color = Color.Unspecified),
            headlineLarge = inheritedTypography.headlineLarge.copy(color = Color.Unspecified),
            headlineMedium = inheritedTypography.headlineMedium.copy(color = Color.Unspecified),
            headlineSmall = inheritedTypography.headlineSmall.copy(color = Color.Unspecified),
            titleLarge = inheritedTypography.titleLarge.copy(color = Color.Unspecified),
            titleMedium = inheritedTypography.titleMedium.copy(color = Color.Unspecified),
            titleSmall = inheritedTypography.titleSmall.copy(color = Color.Unspecified),
            bodyLarge = inheritedTypography.bodyLarge.copy(color = UpdateColors.onSurface),
            bodyMedium = inheritedTypography.bodyMedium.copy(color = Color.Unspecified),
            bodySmall = inheritedTypography.bodySmall.copy(color = Color.Unspecified),
            labelLarge = inheritedTypography.labelLarge.copy(color = Color.Unspecified),
            labelMedium = inheritedTypography.labelMedium.copy(color = Color.Unspecified),
            labelSmall = inheritedTypography.labelSmall.copy(color = Color.Unspecified),
        )
    }
    UiMotionTheme(colorScheme = UpdateColors, typography = typography) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            val compact = maxHeight < 500.dp
            val wide = maxWidth >= 600.dp
            Column(
                Modifier.widthIn(max = 880.dp).fillMaxSize()
                    .padding(horizontal = if (wide) 24.dp else 16.dp, vertical = if (compact) 12.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        updateText("brand"),
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    TextButton(onClick = onExit, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(updateText("exit"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(
                    Modifier.weight(1f).fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column {
                        // 正文是唯一滚动区域；长日志不会把进度或安装按钮推到屏幕外。
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                                .padding(
                                    horizontal = if (wide) 28.dp else 20.dp,
                                    vertical = if (compact) 16.dp else if (wide) 28.dp else 20.dp,
                                ),
                        ) {
                            UpdateHeading(release, compact)
                            Spacer(Modifier.height(if (compact) 20.dp else 28.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(20.dp))
                            Text(
                                updateText("notes"),
                                modifier = Modifier.semantics { heading() },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(12.dp))
                            ReleaseNotes(release)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        UpdateControls(progress, onUpdate, onCancel, onOpenRelease, compact, wide)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotes(release: LatestVersionProfile) {
    val body = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    )
    val heading = body.copy(fontWeight = FontWeight.SemiBold)
    SelectionContainer {
        Markdown(
            release.body.ifBlank { updateText("emptyNotes") },
            modifier = Modifier.fillMaxWidth(),
            colors = markdownColor(),
            typography = markdownTypography(
                h1 = heading.copy(fontSize = 20.sp, lineHeight = 28.sp),
                h2 = heading.copy(fontSize = 18.sp, lineHeight = 26.sp),
                h3 = heading.copy(fontSize = 16.sp, lineHeight = 24.sp),
                h4 = heading,
                h5 = heading,
                h6 = heading,
                text = body,
                paragraph = body,
                ordered = body,
                bullet = body,
                list = body,
            ),
        )
    }
}

@Composable
private fun UpdateHeading(release: LatestVersionProfile, compact: Boolean) {
    Text(
        updateText("title"),
        modifier = Modifier.semantics { heading() },
        fontSize = if (compact) 24.sp else 28.sp,
        lineHeight = if (compact) 32.sp else 36.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        updateText("subtitle"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    )
    Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "${updateText("current")} $projectVersion",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "${updateText("latest")} ${release.version}",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (release.prerelease) {
            Text(updateText("preview"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun UpdateControls(
    progress: UpdateProgress,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onOpenRelease: () -> Unit,
    compact: Boolean,
    wide: Boolean,
) {
    val busy = progress.stage in listOf(UpdateStage.DOWNLOADING, UpdateStage.VERIFYING, UpdateStage.EXTRACTING)
    val knownDownload = progress.stage == UpdateStage.DOWNLOADING && progress.totalBytes > 0
    val key = when (progress.stage) {
        UpdateStage.READY -> "ready"
        UpdateStage.DOWNLOADING -> "downloading"
        UpdateStage.VERIFYING -> "verifying"
        UpdateStage.EXTRACTING -> "extracting"
        UpdateStage.INSTALLING -> "installing"
        UpdateStage.PERMISSION_REQUIRED -> "permission"
        UpdateStage.FAILED -> "failed"
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = if (wide) 28.dp else 20.dp, vertical = if (compact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (progress.stage != UpdateStage.READY) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    updateText(key),
                    modifier = Modifier.weight(1f),
                    color = if (progress.stage == UpdateStage.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (knownDownload) {
                    Text(
                        "${(progress.fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                        modifier = Modifier.padding(start = 12.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        if (busy) {
            if (knownDownload) {
                LinearProgressIndicator(
                    progress = { progress.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            } else {
                UiLinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            if (progress.stage == UpdateStage.DOWNLOADING) {
                val total = if (knownDownload) " / ${updateBytes(progress.totalBytes)}" else ""
                val speed = if (progress.bytesPerSecond > 0) " · ${updateBytes(progress.bytesPerSecond)}/s" else ""
                val parts = if (progress.parts > 1) " · ${progress.part}/${progress.parts}" else ""
                Text(
                    "${updateBytes(progress.downloadedBytes)}$total$speed$parts",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
        if (progress.stage == UpdateStage.FAILED && !progress.error.isNullOrBlank()) {
            Text(
                progress.error.orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val primaryAction: @Composable (Modifier) -> Unit = { modifier ->
                if (busy) {
                    OutlinedButton(onClick = onCancel, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp)) {
                        Text(updateText("stop"), color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Button(
                        onClick = onUpdate,
                        modifier = modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Text(
                            updateText(when (progress.stage) {
                                UpdateStage.INSTALLING -> "reinstall"
                                UpdateStage.PERMISSION_REQUIRED -> "grant"
                                UpdateStage.FAILED -> "retry"
                                else -> "download"
                            }),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            if (maxWidth < 400.dp) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    primaryAction(Modifier.fillMaxWidth())
                    ManualInstallButton(onOpenRelease, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ManualInstallButton(onOpenRelease)
                    Spacer(Modifier.weight(1f))
                    primaryAction(Modifier)
                }
            }
        }
    }
}

@Composable
private fun ManualInstallButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text(updateText("manual"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun updateBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
