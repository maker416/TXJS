/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.net.appearance

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** 原版 1.15 包141。显示标签可被昵称模仿，必须同时验证原始 source connection 为0。 */
object IdentityChatCodec {
    const val READY_NOTICE = "已连接，支持玩家专属外观。"

    fun serverControlText(payload: ByteArray): String? = runCatching {
        if (payload.size !in 1..4096) return null
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val message = input.readUTF()
            if (input.readUnsignedByte() != 3 || !input.readBoolean()) return null
            val sender = input.readUTF()
            val sourceConnection = input.readInt()
            input.readInt() // 原版颜色字段；颜色不构成身份。
            if (input.available() != 0 || sender != "RWJS-ID" || sourceConnection != 0) return null
            message
        }
    }.getOrNull()
}
