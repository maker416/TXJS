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

    return (if (errors.isEmpty()) spec else null) to ThemeValidation(errors, warnings)
}

/**
 * 校验 zip entry 名，防路径穿越（`FileUtils.unzipTo` 不做此检查）。
 */
fun isSafeZipEntryName(name: String): Boolean =
    name.isNotBlank() && !name.startsWith("/") && !name.contains("..") &&
        !name.contains("\\") && !Regex("^[a-zA-Z]:").containsMatchIn(name)
