/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import kotlinx.coroutines.CancellationException

internal enum class HostModSyncFailureAction { Retry, ContinueWithoutSharing }

/** 失败必须等待玩家决定；取消继续向上传播，绝不隐式降级开房。 */
internal suspend fun awaitHostModSyncService(
    prepare: suspend () -> Unit,
    onFailure: suspend (Exception) -> HostModSyncFailureAction,
): Boolean {
    while (true) {
        try {
            prepare()
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (onFailure(error) == HostModSyncFailureAction.ContinueWithoutSharing) return false
        }
    }
}
