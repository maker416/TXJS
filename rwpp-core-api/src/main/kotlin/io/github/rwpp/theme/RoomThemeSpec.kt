/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import kotlinx.serialization.Serializable

/**
 * 等待战斗室的本地外观，以及显式启用的玩家卡片内容。页面布局由客户端按 PC / Android 决定。
 * 旧结构字段仅为兼容既有包保留，导入和渲染均须经 [sanitized]，不会参与页面布局。
 */
@Serializable
data class RoomThemeSpec(
    val background: RoomBackgroundSpec = RoomBackgroundSpec(),
    /** 兼容旧包；所有布局字段均忽略。 */
    val layout: RoomLayoutSpec = RoomLayoutSpec(),
    val compact: RoomLayoutOverride? = null,
    val wide: RoomLayoutOverride? = null,
    val panels: Map<String, RoomPanelSpec> = emptyMap(),
    val player: RoomPlayerSpec = RoomPlayerSpec(),
    val buttons: Map<String, RoomButtonSpec> = emptyMap(),
) {
    fun sanitized(): RoomThemeSpec = copy(
        background = background.sanitized(),
        layout = RoomLayoutSpec(),
        compact = null,
        wide = null,
        panels = panels.filterKeys { it in ROOM_PANEL_IDS }.mapValues { it.value.sanitized() },
        player = player.sanitized(),
        buttons = buttons.filterKeys { it in ROOM_BUTTON_IDS }.mapValues { it.value.sanitized() },
    )
}

@Serializable
data class RoomBackgroundSpec(
    val image: String = "",
    /** crop / fit / fill，分别对应等比裁切、完整显示、拉伸。 */
    val scale: String = "crop",
    val alpha: Int = 100,
    val overlayColor: String = "",
    val overlayAlpha: Int = 0,
) {
    fun sanitized(): RoomBackgroundSpec = copy(
        image = image.safeAssetPath(),
        scale = scale.takeIf { it in ROOM_IMAGE_SCALES } ?: "crop",
        alpha = alpha.coerceIn(0, 100),
        overlayColor = overlayColor.validColor(),
        overlayAlpha = overlayAlpha.coerceIn(0, 100),
    )
}

/** 客户端内部尺寸载体；旧包中的同名表只被读取，绝不作为布局来源。 */
@Serializable
data class RoomLayoutSpec(
    val columns: Int = 2,
    val minColumnWidth: Int = 280,
    val spacing: Int = 8,
    val padding: Int = 8,
    val widthPercent: Int = 100,
    val maxWidth: Int = 1600,
    val breakpoint: Int = 840,
    val playerListHeight: Int = 320,
    val chatHeight: Int = 220,
    val mapHeight: Int = 140,
) {
    fun sanitized(): RoomLayoutSpec = copy(
        columns = columns.coerceIn(1, 3),
        minColumnWidth = minColumnWidth.coerceIn(240, 480),
        spacing = spacing.coerceIn(0, 32),
        padding = padding.coerceIn(0, 32),
        widthPercent = widthPercent.coerceIn(50, 100),
        maxWidth = maxWidth.coerceIn(480, 2400),
        breakpoint = breakpoint.coerceIn(480, 1400),
        playerListHeight = playerListHeight.coerceIn(140, 640),
        chatHeight = chatHeight.coerceIn(120, 640),
        mapHeight = mapHeight.coerceIn(80, 400),
    )
}

/** 仅供读取旧包，运行时不应用。 */
@Serializable
data class RoomLayoutOverride(
    val columns: Int? = null,
    val minColumnWidth: Int? = null,
    val spacing: Int? = null,
    val padding: Int? = null,
    val widthPercent: Int? = null,
    val maxWidth: Int? = null,
    val breakpoint: Int? = null,
    val playerListHeight: Int? = null,
    val chatHeight: Int? = null,
    val mapHeight: Int? = null,
)

