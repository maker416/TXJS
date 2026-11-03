/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 磁盘标记只能证明文件来源；另记本进程已加载的来源，并在重载成功后才提交。
 * 停用会先删除整个覆盖目录（包括标记），因此失败重试不能只比较磁盘标记与 null。
 */
internal class GameArtOverlaySynchronizer(
    private val readMarker: () -> String?,
    private val rebuild: () -> Unit,
    private val commitMarker: (String?) -> Unit,
    private val reload: suspend () -> Unit,
    private val beforeSync: () -> Unit = {},
) {
    private val mutex = Mutex()
    private var loadedKnown = false
    private var loadedId: String? = null
    private var dirty = false

    /** 仅在引擎初始化前、运行期同步开始前调用。 */
    fun atStartup(expected: String?) {
        if (readMarker() != expected) {
            rebuild()
            commitMarker(expected)
        }
        loadedId = expected
        loadedKnown = true
    }

    suspend fun sync(expected: String?) = mutex.withLock {
        if (!dirty && loadedKnown && loadedId == expected && readMarker() == expected) return@withLock
        beforeSync()
        // 即使重建、重载或标记提交失败，下一次也必须重做，而不是误认为已完成。
        dirty = true
        rebuild()
        reload()
        commitMarker(expected)
        loadedId = expected
        loadedKnown = true
        dirty = false
    }
}
