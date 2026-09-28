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
import io.github.rwpp.logger

/**
 * 按 IME 可见性切换沉浸式隐藏系统栏。
 *
 * IME 可见期间不做任何系统栏操作（既不隐藏也不显示）：键盘弹出时系统会连带亮出
 * 导航栏，此时再触碰系统栏可能把正在弹起的输入法打断（Android 各 ROM 均有报告，
 * 华为平板上必现；详见 AGENTS.md 好友输入法踩坑记录）。等键盘收起后再恢复隐藏。
 */
fun applyImeImmersiveMode(activity: Activity, imeVisible: Boolean) {
    if (imeVisible) {
        logger.info("[IMEGUARD] ime visible, skip system-bar change")
        return
    }
    logger.info("[IMEGUARD] hide system bars")
    val window = activity.window
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(
        WindowInsetsCompat.Type.statusBars() or
            WindowInsetsCompat.Type.navigationBars()
    )
}