@Serializable
data class RoomPanelSpec(
    val order: Int = 0,
    val span: Int = 1,
    val backgroundImage: String = "",
    val backgroundColor: String = "",
    val contentColor: String = "",
    val borderColor: String = "",
    val borderWidth: Int = 1,
    val corner: Int = 8,
    val padding: Int = 8,
    /** 仅背景透明度，文字及交互区域保持完整。 */
    val opacity: Int = 100,
) {
    fun sanitized(): RoomPanelSpec = copy(
        order = 0,
        span = 1,
        backgroundImage = backgroundImage.safeAssetPath(),
        backgroundColor = backgroundColor.validColor(),
        contentColor = contentColor.validColor(),
        borderColor = borderColor.validColor(),
        borderWidth = borderWidth.coerceIn(0, 6),
        corner = corner.coerceIn(0, 48),
        padding = 8,
        opacity = opacity.coerceIn(0, 100),
    )
}

@Serializable
data class RoomPlayerSpec(
    val backgroundImage: String = "",
    val localImage: String = "",
    val hostImage: String = "",
    val aiImage: String = "",
    val readyImage: String = "",
    val notReadyImage: String = "",
    val emptyImage: String = "",
    /** 玩家出生位置的一基编号（1..1000）→包内图片。优先于状态图片。 */
    val slotImages: Map<String, String> = emptyMap(),
    val backgroundColor: String = "",
    val borderColor: String = "",
    val borderWidth: Int = 1,
    val corner: Int = 6,
    val opacity: Int = 100,
    val rowHeight: Int = 48,
    val spacing: Int = 4,
    val padding: Int = 4,
    val nameFont: String = "",
    val nameFontBold: String = "",
    val nameSize: Float = 14f,
    val nameWeight: Int = 500,
    val nameColor: String = "",
    val nameUseTeamColor: Boolean = true,
    val nameMaxLines: Int = 1,
    val nameLetterSpacing: Float = 0f,
    val columnOrder: List<String> = ROOM_PLAYER_COLUMN_IDS,
    val nameWidth: Float = 0.6f,
    val spawnWidth: Float = 0.1f,
    val teamWidth: Float = 0.1f,
    val pingWidth: Float = 0.2f,
    val showHeader: Boolean = true,
    val showPing: Boolean = true,
    val showSpawn: Boolean = true,
    val showTeam: Boolean = true,
    val profile: RoomProfileSpec = RoomProfileSpec(),
    /** 可选的头像格和静态信息行；默认关闭，不影响既有美术包。 */
    val card: RoomPlayerCardSpec = RoomPlayerCardSpec(),
) {
    fun sanitized(): RoomPlayerSpec = copy(
        backgroundImage = backgroundImage.safeAssetPath(),
        localImage = localImage.safeAssetPath(),
        hostImage = hostImage.safeAssetPath(),
        aiImage = aiImage.safeAssetPath(),
        readyImage = readyImage.safeAssetPath(),
        notReadyImage = notReadyImage.safeAssetPath(),
        emptyImage = emptyImage.safeAssetPath(),
        slotImages = slotImages.filterKeys { it.validSlotKey() }.mapValues { it.value.safeAssetPath() },
        backgroundColor = backgroundColor.validColor(),
        borderColor = borderColor.validColor(),
        borderWidth = borderWidth.coerceIn(0, 6),
        corner = corner.coerceIn(0, 48),
        opacity = opacity.coerceIn(0, 100),
        rowHeight = 48,
        spacing = 4,
        padding = 4,
        nameFont = nameFont.safeAssetPath(),
        nameFontBold = nameFontBold.safeAssetPath(),
        nameSize = 14f,
        nameWeight = nameWeight.coerceIn(100, 900),
        nameColor = nameColor.validColor(),
        nameMaxLines = 1,
        nameLetterSpacing = 0f,
        columnOrder = ROOM_PLAYER_COLUMN_IDS,
        nameWidth = 0.6f,
        spawnWidth = 0.1f,
        teamWidth = 0.1f,
        pingWidth = 0.2f,
        showHeader = true,
        showPing = true,
        showSpawn = true,
        showTeam = true,
        profile = profile.sanitized(),
        card = card.sanitized(),
    )
}

/**
 * 客户端提供的受控玩家卡片扩展，不改变原有玩家列和操作。
 * 附加信息行均为包内静态内容；客户端独立提供账号头像，不由美术包指定账号或改写游戏状态。
 */
