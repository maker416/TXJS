/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import io.github.rwpp.io.unzipTo
import net.peanuuutz.tomlkt.Toml
import java.io.File
import java.util.zip.ZipFile

/**
 * 主题包安装结果。[errors] 非空表示被拒绝（未落盘）；[warnings] 仅供 UI 展示。
 */
data class ThemeInstallResult(
    val success: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val themeId: String? = null,
    val themeName: String? = null,
)

/**
 * 主题美术包（`.rwtheme` zip）的安装器：全部校验在解压落盘前完成。
 *
 * 纯 JVM 实现（无 Compose/Koin 依赖），便于单元测试；
 * `ArtThemeController.import` 只是本类的协程包装 + 安装后重扫。
 */
object ThemePackInstaller {

    /**
     * 校验并解压 [zipFile] 到 `themesRoot/<id>/`。
     * 校验失败或 IO 异常时不落盘（或尽量清理半成品目录），返回带 errors 的结果。
     */
    fun install(zipFile: File, themesRoot: File): ThemeInstallResult {
        return runCatching { installUnsafe(zipFile, themesRoot) }.getOrElse {
            ThemeInstallResult(false, errors = listOf("导入失败: ${it.message}"))
        }
    }

    private fun installUnsafe(zipFile: File, themesRoot: File): ThemeInstallResult {
        ZipFile(zipFile).use { zip ->
            val themeEntry = zip.getEntry("theme.toml")
                ?: return ThemeInstallResult(false, errors = listOf("包内缺少 theme.toml"))
            val text = zip.getInputStream(themeEntry).reader().readText()
            val (spec, validation) = validateThemeToml(text)
            if (spec == null) {
                return ThemeInstallResult(false, errors = validation.errors)
            }
            val target = File(themesRoot, spec.theme.id)
            if (target.exists()) {
                return ThemeInstallResult(
                    false,
                    errors = listOf("已存在相同 id 的主题包: ${spec.theme.id}")
                )
            }
            val unsafe = zip.entries().toList()
                .map { it.name }
                .filterNot { isSafeZipEntryName(it) }
            if (unsafe.isNotEmpty()) {
                return ThemeInstallResult(
                    false,
                    errors = listOf("包内包含非法文件路径: ${unsafe.first()}")
                )
            }
            // 文本覆盖文件预解析：非法 TOML 直接拒绝导入（运行时不再解析）
            for (name in listOf("strings_zh.toml", "strings_en.toml")) {
                val entry = zip.getEntry(name) ?: continue
                val content = zip.getInputStream(entry).reader().readText()
                runCatching { Toml.parseToTomlTable(content.replace("\r", "\n")) }
                    .onFailure {
                        return ThemeInstallResult(
                            false,
                            errors = listOf("$name 解析失败: ${it.message}")
                        )
                    }
            }
            target.mkdirs()
            zipFile.unzipTo(target)
            return ThemeInstallResult(
                true,
                warnings = validation.warnings,
                themeId = spec.theme.id,
                themeName = spec.theme.name
            )
        }
    }
}
