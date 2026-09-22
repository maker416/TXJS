/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

/**
 * 输入框获焦时暂时退出全屏沉浸，避免鸿蒙/华为把刚弹出的输入法立刻收掉。
 * 桌面端无系统栏，实际实现为空。
 */
expect fun setImeImmersiveSuspended(suspended: Boolean)
