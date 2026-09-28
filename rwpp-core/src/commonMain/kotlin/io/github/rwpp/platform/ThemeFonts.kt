/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/**
 * 从磁盘文件加载字体（主题美术包用）。两端平台各自实现 `Font(File)` 的构建。
 */
expect fun fileFont(file: File, weight: FontWeight): Font

/**
 * 用主题包内的字体文件构建 [FontFamily]：regular 映射 [FontWeight.Normal]，
 * bold 缺省时用 regular 顶替（Bold 由系统合成或回落到同一字形）。
 * 两个文件都缺失/加载失败时返回 null，调用方回落内置字体。
 */
fun themeFontFamily(regular: File?, bold: File?): FontFamily? {
    if (regular == null && bold == null) return null
    return runCatching {
        FontFamily(
            buildList {
                if (regular != null) add(fileFont(regular, FontWeight.Normal))
                val boldFile = bold ?: regular
                if (boldFile != null) add(fileFont(boldFile, FontWeight.Bold))
            }
        )
    }.getOrNull()
}
