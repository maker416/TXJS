/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.rwpp.i18n.i18nOverrideTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.theme.ArtThemeController
import io.github.rwpp.theme.parseColorHex
import net.peanuuutz.tomlkt.TomlElement
import net.peanuuutz.tomlkt.TomlLiteral
import net.peanuuutz.tomlkt.TomlTable
import java.io.File

/** 只覆盖作者明确提供的有效角色，材质包、标题包不会顺带改变新版首页的默认配色。 */
@Composable
internal fun launcherThemeColor(role: String, fallback: Color): Color =
    ArtThemeController.activeTheme?.spec?.colors?.get(role)?.let(::parseColorHex)?.let { Color(it) }
        ?: fallback

internal fun launcherThemeButtonImage(id: String): File? =
    ArtThemeController.activeTheme?.buttonImages?.get(id)?.takeIf { it.isFile }

/** 新版文字键优先；旧包的退出文案仍可沿用 menu.exit，不混淆论坛和资源中心语义。 */
internal fun launcherThemeText(path: String, legacyPath: String): String {
    fun hasOverride(key: String): Boolean {
        var element: TomlElement? = i18nOverrideTable
        key.split('.').forEach { element = (element as? TomlTable)?.get(it) }
        return element is TomlLiteral
    }
    return readI18n(if (!hasOverride(path) && hasOverride(legacyPath)) legacyPath else path)
}
