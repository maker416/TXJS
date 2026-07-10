/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp

import kotlinx.coroutines.flow.StateFlow
import org.koin.core.component.KoinComponent

interface AppContext : KoinComponent {
    fun init()

    fun onExit(action: () -> Unit)

    fun isAndroid(): Boolean

    fun isDesktop(): Boolean

    fun externalStoragePath(path: String): String

    /**
     * 应用私有存储路径（Android 为 getExternalFilesDir 下的私密目录，非 root 设备上
     * 文件管理器无法访问，卸载 App 时随应用一起删除；桌面端默认回退到 [externalStoragePath]）。
     *
     * 用于存放需要保护的文件，例如网络同步过来的房主模组——只允许玩家在游戏内使用，
     * 不允许玩家通过文件管理器直接取出。
     */
    fun internalStoragePath(path: String): String

    /** 注入产物目录（Android 为应用私有目录，桌面端为运行目录下的 generated_lib/） */
    fun generatedLibPath(): String

    fun exit()

    /** Emits `true` once [exit] has been called; drives the full-screen "exiting" overlay. */
    val exitOverlayVisible: StateFlow<Boolean>
}