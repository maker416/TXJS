/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

/** 快速建房可选后缀：P 人数、U 单位上限、C 资金、Z 倍速。 */
data class QuickHostLimits(
    val maxPlayer: Int? = null,
    val unitLimit: Int? = null,
    val credits: Int? = null,
    val speedMultiplier: Int? = null,
) {
    fun appendSuffix(): String = buildString {
        maxPlayer?.let { append("P$it") }
        unitLimit?.let { append("U$it") }
        credits?.let { append("C$it") }
        speedMultiplier?.let { append("Z$it") }
    }
}

/** 创建房间弹窗 → 引擎 quick-host 连接串的全部输入。 */
data class QuickHostRoomOptions(
    val prefix: HostCommandPrefix = HostCommandPrefix.Q,
    val enableMods: Boolean = false,
    val roomId: String? = null,
    val limits: QuickHostLimits = QuickHostLimits(),
)

/**
 * 快速建房串主体类型。Q/R/T 仅 [HostCommandPrefix.commandPrefix] 不同，规则一致。
 * 原版均为 `news`（与中转 Reply 里的 `new` 不是同一层）。
 */
enum class QuickHostBaseKind {
    VANILLA,
    MODDED,
    CUSTOM_VANILLA,
    CUSTOM_MODDED,
    ;

    fun wireBase(commandPrefix: String, roomId: String?): String = when (this) {
        VANILLA -> "${commandPrefix}news"
        MODDED -> "${commandPrefix}mods"
        CUSTOM_VANILLA -> "${commandPrefix}C$roomId"
        CUSTOM_MODDED -> "${commandPrefix}CM$roomId"
    }

    companion object {
        fun from(enableMods: Boolean, roomId: String?): QuickHostBaseKind = when {
            roomId != null && enableMods -> CUSTOM_MODDED
            roomId != null -> CUSTOM_VANILLA
            enableMods -> MODDED
            else -> VANILLA
        }
    }
}

fun HostCommandPrefix.commandPrefix(): String = when (this) {
    HostCommandPrefix.Q -> "Q"
    HostCommandPrefix.R -> "R"
    HostCommandPrefix.T -> T_ROOM_ADDRESS_PREFIX
}

fun buildQuickHostCommand(options: QuickHostRoomOptions): String {
    val kind = QuickHostBaseKind.from(options.enableMods, options.roomId)
    val base = kind.wireBase(options.prefix.commandPrefix(), options.roomId)
    return base + options.limits.appendSuffix()
}

// C 既是自定义房号前缀，也是资金后缀，必须先匹配完整主体，不能直接从末尾剥离 C 数字。
private val quickHostBodyRegex = Regex(
    """(?:news|mods|CM\d+|C\d+)(?:P\d+|U\d+|C\d+|Z\d+)*""",
    RegexOption.IGNORE_CASE,
)

/**
 * 从引擎 quick-host 连接串解析 [HostCommandPrefix]；无法识别或是加入短码时返回 null。
 */
fun parseQuickHostCommandPrefix(address: String): HostCommandPrefix? {
    val trimmed = address.trim()
    if (trimmed.startsWith(T_ROOM_ADDRESS_PREFIX, ignoreCase = true)) {
        val path = trimmed.substring(T_ROOM_ADDRESS_PREFIX.length)
        return if (quickHostBodyRegex.matches(path)) HostCommandPrefix.T else null
    }
    if (isRoomJoinShortCode(trimmed)) return null
    return when (trimmed.firstOrNull()?.uppercaseChar()) {
        'Q' -> HostCommandPrefix.Q.takeIf { quickHostBodyRegex.matches(trimmed.drop(1)) }
        'R' -> HostCommandPrefix.R.takeIf { quickHostBodyRegex.matches(trimmed.drop(1)) }
        else -> null
    }
}
