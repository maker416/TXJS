/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.Toml

/**
 * 主题美术包（`.rwtheme`）中 `theme.toml` 的解析模型。
 *
 * 本模块（rwpp-core-api）不依赖 Compose，配色以 `#RRGGBB` / `#AARRGGBB` 字符串承载，
 * 由 rwpp-core 的 `ArtThemeController` 转换为 `ColorScheme`。
 */
@Serializable
data class ArtThemeSpec(
    val theme: ThemeMeta,
    val colors: Map<String, String> = emptyMap(),
    val menu: MenuSpec = MenuSpec(),
    val fonts: FontsSpec = FontsSpec(),
)

@Serializable
data class ThemeMeta(
    val id: String,
    val name: String,
    val author: String = "",
    val version: String = "",
    val description: String = "",
)

@Serializable
data class MenuSpec(
    /** 是否显示主菜单标题图右下角的「极速版」角标。 */
    val showTitleBadge: Boolean = true,
    /** 主菜单按钮区布局参数（v2）。主题缺省值见 [MenuLayoutSpec]。 */
    val layout: MenuLayoutSpec = MenuLayoutSpec(),
    /** 按语义 id 的按钮槽位覆盖（v2），键必须是 [MENU_BUTTON_IDS] 之一。 */
    val buttons: Map<String, MenuButtonSpec> = emptyMap(),
)

/**
 * 主菜单按钮区布局。所有数值在使用前经 [sanitized] 钳制到安全范围，
 * 不开放绝对坐标（响应式布局下写死像素必然在小屏/大屏破碎）。
 */
@Serializable
data class MenuLayoutSpec(
    /** grid：按列网格排布；vertical：全部整行宽竖排。 */
    val orientation: String = "grid",
    /** grid 模式的列数（1-4）。 */
    val columns: Int = 2,
    /** 主菜单整块（标题+按钮）的垂直对齐：top | center | bottom。 */
    val align: String = "center",
    /** 整块额外的垂直偏移（dp，-400..400）。 */
    val offsetY: Int = 0,
    /** 按钮区占屏宽百分比（30-100），仍受内置最大宽度上限约束。 */
    val widthPercent: Int = 65,
    /** 按钮最小高度 dp（28-96）。 */
    val buttonHeight: Int = 44,
    /** 按钮圆角 dp（0-32）。 */
    val buttonCorner: Int = 20,
    /** 按钮间距 dp（0-32）。 */
    val spacing: Int = 10,
    /** 是否绘制主菜单按钮的默认描边；单个按钮可覆盖。 */
    val showBorder: Boolean = true,
) {
    fun sanitized(): MenuLayoutSpec = copy(
        orientation = if (orientation in ORIENTATIONS) orientation else "grid",
        columns = columns.coerceIn(1, 4),
        align = if (align in ALIGNS) align else "center",
        offsetY = offsetY.coerceIn(-400, 400),
        widthPercent = widthPercent.coerceIn(30, 100),
        buttonHeight = buttonHeight.coerceIn(28, 96),
        buttonCorner = buttonCorner.coerceIn(0, 32),
        spacing = spacing.coerceIn(0, 32),
    )

    companion object {
        val ORIENTATIONS = setOf("grid", "vertical")
        val ALIGNS = setOf("top", "center", "bottom")
    }
}

/**
 * 单个主菜单按钮槽位的覆盖。order 相同的保持默认相对顺序。
 */
@Serializable
data class MenuButtonSpec(
    /** 排序权重，越小越靠前；默认 0 表示保持内置顺序。 */
    val order: Int = 0,
    /** grid 模式下占用列数（1-4）；0 / 省略时沿用该按钮的内置占列。 */
    val span: Int = 0,
    /** 隐藏该按钮（仅视觉隐藏，功能仍可从其他入口到达）。 */
    val hidden: Boolean = false,
    /** 相对于 span 分配的格子宽度的百分比（1-100），缩窄后在格子内居中。 */
    val widthPercent: Int = 100,
    /** 是否绘制默认描边；省略时继承 [MenuLayoutSpec.showBorder]。 */
    val showBorder: Boolean? = null,
) {
    fun sanitized(): MenuButtonSpec = copy(
        span = span.coerceIn(0, 4),
        widthPercent = widthPercent.coerceIn(1, 100),
    )
}

