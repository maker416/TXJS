/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.map

interface Replay {
    val id: Int

    val name: String

    fun displayName(): String

    /** 文件最后修改时间，用于列表按新到旧排序；无法取得时为 0。 */
    val lastModifiedMillis: Long get() = 0L

    /** 回放文件字节数；无法取得时为 0。 */
    val fileSizeBytes: Long get() = 0L
}
