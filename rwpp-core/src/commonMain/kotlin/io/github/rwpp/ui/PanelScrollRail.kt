/*
 * Copyright 2023-2025 RWPP contributors
 */

package io.github.rwpp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rwpp.widget.v2.LazyListScrollbarRail

/** 右侧固定栏：关闭 + 滚动条（不随中间列表滚动）。 */
internal val PanelScrollRailWidth = 44.dp

/** 仅滚动条轨道（多人列表：关闭在顶栏，轨道与列表同高）。 */
@Composable
internal fun PanelListScrollbarRail(
    listState: LazyListState,
    topInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(PanelScrollRailWidth)
            .fillMaxHeight(),
    ) {
        Spacer(Modifier.height(topInset))
        LazyListScrollbarRail(
            listState = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            alwaysShowScrollBar = true,
        )
    }
}

@Composable
internal fun PanelScrollRail(
    onClose: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(PanelScrollRailWidth)
            .fillMaxHeight()
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PanelCloseIconButton(onClose = onClose)
        Spacer(Modifier.height(8.dp))
        LazyListScrollbarRail(
            listState = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            alwaysShowScrollBar = true,
        )
    }
}
