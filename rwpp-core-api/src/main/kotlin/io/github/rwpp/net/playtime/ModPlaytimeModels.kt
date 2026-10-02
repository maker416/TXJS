/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.playtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlayedMod(val name: String, val sha256: String)

/** 累计本次真实活跃秒数；相同 session 重传不会重复累计。 */
@Serializable
data class ModPlaytimeReport(
    @SerialName("session_id") val sessionId: String,
    @SerialName("elapsed_seconds") val elapsedSeconds: Long,
    val mods: List<PlayedMod>,
    val ended: Boolean = false,
)

@Serializable
data class ModPlaytimeReceipt(
    @SerialName("session_id") val sessionId: String,
    @SerialName("elapsed_seconds") val elapsedSeconds: Long,
    @SerialName("added_seconds") val addedSeconds: Long,
    val ended: Boolean,
)

/** 本地重试记录不包含 token；只有原账号的原 token 可重发。 */
@Serializable
data class PendingModPlaytimeReport(
    val userId: Long,
    val tokenFingerprint: String,
    val apiUrl: String,
    val report: ModPlaytimeReport,
)
