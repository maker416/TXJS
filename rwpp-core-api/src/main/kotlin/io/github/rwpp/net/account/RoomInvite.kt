/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.account

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 房间邀请消息的魔数前缀：私信 body 以它开头即视为房间邀请卡片消息。 */
const val ROOM_INVITE_PREFIX = "[RWJSINV1]"

/** 邀请有效期：30 分钟（毫秒）。 */
const val ROOM_INVITE_TTL_MS: Long = 30L * 60L * 1000L

/**
 * 房间邀请负载。通过好友私信通道发送，body = [ROOM_INVITE_PREFIX] + JSON。
 *
 * 旧版本客户端收到时仅显示一行以前缀开头的文本，不解析、不崩溃。
 *
 * @property address 连接地址（直连房唯一可靠的加入凭据，必填）
 * @property code 房间短码（未发布到列表的房/直连房为 null，仅用于展示）
 * @property inviter 邀请人显示名（邀请者不一定是房主）
 * @property map 地图显示名
 * @property players 人数快照，形如 "3/10"（仅展示，加入时不信任）
 * @property mods 启用的模组数量
 * @property version 邀请方客户端版本号（如 "v1.6.4"）
 * @property invitedAt 邀请发出的 epoch 毫秒，用于 TTL 过期判定
 */
@Serializable
data class RoomInvite(
    val address: String,
    val code: String? = null,
    val inviter: String = "",
    val map: String = "",
    val players: String = "",
    val mods: Int = 0,
    val version: String = "",
    val invitedAt: Long = 0,
)

/** 房间邀请编解码器。 */
object RoomInviteCodec {

    private val json = Json { ignoreUnknownKeys = true }

    /** 编码为私信 body 文本。 */
    fun encode(invite: RoomInvite): String =
        ROOM_INVITE_PREFIX + json.encodeToString(RoomInvite.serializer(), invite)

    /**
     * 尝试把私信 body 解码为房间邀请；非邀请消息、JSON 非法、地址为空均返回 null。
     */
    fun decode(body: String): RoomInvite? {
        if (!body.startsWith(ROOM_INVITE_PREFIX)) return null
        return runCatching {
            json.decodeFromString(RoomInvite.serializer(), body.removePrefix(ROOM_INVITE_PREFIX))
        }.getOrNull()?.takeIf { it.address.isNotBlank() }
    }

    /** 邀请是否已过期（[invitedAt] 缺失或超出 TTL 视为过期）。 */
    fun isExpired(invite: RoomInvite, nowMs: Long): Boolean =
        invite.invitedAt <= 0 || nowMs - invite.invitedAt > ROOM_INVITE_TTL_MS
}

private val roomCodeRegex = Regex("""^[QR]\d+$""", RegexOption.IGNORE_CASE)

/**
 * 校验一个字符串是否可作为「加入地址」直连房间。
 *
 * 接受：房间短码（`Q34091`/`R12345`）、`host:port`、IP / 域名（含 `.` 或 `:`）。
 * 拒绝：快速建房指令（`Qnews`、`Qmods`、`QC6666`、`QnewsP20U3000` 等）——
 * 它们会被输入框写入 `lastNetworkIP`，但对受邀方不是可加入地址。
 */
fun isPlausibleJoinAddress(address: String): Boolean {
    val a = address.trim()
    if (a.isEmpty()) return false
    if (roomCodeRegex.matches(a)) return true
    return a.contains(':') || a.contains('.')
}
