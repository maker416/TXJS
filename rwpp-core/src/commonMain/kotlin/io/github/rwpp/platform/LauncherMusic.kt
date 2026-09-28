/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import java.io.File

/**
 * 启动器背景音乐播放器（主题美术包 v3）。
 *
 * 两端格式支持不同：Android 走 `MediaPlayer`（mp3/ogg/m4a/wav 等），
 * 桌面端零新增依赖走 `javax.sound.sampled`（wav）+ 仓库内置 jorbis（ogg）。
 * 所有方法都是幂等、静默失败的（记日志），绝不因音频设备缺失/文件损坏崩溃。
 */
expect object LauncherMusic {
    /** 当前平台支持的扩展名（不含点），按偏好排序（越前越优先）。 */
    val supportedMusicExtensions: List<String>

    /** 循环播放 [file]；同一文件重复调用视为续播。 */
    fun play(file: File, volume: Float)

    /** 停止并释放资源。 */
    fun stop()

    /** 暂停（App 退后台等场景），保留当前进度与文件。 */
    fun pause()

    /** 从暂停恢复；非暂停状态调用为空操作。 */
    fun resume()

    fun setVolume(volume: Float)
}
