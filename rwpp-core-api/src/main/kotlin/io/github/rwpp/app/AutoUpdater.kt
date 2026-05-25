/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.app

interface AutoUpdater {
    /** 当前平台是否支持自动更新 */
    fun isSupported(): Boolean

    /**
     * 下载安装包并启动更新，然后退出当前进程。
     * 此方法不会返回（成功后会调用 exitProcess）。
     * @param downloadUrl 安装包下载地址
     * @param onProgress 进度回调，0.0~1.0 为正常进度，-1.0 表示失败
     */
    fun downloadAndInstall(downloadUrl: String, onProgress: (Float) -> Unit)
}
