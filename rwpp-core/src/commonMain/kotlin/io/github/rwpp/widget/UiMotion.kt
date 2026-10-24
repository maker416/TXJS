/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.rwpp.widget

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.unit.Dp
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings

// Settings 属于无 Compose 依赖的序列化 API 层；界面写入时显式通知所有观察者。
private var animationSettingsRevision by mutableIntStateOf(0)
private val LocalUiAnimationsEnabled = compositionLocalOf<Boolean?> { null }

fun Settings.setUiAnimationsEnabled(enabled: Boolean) {
    if (enableAnimations == enabled) return
    enableAnimations = enabled
    animationSettingsRevision++
}

@Composable
fun uiAnimationsEnabled(): Boolean {
    animationSettingsRevision
    // 主界面及弹窗继承主题提供的值；独立组件/启动预览回读配置，未初始化时默认静态。
    return LocalUiAnimationsEnabled.current
        ?: runCatching { appKoin.get<Settings>().enableAnimations }.getOrDefault(false)
}

// Material3 1.9.0-beta03 的 MotionScheme 尚未公开，版本适配集中于本文件。
// 升级 Compose/Material3/Kotlin 时须重新编译两端并运行 UiMotionTest，优先迁移到公开 API。
private object StaticMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

private object StaticIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = javaClass.hashCode()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UiMotionTheme(
    colorScheme: ColorScheme,
    typography: Typography,
    content: @Composable () -> Unit,
) {
    val enabled = uiAnimationsEnabled()
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        motionScheme = if (enabled) MotionScheme.standard() else StaticMotionScheme,
    ) {
        CompositionLocalProvider(
            LocalUiAnimationsEnabled provides enabled,
            LocalRippleConfiguration provides if (enabled) LocalRippleConfiguration.current else null,
            LocalIndication provides if (enabled) LocalIndication.current else StaticIndication,
            content = content,
        )
    }
}

@Composable
fun animateUiFloatAsState(
    targetValue: Float,
    animationSpec: AnimationSpec<Float> = spring(),
    label: String = "FloatAnimation",
): State<Float> = if (uiAnimationsEnabled()) {
    animateFloatAsState(targetValue, animationSpec, label = label)
} else rememberUpdatedState(targetValue)

@Composable
fun animateUiDpAsState(
    targetValue: Dp,
    animationSpec: AnimationSpec<Dp> = spring(),
    label: String = "DpAnimation",
): State<Dp> = if (uiAnimationsEnabled()) {
    animateDpAsState(targetValue, animationSpec, label = label)
} else rememberUpdatedState(targetValue)

@Composable
fun animateUiColorAsState(
    targetValue: Color,
    animationSpec: AnimationSpec<Color> = spring(),
    label: String = "ColorAnimation",
): State<Color> = if (uiAnimationsEnabled()) {
    animateColorAsState(targetValue, animationSpec, label = label)
} else rememberUpdatedState(targetValue)
