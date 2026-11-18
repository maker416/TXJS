/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.rwpp.event.broadcastIn
import io.github.rwpp.event.events.CloseUIPanelEvent
import io.github.rwpp.i18n.readI18n

/** Art and complete online-game packages have independent management and storage. */
@Composable
fun ExtensionView(onExit: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        onDispose { CloseUIPanelEvent("extension").broadcastIn() }
    }
    Column(Modifier.fillMaxSize().testTag("extension-library")) {
        TabRow(selectedTabIndex = selected) {
            Tab(selected == 0, { selected = 0 }, text = { Text(readI18n("expansion.artTab")) }, modifier = Modifier.testTag("extension-art-tab"))
            Tab(selected == 1, { selected = 1 }, text = { Text(readI18n("expansion.packagesTab")) }, modifier = Modifier.testTag("extension-packages-tab"))
        }
        Box(Modifier.weight(1f)) {
            if (selected == 0) ThemesView(onExit = onExit, title = readI18n("expansion.artTab"))
            else ExpansionPackagesView(onExit)
        }
    }
}
