/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.rwpp.config.ResourceBrowserOrientation

@Composable
actual fun ResourceBrowserLayout(
    orientation: ResourceBrowserOrientation?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val activity = LocalContext.current.findBrowserActivity()
    if (orientation != null && activity != null) {
        DisposableEffect(activity, orientation) {
            val previousOrientation = activity.requestedOrientation
            activity.requestedOrientation = when (orientation) {
                ResourceBrowserOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                ResourceBrowserOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            onDispose { activity.requestedOrientation = previousOrientation }
        }
    }
    Box(modifier) { content() }
}

private tailrec fun Context.findBrowserActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findBrowserActivity()
    else -> null
}
