/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.coil

/**
 * Coil 加载账号头像的模型。
 *
 * @param userId 当前应用内的成员 ID
 * @param hasAvatar 服务端是否已有头像（无则不发起请求，由调用方画占位）
 * @param version 缓存版本：自己上传 / 删除头像后自增，驱动缓存失效
 */
data class AccountAvatar(
    val userId: Long,
    val hasAvatar: Boolean,
    val version: Int,
)
