/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.app

enum class UpdateStage { READY, DOWNLOADING, VERIFYING, EXTRACTING, INSTALLING, PERMISSION_REQUIRED, FAILED }

data class UpdateProgress(
    val stage: UpdateStage,
    val fraction: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val part: Int = 0,
    val parts: Int = 0,
    val error: String? = null,
) {
    val legacyProgress: Float get() = when (stage) {
        UpdateStage.FAILED -> AutoUpdater.PROGRESS_FAILED
        UpdateStage.PERMISSION_REQUIRED -> AutoUpdater.PROGRESS_NEED_INSTALL_PERMISSION
        else -> fraction
    }
}
