/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.ResourceBrowserOrientation
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.widget.BorderCard
import io.github.rwpp.widget.LargeDropdownMenu

@Composable
internal fun ResourceBrowserOrientationDialog(
    onSelected: (ResourceBrowserOrientation) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val transition = remember { MutableTransitionState(false).apply { targetState = true } }
    var closing by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<ResourceBrowserOrientation?>(null) }
    val currentOnSelected by rememberUpdatedState(onSelected)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)

    fun close(orientation: ResourceBrowserOrientation? = null) {
        if (closing) return
        selection = orientation
        closing = true
        transition.targetState = false
    }

    // 选择后先完成退场，再保存并旋转屏幕，避免旋转打断弹窗动画。
    LaunchedEffect(closing, transition.isIdle, transition.currentState) {
        if (closing && transition.isIdle && !transition.currentState) {
            selection?.let(currentOnSelected) ?: currentOnDismiss()
        }
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visibleState = transition,
            enter = fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 0.9f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.95f),
        ) {
            BorderCard(
                modifier = Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth(),
                backgroundColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(readI18n("browser.orientationTitle"), style = MaterialTheme.typography.titleLarge)
                    Text(readI18n("browser.orientationHint"), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ResourceBrowserOrientation.entries.forEach { orientation ->
                            Card(
                                onClick = { close(orientation) },
                                enabled = !closing,
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                        val portrait = orientation == ResourceBrowserOrientation.Portrait
                                        Box(
                                            Modifier.size(
                                                if (portrait) 34.dp else 60.dp,
                                                if (portrait) 60.dp else 34.dp,
                                            ).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
                                        )
                                    }
                                    Text(orientationLabel(orientation), style = MaterialTheme.typography.titleMedium)
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
internal fun ResourceBrowserOrientationSetting(settings: Settings, configIO: ConfigIO) {
    val orientations = listOf(null) + ResourceBrowserOrientation.entries
    var orientation by remember(settings) { mutableStateOf(settings.resourceBrowserOrientation) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(readI18n("browser.defaultOrientation"), style = MaterialTheme.typography.bodyMedium)
        LargeDropdownMenu(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("browser-orientation-setting"),
            label = "",
            items = orientations.map { orientationLabel(it) },
            selectedIndex = orientations.indexOf(orientation),
            onItemSelected = { index, _ ->
                orientation = orientations[index]
                settings.resourceBrowserOrientation = orientation
                configIO.saveConfig(settings)
            },
        )
    }
}

private fun orientationLabel(orientation: ResourceBrowserOrientation?): String = readI18n(
    when (orientation) {
        ResourceBrowserOrientation.Landscape -> "browser.landscape"
        ResourceBrowserOrientation.Portrait -> "browser.portrait"
        null -> "browser.askOrientation"
    }
)
