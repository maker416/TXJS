/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n

/** 两项可检测的系统授权；厂商后台策略不计入完成数量。 */
internal data class BackgroundConnectionPermissionState(
    val notificationsEnabled: Boolean,
    val notificationChannelEnabled: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val notificationsAllowed: Boolean get() = notificationsEnabled && notificationChannelEnabled
    val enabledCount: Int get() = (if (notificationsAllowed) 1 else 0) + (if (batteryUnrestricted) 1 else 0)
}

@Composable
internal fun BackgroundConnectionSettingsRow(state: BackgroundConnectionPermissionState, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PermissionIcon(BackgroundBatteryIcon, MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(readI18n("settings.backgroundConnection"), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        readI18n("settings.backgroundConnectionEntryHint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PermissionBadge(state.enabledCount == 2, permissionSummary(state))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            HorizontalDivider()
        }
    }
}

@Composable
internal fun BackgroundConnectionPermissionPanel(
    state: BackgroundConnectionPermissionState,
    busy: Boolean,
    onNotifications: () -> Unit,
    onBattery: () -> Unit,
    onSystemSettings: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.widthIn(max = 760.dp).fillMaxWidth(0.92f).padding(vertical = 12.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PermissionIcon(BackgroundBatteryIcon, MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(readI18n("settings.backgroundConnection"),
                                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold))
                            Text(permissionSummary(state), style = MaterialTheme.typography.bodySmall,
                                color = permissionStatusColor(state.enabledCount == 2))
                        }
                        IconButton(onClick = onDismissRequest) {
                            Icon(Icons.Default.Close, readI18n("common.close"), modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BoxWithConstraints {
                            val notifications: @Composable (Modifier) -> Unit = { modifier ->
                                BackgroundPermissionCard(
                                    icon = Icons.Default.Notifications,
                                    title = readI18n("settings.backgroundConnectionNotifications"),
                                    granted = state.notificationsAllowed,
                                    description = readI18n(when {
                                        !state.notificationsEnabled -> "settings.backgroundConnectionNotificationsDenied"
                                        !state.notificationChannelEnabled -> "settings.backgroundConnectionChannelDisabled"
                                        else -> "settings.backgroundConnectionNotificationsAllowed"
                                    }),
                                    enabled = !busy,
                                    onClick = onNotifications,
                                    modifier = modifier,
                                )
                            }
                            val battery: @Composable (Modifier) -> Unit = { modifier ->
                                BackgroundPermissionCard(
                                    icon = BackgroundBatteryIcon,
                                    title = readI18n("settings.backgroundConnectionBattery"),
                                    granted = state.batteryUnrestricted,
                                    description = readI18n(if (state.batteryUnrestricted) "settings.backgroundConnectionAllowed"
                                        else "settings.backgroundConnectionRestricted"),
                                    enabled = !busy,
                                    onClick = onBattery,
                                    modifier = modifier,
                                )
                            }
                            if (maxWidth >= 460.dp) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    notifications(Modifier.weight(1f))
                                    battery(Modifier.weight(1f))
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    notifications(Modifier.fillMaxWidth())
                                    battery(Modifier.fillMaxWidth())
                                }
                            }
                        }
                        Surface(
                            onClick = onSystemSettings,
                            enabled = !busy,
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Default.Settings, null, modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(readI18n("settings.backgroundConnectionOpenSettings"), style = MaterialTheme.typography.titleSmall)
                                    Text(readI18n("settings.backgroundConnectionManualHint"), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(readI18n("settings.backgroundConnectionManualStatus"),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(readI18n("settings.backgroundConnectionHint"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundPermissionCard(
    icon: ImageVector,
    title: String,
    granted: Boolean,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val status = readI18n(if (granted) "settings.backgroundConnectionGranted" else "settings.backgroundConnectionNotGranted")
    val tint = permissionStatusColor(granted)
    Surface(
        modifier = modifier.semantics { stateDescription = "$title: $status" },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                PermissionBadge(granted, status)
            }
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (granted) {
                TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(readI18n("settings.backgroundConnectionManage"), style = MaterialTheme.typography.labelLarge)
                }
            } else {
                FilledTonalButton(onClick = onClick, enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Text(readI18n("settings.backgroundConnectionEnable"), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun PermissionIcon(icon: ImageVector, tint: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = 0.12f)) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(23.dp))
        }
    }
}

@Composable
private fun PermissionBadge(granted: Boolean, text: String) {
    val tint = permissionStatusColor(granted)
    Surface(shape = RoundedCornerShape(20.dp), color = tint.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (granted) Icons.Default.CheckCircle else Icons.Default.Info, null,
                tint = tint, modifier = Modifier.size(14.dp))
            Text(text, color = tint, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun permissionStatusColor(granted: Boolean): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        if (granted) Color(0xFF7ADBB0) else Color(0xFFF0BC72)
    } else {
        if (granted) Color(0xFF176B48) else Color(0xFF885500)
    }

private fun permissionSummary(state: BackgroundConnectionPermissionState) =
    readI18n("settings.backgroundConnectionSummary", I18nType.RWPP, state.enabledCount.toString(), "2")

private val BackgroundBatteryIcon = ImageVector.Builder("BackgroundBattery", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        moveTo(9f, 2f); lineTo(15f, 2f); lineTo(15f, 4f); lineTo(18f, 4f)
        lineTo(18f, 22f); lineTo(6f, 22f); lineTo(6f, 4f); lineTo(9f, 4f); close()
        moveTo(8f, 6f); lineTo(16f, 6f); lineTo(16f, 20f); lineTo(8f, 20f); close()
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(13f, 7f); lineTo(9f, 14f); lineTo(12f, 14f)
        lineTo(11f, 19f); lineTo(15f, 12f); lineTo(12f, 12f); close()
    }
}.build()
