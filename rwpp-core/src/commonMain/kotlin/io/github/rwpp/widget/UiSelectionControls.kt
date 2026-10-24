/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * Material3 的滑块节点不会因 MotionScheme 改变而取消已经开始的 animateTo。
 * 仅重建视觉子树以停止旧动画；外层 toggleable/交互源保持稳定，避免丢焦点和中断手势。
 */
@Composable
fun UiSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
) {
    val animationsEnabled = uiAnimationsEnabled()
    val source = remember { MutableInteractionSource() }
    val toggleable = if (onCheckedChange == null) Modifier else Modifier
        .minimumInteractiveComponentSize()
        .toggleable(checked, source, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    Box(modifier.then(toggleable), contentAlignment = Alignment.Center) {
        key(animationsEnabled, if (animationsEnabled) null else checked) {
            Switch(checked, onCheckedChange = null, enabled = enabled, colors = colors, interactionSource = source)
        }
    }
}

/** 勾选框颜色动画同样可能保留旧 spec；保持外层输入，仅重建视觉状态。 */
@Composable
fun UiCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
) {
    val animationsEnabled = uiAnimationsEnabled()
    val source = remember { MutableInteractionSource() }
    val toggleable = if (onCheckedChange == null) Modifier else Modifier
        .minimumInteractiveComponentSize()
        .toggleable(checked, source, indication = null, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
    Box(modifier.then(toggleable), contentAlignment = Alignment.Center) {
        key(animationsEnabled, if (animationsEnabled) null else checked) {
            Checkbox(checked, onCheckedChange = null, enabled = enabled, colors = colors, interactionSource = source)
        }
    }
}
