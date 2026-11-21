/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget.v2

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import io.github.rwpp.widget.uiAnimationsEnabled
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bounceClick(
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val animationsEnabled = uiAnimationsEnabled()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interactionSource, animationsEnabled) {
        if (!animationsEnabled) {
            if (scale.value != 1f) scale.snapTo(1f)
            return@LaunchedEffect
        }
        val activePresses = mutableSetOf<PressInteraction.Press>()
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> activePresses.add(interaction)
                is PressInteraction.Release -> activePresses.remove(interaction.press)
                is PressInteraction.Cancel -> activePresses.remove(interaction.press)
            }
            val target = if (activePresses.isNotEmpty()) 0.97f else 1f
            if (scale.targetValue != target) {
                launch {
                    scale.animateTo(target, tween(120))
                }
            }
        }
    }
    this
        .let {
            if (animationsEnabled) {
                it.graphicsLayer {
                    val s = scale.value
                    scaleX = s
                    scaleY = s
                }
            } else {
                it
            }
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongClick,
        )
}