@Serializable
data class RoomPlayerCardSpec(
    val enabled: Boolean = false,
    val avatar: RoomPlayerAvatarSpec = RoomPlayerAvatarSpec(),
    val content: RoomPlayerCardContentSpec = RoomPlayerCardContentSpec(),
    val local: RoomPlayerCardContentSpec? = null,
    val host: RoomPlayerCardContentSpec? = null,
    val ai: RoomPlayerCardContentSpec? = null,
    val spectator: RoomPlayerCardContentSpec? = null,
    /** 出生位置加一（1..1000），不是玩家列表中的排序行号。 */
    val slots: Map<String, RoomPlayerCardContentSpec> = emptyMap(),
) {
    fun sanitized(): RoomPlayerCardSpec = copy(
        avatar = avatar.sanitized(),
        content = content.sanitized(),
        local = local?.sanitized(),
        host = host?.sanitized(),
        ai = ai?.sanitized(),
        spectator = spectator?.sanitized(),
        slots = slots.filterKeys { it.validSlotKey() }.mapValues { it.value.sanitized() },
    )

    /**
     * [slot] 为一基出生位置。指定位置 > 自己 > 房主 > AI > 观战 > 通用。
     * 命中的配置整份替换通用内容，包括显式空配置；观战不使用位置配置。
     */
    fun contentFor(
        slot: Int,
        isLocal: Boolean,
        isHost: Boolean,
        isAI: Boolean,
        isSpectator: Boolean = false,
    ): RoomPlayerCardContentSpec = when {
        !isSpectator && slot in 1..1000 && slots.containsKey(slot.toString()) -> slots.getValue(slot.toString())
        isLocal && local != null -> local
        isHost && host != null -> host
        isAI && ai != null -> ai
        isSpectator && spectator != null -> spectator
        else -> content
    }
}

@Serializable
data class RoomPlayerAvatarSpec(
    val enabled: Boolean = true,
    val size: Int = 48,
    val corner: Int = 8,
    val borderWidth: Int = 1,
    val borderColor: String = "",
    val backgroundColor: String = "",
    val tint: String = "",
) {
    fun sanitized(): RoomPlayerAvatarSpec = copy(
        size = size.coerceIn(32, 64),
        corner = corner.coerceIn(0, 48),
        borderWidth = borderWidth.coerceIn(0, 6),
        borderColor = borderColor.validColor(),
        backgroundColor = backgroundColor.validColor(),
        tint = tint.validColor(),
    )
}

@Serializable
data class RoomPlayerCardContentSpec(
    /** 无可用账号头像时的默认图；不能替换已核验账号设置的头像。 */
    val avatarImage: String = "",
    val rows: List<RoomPlayerInfoRowSpec> = emptyList(),
) {
    fun sanitized(): RoomPlayerCardContentSpec = copy(
        avatarImage = avatarImage.safeAssetPath(),
        rows = rows.asSequence().map { it.sanitized() }.filter { it.cells.isNotEmpty() }.take(3).toList(),
    )
}

@Serializable
data class RoomPlayerInfoRowSpec(
    val cells: List<RoomPlayerInfoCellSpec> = emptyList(),
) {
    fun sanitized(): RoomPlayerInfoRowSpec = copy(
        cells = cells.asSequence().map { it.sanitized() }
            .filter { it.text.isNotBlank() || it.iconImage.isNotEmpty() }.take(4).toList(),
    )
}

@Serializable
data class RoomPlayerInfoCellSpec(
    val text: String = "",
    val iconImage: String = "",
    val contentColor: String = "",
    val backgroundColor: String = "",
    val borderColor: String = "",
    val borderWidth: Int = 0,
    val corner: Int = 6,
) {
    fun sanitized(): RoomPlayerInfoCellSpec = copy(
        text = text.filterNot { it.isISOControl() }.take(160),
        iconImage = iconImage.safeAssetPath(),
        contentColor = contentColor.validColor(),
        backgroundColor = backgroundColor.validColor(),
        borderColor = borderColor.validColor(),
        borderWidth = borderWidth.coerceIn(0, 6),
        corner = corner.coerceIn(0, 48),
    )
}

