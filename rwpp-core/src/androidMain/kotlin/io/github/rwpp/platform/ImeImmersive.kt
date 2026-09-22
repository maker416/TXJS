/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.rwpp.appKoin
import io.github.rwpp.ui.UI

actual fun setImeImmersiveSuspended(suspended: Boolean) {
    UI.imeImmersiveSuspended = suspended
    val activity = runCatching { appKoin.get<Activity>() }.getOrNull() ?: return
    applyImeImmersiveMode(activity, suspended)
}

/** 按输入焦点切换系统栏。沉浸隐藏与输入法在鸿蒙上不能同时成立。 */
fun applyImeImmersiveMode(activity: Activity, suspended: Boolean) {
    val window = activity.window
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (suspended) {
        controller.show(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.hide(
            WindowInsetsCompat.Type.statusBars() or
                WindowInsetsCompat.Type.navigationBars()
        )
    }
}
