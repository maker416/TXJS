/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.rwpp.i18n.readI18n
import kotlin.math.roundToInt

/** 小尺寸独立浮层，只拦截球内输入；位置按比例保存，旋转/缩放后仍留在页面内。 */
@Composable
internal fun ResourceBrowserFloatingExit(onExit: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxX = with(density) { (maxWidth - 56.dp).toPx().coerceAtLeast(0f) }
        val maxY = with(density) { (maxHeight - 56.dp).toPx().coerceAtLeast(0f) }
        var horizontal by rememberSaveable { mutableStateOf(1f) }
        var vertical by rememberSaveable { mutableStateOf(0.35f) }
        var dragging by remember { mutableStateOf(false) }
        val animatedX by animateFloatAsState(horizontal * maxX)
        val x = (if (dragging) horizontal * maxX else animatedX).coerceIn(0f, maxX)
        fun dock() {
            horizontal = if (horizontal < 0.5f) 0f else 1f
            dragging = false
        }

        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(x.roundToInt(), (vertical * maxY).roundToInt()),
            properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Box(Modifier.padding(4.dp)) {
                Surface(
                    onClick = onExit,
                    modifier = Modifier.size(48.dp).testTag("browser-floating-exit")
                        .pointerInput(maxX, maxY) {
                            detectDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = ::dock,
                                onDragCancel = ::dock,
                            ) { change, amount ->
                                change.consume()
                                if (maxX > 0f) horizontal = (horizontal + amount.x / maxX).coerceIn(0f, 1f)
                                if (maxY > 0f) vertical = (vertical + amount.y / maxY).coerceIn(0f, 1f)
                            }
                        },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shadowElevation = 4.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, readI18n("common.close"))
                    }
                }
            }
        }
    }
}
