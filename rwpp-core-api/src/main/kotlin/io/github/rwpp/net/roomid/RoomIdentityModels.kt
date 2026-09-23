/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.roomid

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 房间身份公示（roomid）协议 DTO，与 Go 服务端 relaymod `/api/v1/roomid/` 端点组对齐（snake_case 字段名）。
 *
 * 机制：登录客户端在房内周期性公示 `{room_keys, player_name}`（带 `X-App-Key` + `Authorization: Bearer`），
 * 服务端向 UAS 转发 token 核验身份后记录「房间 key + 玩家名 → 账号」映射（TTL 90s）；
 * 同房间其他客户端按 `room_key + player_name` 查询，命中得账号三元组。
 */

/** 公示请求体。对应 `PUT /api/v1/roomid/publish`。[roomKeys] 1..8 个，[playerName] 1..64 字符。 */
@Serializable
data class RoomIdPublishRequest(
    @SerialName("room_keys") val roomKeys: List<String>,
    @SerialName("player_name") val playerName: String,
)

/** 查询结果中的单个账号条目：`user_id` / `username` / `nickname` 三元组。 */
@Serializable
data class RoomIdEntry(
    @SerialName("user_id") val userId: Long,
    val username: String,
    val nickname: String,
)

/**
 * 查询响应体。对应 `GET /api/v1/roomid/lookup?room_key=A&room_key=B&player_name=X`。
 * 无匹配 / 无权限时服务端同样返回 200 + 空数组。
 */
@Serializable
data class RoomIdLookupResponse(
    val users: List<RoomIdEntry> = emptyList(),
)

/** `PUT`/`DELETE /api/v1/roomid/publish` 的成功响应体：`{"ok": true}`。 */
@Serializable
data class RoomIdOkResponse(
    @SerialName("ok") val ok: Boolean = false,
)
