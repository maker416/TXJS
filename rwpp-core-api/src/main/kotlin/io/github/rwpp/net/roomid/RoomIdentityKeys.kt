/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.roomid

import io.github.rwpp.net.RoomDescription
import io.github.rwpp.net.sync.CODE_PREFIX
import io.github.rwpp.net.sync.SID_PREFIX
import io.github.rwpp.net.sync.forAddress
import io.github.rwpp.net.sync.forRoomDescription

/** 身份公示 key 前缀：直连地址归一化后的 `addr:host:port`，如 `addr:1.2.3.4:5123`。 */
const val ADDR_PREFIX = "addr:"

/** 直连地址未带端口时补的默认游戏端口。 */
const val DEFAULT_GAME_PORT = 5123

/** 每名玩家最多公示的 key 个数（与服务端上限对齐），超出按 sid > code > addr 优先级截断。 */
const val MAX_IDENTITY_KEYS = 8

/** 归一化后的小写 host（IPv4 或域名）；快速建房指令串等不合法 host 不匹配。 */
private val ADDR_HOST_REGEX = Regex("^[a-z0-9][a-z0-9.\\-]*$")

/**
 * 由原始加入地址推导身份公示 key：
 *
 * - trim 后 host 部分匹配 `^[QR]\d+$`（大小写不敏感）→ `["code:XXX"]`（归一大写，复用
 *   [forAddress]，与其行为保持一致）；
 * - 否则按直连地址归一化为 `addr:host:port`：去空白、host 小写、无 `:端口`（或端口非法）时补
 *   默认端口 [DEFAULT_GAME_PORT]；
 * - host 必须形似 IPv4 / 域名（含 `.`），`Qnews`/`QC6666` 这类快速建房指令串不是可加入地址，
 *   返回空列表。
 */
fun identityKeysForAddress(address: String): List<String> {
    val trimmed = address.trim()
    if (trimmed.isEmpty()) return emptyList()
    // 短码：与模组同步共用同一份归一逻辑（host 部分匹配短码时产出 code: key）
    val codeKeys = forAddress(trimmed)
    if (codeKeys.isNotEmpty()) return codeKeys
    val host = trimmed.substringBefore(':').trim().lowercase()
    if (!ADDR_HOST_REGEX.matches(host) || '.' !in host) return emptyList()
    val port = trimmed.substringAfter(':', "").trim()
        .toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_GAME_PORT
    return listOf("$ADDR_PREFIX$host:$port")
}

/**
 * 由列表房间信息推导身份公示 key：直接委托 [forRoomDescription]（得 sid + code，按优先级排序）。
 * 直连 IP 房不产出 `code:` key；无任何匹配时返回空列表。
 */
fun identityKeysForRoomDescription(desc: RoomDescription): List<String> =
    forRoomDescription(desc)

/**
 * 合并候选 key 并按优先级（sid > code > addr）截断到 [MAX_IDENTITY_KEYS] 个。
 * 输入顺序无关：先按前缀去重再稳定排序。
 */
fun prioritizeIdentityKeys(keys: List<String>): List<String> {
    fun rank(key: String): Int = when {
        key.startsWith(SID_PREFIX) -> 0
        key.startsWith(CODE_PREFIX) -> 1
        else -> 2
    }
    return keys.distinct().sortedBy { rank(it) }.take(MAX_IDENTITY_KEYS)
}
