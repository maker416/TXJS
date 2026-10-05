/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import io.github.rwpp.app.UpdateProgress
import io.github.rwpp.app.UpdateStage
import io.github.rwpp.net.LatestVersionProfile
import io.github.rwpp.projectVersion
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.platform.BackHandler

private val UpdateMint = Color(0xFF83E8C2)
private val UpdateMuted = Color(0xFF9BAAB9)
private fun updateText(key: String) = readI18n("mandatoryUpdate.$key", I18nType.RWPP)

/** 全屏根页面：不组合启动器，返回键不会关闭更新闸门。 */
@Composable
fun MandatoryUpdateScreen(
    release: LatestVersionProfile, progress: UpdateProgress,
    onUpdate: () -> Unit, onCancel: () -> Unit, onExit: () -> Unit, onOpenRelease: () -> Unit,
) {
    BackHandler(true) {}
    MaterialTheme(colorScheme = darkColorScheme(primary = UpdateMint, onPrimary = Color(0xFF08291E),
        surface = Color(0xFF15222E), onSurface = Color(0xFFEEF4F8), onSurfaceVariant = UpdateMuted)) {
        CompositionLocalProvider(LocalContentColor provides Color(0xFFEEF4F8)) {
            BoxWithConstraints(Modifier.fillMaxSize().background(Brush.linearGradient(
                listOf(Color(0xFF0C1520), Color(0xFF142936), Color(0xFF102B2A))))) {
                val compact = maxHeight < 500.dp
                val wide = maxWidth >= 760.dp
                Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 32.dp else 20.dp,
                    vertical = if (compact) 12.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 22.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = UpdateMint, shape = RoundedCornerShape(10.dp)) {
                            Text("R", Modifier.padding(horizontal = 13.dp, vertical = 7.dp), color = Color(0xFF08291E),
                                fontWeight = FontWeight.Black, fontSize = 20.sp)
                        }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text("RWJS", fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                            Text("UPDATE CENTER", color = UpdateMuted, fontSize = 10.sp, letterSpacing = 2.sp)
                        }
                        TextButton(onClick = onExit) { Text(updateText("exit"), color = UpdateMuted) }
                    }
                    if (wide) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            Column(Modifier.weight(0.95f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                UpdateHero(release, compact)
                            }
                            ReleaseNotes(release, Modifier.weight(1.05f).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            UpdateHero(release, compact)
                            ReleaseNotes(release, Modifier.fillMaxWidth(), scroll = false)
                        }
                    }
                    UpdateControls(progress, onUpdate, onCancel, onOpenRelease, compact)
                }
            }
        }
    }
}

@Composable
private fun UpdateEmblem(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(UpdateMint.copy(alpha = 0.05f), r)
            drawCircle(UpdateMint.copy(alpha = 0.12f), r * 0.98f, style = Stroke(1.dp.toPx()))
            drawCircle(UpdateMint.copy(alpha = 0.23f), r * 0.72f, style = Stroke(1.dp.toPx()))
            drawCircle(UpdateMint, 4.dp.toPx(), center = Offset(size.width * 0.86f, size.height * 0.25f))
            val center = Offset(size.width / 2, size.height / 2)
            val arrow = r * 0.33f
            val stroke = 2.dp.toPx()
            drawLine(UpdateMint, center.copy(y = center.y - arrow), center.copy(y = center.y + arrow), stroke)
            drawLine(UpdateMint, center.copy(y = center.y + arrow), Offset(center.x - arrow, center.y), stroke)
            drawLine(UpdateMint, center.copy(y = center.y + arrow), Offset(center.x + arrow, center.y), stroke)
        }
    }
}

