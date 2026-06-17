/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.app

interface AutoUpdater {
  companion object {
    const val PROGRESS_FAILED = -1f
    const val PROGRESS_NEED_INSTALL_PERMISSION = -2f
  }

  /** 当前平台是否支持自动更新 */
  fun isSupported(): Boolean

  /**
   * 下载安装包并启动系统安装界面（桌面端成功后会退出当前进程）。
   * @param downloadUrl 安装包下载地址
   * @param onProgress 进度回调：0.0~1.0 为下载进度；[PROGRESS_NEED_INSTALL_PERMISSION] 需授予安装权限；[PROGRESS_FAILED] 表示失败
   */
  fun downloadAndInstall(downloadUrl: String, onProgress: (Float) -> Unit)

  /** 取消挂起的更新流程（如等待安装权限时的自动重试） */
  fun cancelPendingUpdate() {}
}