/** 主菜单按钮的稳定语义 id，美术包据此覆盖排布与背景图（文档承诺不变）。 */
val MENU_BUTTON_IDS: Set<String> = setOf(
    "singlePlayer", "multiplayer", "resourceBrowser", "mods", "settings", "openSourceInfo",
)

/**
 * 主题字体（v2）：包内字体文件路径（相对包根），生效于全局 Typography。
 */
@Serializable
data class FontsSpec(
    val regular: String = "",
    val bold: String = "",
)

/**
 * `[colors]` 段支持的角色名（与 Material3 `ColorScheme` 对应），未列出的角色由内置基础主题兜底。
 */
val SUPPORTED_COLOR_ROLES: Set<String> = setOf(
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "background", "onBackground", "surface", "onSurface",
    "surfaceContainer", "error", "onError", "inversePrimary",
)

private val THEME_ID_REGEX = Regex("[a-zA-Z0-9_-]+")

private val themeToml = Toml {
    ignoreUnknownKeys = true
}

/**
 * 解析 `#RRGGBB` 或 `#AARRGGBB` 为 ARGB Long；必须带 `#` 前缀，非法格式返回 null。
 */
fun parseColorHex(s: String): Long? {
    val trimmed = s.trim()
    if (!trimmed.startsWith("#")) return null
    val hex = trimmed.removePrefix("#")
    return when (hex.length) {
        6 -> hex.toLongOrNull(16)?.let { 0xFF000000L or it }
        8 -> hex.toLongOrNull(16)
        else -> null
    }
}

/**
 * 主题包校验结果。[errors] 非空时必须拒绝导入；[warnings] 仅供展示。
 */
data class ThemeValidation(
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * 解析并校验 `theme.toml` 文本。
 *
 * 返回 `(spec, validation)`：解析失败或存在硬性错误时 spec 为 null。
 * 校验只在导入时执行，运行时路径不做可能失败的解析。
 */
fun validateThemeToml(text: String): Pair<ArtThemeSpec?, ThemeValidation> {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    val spec = runCatching {
        themeToml.decodeFromString(ArtThemeSpec.serializer(), text)
    }.getOrElse {
        return null to ThemeValidation(errors = listOf("theme.toml 解析失败: ${it.message}"))
    }

    val id = spec.theme.id.trim()
    if (id.isEmpty()) {
        errors += "缺少 [theme] id"
    } else if (!THEME_ID_REGEX.matches(id)) {
        errors += "id 只能包含字母、数字、下划线与连字符: $id"
    }
    if (spec.theme.name.isBlank()) {
        errors += "缺少 [theme] name"
    }

    spec.colors.forEach { (role, hex) ->
        if (role !in SUPPORTED_COLOR_ROLES) {
            warnings += "未知配色角色已忽略: $role"
        } else if (parseColorHex(hex) == null) {
            warnings += "色值格式非法已忽略: $role = \"$hex\"（应为 #RRGGBB 或 #AARRGGBB）"
        }
    }

    // v2：布局与按钮槽位校验（数值越界在使用处钳制，此处只对「作者显然写错」的情况给警告）
    if (spec.menu.layout.orientation !in MenuLayoutSpec.ORIENTATIONS) {
        warnings += "未知布局方向已回落 grid: ${spec.menu.layout.orientation}"
    }
    if (spec.menu.layout.align !in MenuLayoutSpec.ALIGNS) {
        warnings += "未知对齐方式已回落 center: ${spec.menu.layout.align}"
    }
    spec.menu.buttons.keys.forEach { id ->
        if (id !in MENU_BUTTON_IDS) {
            warnings += "未知按钮槽位已忽略: $id（可用: ${MENU_BUTTON_IDS.joinToString()}）"
        }
    }
    // 字体路径必须是包内相对路径
    listOf(spec.fonts.regular, spec.fonts.bold).forEach { path ->
        if (path.isNotBlank() && !isSafeZipEntryName(path)) {
            errors += "字体路径非法: $path"
        }
    }

    return (if (errors.isEmpty()) spec else null) to ThemeValidation(errors, warnings)
}

/**
 * 校验 zip entry 名，防路径穿越（`FileUtils.unzipTo` 不做此检查）。
 */
fun isSafeZipEntryName(name: String): Boolean =
    name.isNotBlank() && !name.startsWith("/") && !name.contains("..") &&
        !name.contains("\\") && !Regex("^[a-zA-Z]:").containsMatchIn(name)