@Serializable
data class RoomProfileSpec(
    val backgroundImage: String = "",
    val litImage: String = "",
    val unavailableImage: String = "",
    val iconImage: String = "",
    val width: Int = 48,
    val height: Int = 48,
    val iconSize: Int = 20,
    val corner: Int = 8,
    val borderWidth: Int = 1,
    val borderColor: String = "",
    val backgroundColor: String = "",
    val tint: String = "",
    val showLabel: Boolean = false,
    val labelSize: Float = 10f,
) {
    fun sanitized(): RoomProfileSpec = copy(
        backgroundImage = backgroundImage.safeAssetPath(),
        litImage = litImage.safeAssetPath(),
        unavailableImage = unavailableImage.safeAssetPath(),
        iconImage = iconImage.safeAssetPath(),
        width = 48,
        height = 48,
        iconSize = 20,
        corner = corner.coerceIn(0, 48),
        borderWidth = borderWidth.coerceIn(0, 6),
        borderColor = borderColor.validColor(),
        backgroundColor = backgroundColor.validColor(),
        tint = tint.validColor(),
        showLabel = false,
        labelSize = 10f,
    )
}

@Serializable
data class RoomButtonSpec(
    val backgroundImage: String = "",
    val backgroundColor: String = "",
    val contentColor: String = "",
    val borderColor: String = "",
    val borderWidth: Int = 1,
    val corner: Int = 8,
    val minHeight: Int = 48,
    val textSize: Float = 13f,
    val order: Int = 0,
    val opacity: Int = 100,
) {
    fun sanitized(): RoomButtonSpec = copy(
        backgroundImage = backgroundImage.safeAssetPath(),
        backgroundColor = backgroundColor.validColor(),
        contentColor = contentColor.validColor(),
        borderColor = borderColor.validColor(),
        borderWidth = borderWidth.coerceIn(0, 6),
        corner = corner.coerceIn(0, 48),
        minHeight = 48,
        textSize = 13f,
        order = 0,
        opacity = opacity.coerceIn(0, 100),
    )
}

val ROOM_PANEL_IDS: Set<String> = setOf("players", "map", "chat", "details", "actions")
val ROOM_BUTTON_IDS: Set<String> = setOf(
    "start", "options", "selectMap", "addAI", "changeSite", "invite", "publish", "lockRoom", "memberInvites", "sendChat",
)
val ROOM_PLAYER_COLUMN_IDS: List<String> = listOf("name", "spawn", "team", "ping", "profile")
val ROOM_IMAGE_SCALES: Set<String> = setOf("crop", "fit", "fill")

/** 相对于 room 的旧结构字段路径。导入给出迁移提示，绝不把它们交给布局器。 */
internal fun isRoomStructureField(path: String): Boolean {
    val parts = path.split('.')
    return when (parts.firstOrNull()) {
        "layout", "compact", "wide" -> true
        "panels" -> parts.size == 3 && parts[2] in setOf("order", "span", "padding")
        "buttons" -> parts.size == 3 && parts[2] in setOf("order", "minHeight", "textSize")
        "player" -> if (parts.getOrNull(1) == "profile") {
            parts.size == 3 && parts[2] in setOf("width", "height", "iconSize", "showLabel", "labelSize")
        } else {
            parts.size == 2 && parts[1] in setOf(
                "rowHeight", "spacing", "padding", "nameSize", "nameMaxLines", "nameLetterSpacing",
                "columnOrder", "nameWidth", "spawnWidth", "teamWidth", "pingWidth",
                "showHeader", "showPing", "showSpawn", "showTeam",
            )
        }
        else -> false
    }
}

/** 比 zip entry 检查更严格：资源必须是可解析为普通包内文件的相对路径。 */
fun isSafeThemeAssetPath(path: String): Boolean =
    isSafeZipEntryName(path) && ':' !in path && path.none { it.isISOControl() } &&
        path.split('/').all { it.isNotEmpty() && it != "." && it.trim() == it }

