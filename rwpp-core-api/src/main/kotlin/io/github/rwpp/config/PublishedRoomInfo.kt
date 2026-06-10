/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import kotlinx.serialization.Serializable
import org.koin.core.annotation.Single

/**
 * 记录当前房间发布到列表服务器后分配的信息，用于续期和查询剩余时间。
 * 通过 [ConfigIO] 持久化于 `PublishedRoomInfo.toml`。
 */
@Single
@Serializable
data class PublishedRoomInfo(
    /** 房间 RoomId（Q/R 编号），用于关联当前房间 */
    var roomId: String = "",
    /** 列表服务器分配的服务端ID */
    var serverId: String = "",
    /** 上传时返回的 secret_key，用于续期和删除 */
    var secretKey: String = "",
    /** 房间类型 (roomtype) */
    var roomType: String = "",
    /** 使用的列表服务基础 URL */
    var baseUrl: String = DEFAULT_ROOM_LIST_API_URLS,
) : Config {
    /** 是否有有效的已发布信息 */
    val isPublished: Boolean get() = serverId.isNotBlank() && secretKey.isNotBlank()
}
