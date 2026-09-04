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

    /**
     * 彻底重启应用进程（用于"重启式特殊加载"：开关已落盘，冷启动完成单位重建）。
     *
     * 实现方需先完成与 [exit] 等价的配置收尾，再以全新进程拉起入口。
     * 返回 false 表示该平台不支持（桌面端），调用方应回退原有流程；
     * 返回 true 时进程即将结束，调用方不应再假设任何后续代码会执行。
     */
    fun restart(): Boolean

    /** Emits `true` once [exit] has been called; drives the full-screen "exiting" overlay. */
    val exitOverlayVisible: StateFlow<Boolean>
}