/** 导入阶段拒绝危险路径，未知 id / 非法色值则提示并由 sanitized 忽略。 */
fun validateRoomTheme(room: RoomThemeSpec): ThemeValidation {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    fun path(key: String, value: String) {
        if (value.isNotEmpty() && !isSafeThemeAssetPath(value)) errors += "战斗室资源路径非法: $key = $value"
    }
    fun color(key: String, value: String) {
        if (value.isNotEmpty() && parseColorHex(value) == null) warnings += "战斗室色值非法已忽略: $key = $value"
    }
    path("room.background.image", room.background.image)
    color("room.background.overlayColor", room.background.overlayColor)
    if (room.background.scale !in ROOM_IMAGE_SCALES) {
        warnings += "未知战斗室背景缩放方式已回落 crop: ${room.background.scale}"
    }
    room.panels.forEach { (id, panel) ->
        if (id !in ROOM_PANEL_IDS) warnings += "未知战斗室面板已忽略: $id"
        path("room.panels.$id.backgroundImage", panel.backgroundImage)
        color("room.panels.$id.backgroundColor", panel.backgroundColor)
        color("room.panels.$id.contentColor", panel.contentColor)
        color("room.panels.$id.borderColor", panel.borderColor)
    }
    with(room.player) {
        mapOf(
            "backgroundImage" to backgroundImage, "localImage" to localImage, "hostImage" to hostImage,
            "aiImage" to aiImage, "readyImage" to readyImage, "notReadyImage" to notReadyImage,
            "emptyImage" to emptyImage, "nameFont" to nameFont, "nameFontBold" to nameFontBold,
        ).forEach { (key, value) -> path("room.player.$key", value) }
        slotImages.forEach { (key, value) ->
            if (!key.validSlotKey()) warnings += "未知战斗室玩家位置已忽略: $key（应为 1..1000 的整数）"
            path("room.player.slotImages.$key", value)
        }
        color("room.player.backgroundColor", backgroundColor)
        color("room.player.borderColor", borderColor)
        color("room.player.nameColor", nameColor)
        with(card.avatar) {
            color("room.player.card.avatar.borderColor", borderColor)
            color("room.player.card.avatar.backgroundColor", backgroundColor)
            color("room.player.card.avatar.tint", tint)
        }
        fun cardContent(key: String, content: RoomPlayerCardContentSpec) {
            path("$key.avatarImage", content.avatarImage)
            content.rows.forEachIndexed { rowIndex, row ->
                row.cells.forEachIndexed { cellIndex, cell ->
                    val cellKey = "$key.rows[$rowIndex].cells[$cellIndex]"
                    path("$cellKey.iconImage", cell.iconImage)
                    color("$cellKey.contentColor", cell.contentColor)
                    color("$cellKey.backgroundColor", cell.backgroundColor)
                    color("$cellKey.borderColor", cell.borderColor)
                }
            }
        }
        cardContent("room.player.card.content", card.content)
        mapOf("local" to card.local, "host" to card.host, "ai" to card.ai, "spectator" to card.spectator)
            .forEach { (id, content) -> if (content != null) cardContent("room.player.card.$id", content) }
        card.slots.forEach { (key, content) ->
            if (!key.validSlotKey()) warnings += "未知战斗室玩家卡片位置已忽略: $key（应为 1..1000 的整数）"
            cardContent("room.player.card.slots.$key", content)
        }
        with(profile) {
            mapOf("backgroundImage" to backgroundImage, "litImage" to litImage,
                "unavailableImage" to unavailableImage, "iconImage" to iconImage)
                .forEach { (key, value) -> path("room.player.profile.$key", value) }
            color("room.player.profile.borderColor", borderColor)
            color("room.player.profile.backgroundColor", backgroundColor)
            color("room.player.profile.tint", tint)
        }
    }
    room.buttons.forEach { (id, button) ->
        if (id !in ROOM_BUTTON_IDS) warnings += "未知战斗室按钮已忽略: $id"
        path("room.buttons.$id.backgroundImage", button.backgroundImage)
        color("room.buttons.$id.backgroundColor", button.backgroundColor)
        color("room.buttons.$id.contentColor", button.contentColor)
        color("room.buttons.$id.borderColor", button.borderColor)
    }
    return ThemeValidation(errors, warnings)
}

private fun String.validSlotKey(): Boolean = toIntOrNull()?.let { it in 1..1000 && it.toString() == this } == true
private fun String.safeAssetPath(): String = takeIf { it.isEmpty() || isSafeThemeAssetPath(it) }.orEmpty()
private fun String.validColor(): String = takeIf { parseColorHex(it) != null }.orEmpty()
