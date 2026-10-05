/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.LauncherMusicController
import io.github.rwpp.widget.LargeDropdownMenu
import io.github.rwpp.widget.themes
import kotlin.math.roundToInt

/** 纵向排布的主题卡片；输入与滑块占满可用宽度，按钮在窄屏自动换行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemeSettingsContent(
    settings: Settings,
    selectedTheme: String,
    backgroundImagePath: String,
    backgroundImageEnabled: Boolean,
    onChangeTheme: (String) -> Unit,
    onEditBackground: (String) -> Unit,
    onChooseBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onRestoreThemeBackground: () -> Unit,
    onManageThemes: () -> Unit,
) {
    val activeTheme = ArtThemeController.activeTheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                readI18n("settings.theme"),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            ThemeSettingsCard("settings.themeAppearance") {
                Text(readI18n("settings.colorScheme"), style = MaterialTheme.typography.bodyMedium)
                val names = themes.keys.toList()
                LargeDropdownMenu(
                    modifier = Modifier.fillMaxWidth(),
                    items = names,
                    label = "",
                    selectedIndex = names.indexOf(selectedTheme).coerceAtLeast(0),
                    selectedItemColor = { name, _ -> themes[name]?.primary ?: MaterialTheme.colorScheme.primary },
                    onItemSelected = { _, name -> onChangeTheme(name) },
                )
                ThemeSettingsHint(
                    if (activeTheme == null) readI18n("settings.themePackHint")
                    else readI18n("settings.themePackActive", arg = arrayOf(activeTheme.spec.theme.name))
                )
                OutlinedButton(onClick = onManageThemes) {
                    Text(readI18n("themes.manageEntry"))
                }
                HorizontalDivider()
                ThemeSettingsSwitch("settings.changeGameTheme", settings.changeGameTheme) { settings.changeGameTheme = it }
            }

            ThemeSettingsCard("settings.themeBackground") {
                OutlinedTextField(
                    value = backgroundImagePath,
                    onValueChange = onEditBackground,
                    label = { Text(readI18n("settings.setBackgroundImagePath")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val hasThemeBackground = activeTheme?.backgroundFile != null
                ThemeSettingsHint(readI18n(when {
                    !backgroundImageEnabled -> "settings.backgroundDefaultHint"
                    hasThemeBackground -> "settings.backgroundThemeHint"
                    backgroundImagePath.isNotBlank() -> "settings.backgroundCustomHint"
                    else -> "settings.backgroundDefaultHint"
                }))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(onClick = onChooseBackground) {
                        Text(readI18n("settings.chooseBackgroundImage"))
                    }
                    OutlinedButton(
                        onClick = onClearBackground,
                        enabled = backgroundImagePath.isNotBlank() || (backgroundImageEnabled && hasThemeBackground),
                    ) {
                        Text(readI18n("settings.clearBackgroundImage"))
                    }
                    if (hasThemeBackground && !backgroundImageEnabled) {
                        TextButton(onClick = onRestoreThemeBackground) {
                            Text(readI18n("settings.restoreThemeBackground"))
                        }
                    }
                }
                HorizontalDivider()
                ThemeSettingsSlider("settings.backgroundTransparency", settings.backgroundTransparency) {
                    settings.backgroundTransparency = it
                    UI.backgroundTransparency = it
                }
            }

            ThemeSettingsCard("settings.themeInterface") {
                ThemeSettingsSwitch("settings.enableAnimations", settings.enableAnimations) { settings.enableAnimations = it }
                HorizontalDivider()
                ThemeSettingsSwitch("settings.boldText", settings.boldText) { settings.boldText = it }
            }

            ThemeSettingsCard("settings.themeMusic") {
                ThemeSettingsHint(readI18n("settings.themeMusicHint"))
                ThemeSettingsSlider("settings.launcherMusicVolume", settings.launcherMusicVolume) {
                    settings.launcherMusicVolume = it
                    LauncherMusicController.sync()
                }
            }
        }
    }
}

@Composable
private fun ThemeSettingsCard(titleKey: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                readI18n(titleKey),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun ThemeSettingsHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ThemeSettingsSwitch(key: String, initialValue: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initialValue) }
    fun update(value: Boolean) {
        checked = value
        onChange(value)
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { update(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(readI18n(key), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = { update(it) })
    }
}

@Composable
private fun ThemeSettingsSlider(key: String, initialValue: Float, onChange: (Float) -> Unit) {
    var value by remember { mutableFloatStateOf(initialValue.coerceIn(0f, 1f)) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(readI18n(key), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = {
            value = it
            onChange(it)
        }, modifier = Modifier.fillMaxWidth())
    }
}
