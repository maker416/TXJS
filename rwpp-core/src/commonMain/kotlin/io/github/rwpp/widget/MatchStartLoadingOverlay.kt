/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rwpp.i18n.readI18n

/** PC 遮罩随启动器根容器同步隐藏，不能让独立 Dialog 留在游戏 Canvas 上方。 */
@Composable
fun MatchStartLoadingOverlay(visible: Boolean, isDesktop: Boolean) {
    AnimatedAlertDialog(
        visible = visible,
        onDismissRequest = {},
        enableDismiss = false,
        inlineDesktop = isDesktop,
    ) {
        BorderCard(Modifier.widthIn(max = 420.dp).padding(20.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UiCircularProgressIndicator()
                Text(readI18n("multiplayer.room.startLoading"))
            }
        }
    }
}