@Composable
private fun UpdateHero(release: LatestVersionProfile, compact: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = UpdateMint.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
                Text(updateText("required"), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = UpdateMint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (release.prerelease) "PREVIEW" else "RELEASE", color = UpdateMuted, fontSize = 11.sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(if (compact) 14.dp else 22.dp))
        Text(updateText("title"), fontSize = if (compact) 32.sp else 40.sp, lineHeight = if (compact) 40.sp else 50.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(updateText("subtitle"), color = UpdateMuted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(if (compact) 18.dp else 26.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text(updateText("current"), color = UpdateMuted, fontSize = 12.sp)
                Text(projectVersion, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = UpdateMuted, modifier = Modifier.size(18.dp))
            Column {
                Text(updateText("latest"), color = UpdateMint, fontSize = 12.sp)
                Text(release.version, fontSize = 25.sp, color = UpdateMint, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            UpdateEmblem(Modifier.size(if (compact) 65.dp else 86.dp))
            Column {
                Text(updateText("safe"), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(updateText("preserved"), color = UpdateMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ReleaseNotes(release: LatestVersionProfile, modifier: Modifier, scroll: Boolean = true) {
    Surface(modifier, color = Color.White.copy(alpha = 0.035f), shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(updateText("notes"), fontWeight = FontWeight.Bold)
                Text("CHANGELOG", fontSize = 10.sp, color = UpdateMuted, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(14.dp))
            val bodyModifier = if (scroll) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier
            Markdown(release.body.ifBlank { updateText("emptyNotes") }, modifier = bodyModifier.fillMaxWidth(),
                colors = markdownColor(), typography = markdownTypography())
        }
    }
}

@Composable
private fun UpdateControls(progress: UpdateProgress, onUpdate: () -> Unit, onCancel: () -> Unit,
    onOpenRelease: () -> Unit, compact: Boolean) {
    val busy = progress.stage in listOf(UpdateStage.DOWNLOADING, UpdateStage.VERIFYING, UpdateStage.EXTRACTING)
    val key = when (progress.stage) {
        UpdateStage.READY -> "ready"
        UpdateStage.DOWNLOADING -> "downloading"
        UpdateStage.VERIFYING -> "verifying"
        UpdateStage.EXTRACTING -> "extracting"
        UpdateStage.INSTALLING -> "installing"
        UpdateStage.PERMISSION_REQUIRED -> "permission"
        UpdateStage.FAILED -> "failed"
    }
    Surface(color = Color(0xFF1B3037), shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, UpdateMint.copy(alpha = 0.16f))) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 14.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(updateText(key), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), fontSize = 14.sp)
                if (busy) Text("${(progress.fraction * 100).toInt()}%", color = UpdateMint, fontWeight = FontWeight.Bold)
            }
            if (busy) {
                if (progress.stage == UpdateStage.DOWNLOADING && progress.totalBytes > 0) {
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth(), color = UpdateMint)
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = UpdateMint)
                val eta = if (progress.totalBytes > progress.downloadedBytes && progress.bytesPerSecond > 0)
                    " · ~${(progress.totalBytes - progress.downloadedBytes) / progress.bytesPerSecond}s" else ""
                val transferDetails = if (progress.stage == UpdateStage.DOWNLOADING)
                    " · ${updateBytes(progress.bytesPerSecond)}/s$eta" else ""
                val volumeDetails = if (progress.parts > 0) " · ${progress.part}/${progress.parts}" else ""
                Text("${updateBytes(progress.downloadedBytes)} / ${if (progress.totalBytes > 0) updateBytes(progress.totalBytes) else "—"}" +
                    transferDetails + volumeDetails, color = UpdateMuted, fontSize = 12.sp)
            }
            if (progress.stage == UpdateStage.FAILED) {
                Text(progress.error.orEmpty(), color = Color(0xFFFFB4AB), fontSize = 12.sp, maxLines = 3)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenRelease) { Text(updateText("manual"), color = UpdateMuted, fontSize = 12.sp) }
                Spacer(Modifier.weight(1f))
                if (busy) {
                    OutlinedButton(onClick = onCancel) { Text(updateText("stop"), color = UpdateMint) }
                } else {
                    Button(onClick = onUpdate, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) {
                        Text(updateText(when (progress.stage) {
                            UpdateStage.INSTALLING -> "reinstall"
                            UpdateStage.PERMISSION_REQUIRED -> "grant"
                            UpdateStage.FAILED -> "retry"
                            else -> "download"
                        }), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun updateBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
