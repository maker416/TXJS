/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import net.peanuuutz.tomlkt.Toml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThemePackDocumentationTest {
    @Test
    fun publishedRoomExampleUsesTheProductionSchema() {
        listOf("room-theme.example.toml", "theme.toml").forEach { name ->
            val example = File("../examples/themes/pelagic-atlas", name)
            assertTrue(example.isFile, "找不到战斗室美术包示例配置 $name")
            val (spec, validation) = validateThemeToml(example.readText())
            assertNotNull(spec, validation.errors.joinToString())
            assertTrue(validation.warnings.isEmpty(), "$name: ${validation.warnings}")
            assertTrue(spec.room.panels.isNotEmpty(), "示例必须实际配置战斗室")
        }
    }

    @Test
    fun publishedTomlExamplesParseAndThemeExamplesAreValid() {
        // 旧文档入口及当前 v3 完整规范均须通过生产解析器校验。
        val documents = listOf("theme-pack-format.md", "主题包开发文档.md", "主题包开发文档v2.md", "主题包开发文档v3.md", "战斗室美术包开发.md")
            .map { File("../docs", it) }
            .filter { it.isFile }
        assertTrue(documents.isNotEmpty(), "找不到主题包开发文档")
        documents.forEach { validateExamples(it) }
    }

    private fun validateExamples(file: File) {
        val document = file.readText()
        val snippets = Regex("```toml\\r?\\n([\\s\\S]*?)\\r?\\n```")
            .findAll(document).map { it.groupValues[1] }.toList()
        assertTrue(snippets.isNotEmpty(), "${file.name} 缺少 TOML 示例")
        snippets.forEachIndexed { index, snippet ->
            Toml.parseToTomlTable(snippet)
            // 文案文件与主题配置使用不同模型；配置片段补元数据后走真实导入校验。
            val isThemeConfig = listOf("[theme]", "[menu.layout]", "[menu.buttons.", "[fonts]", "[colors]", "[room.")
                .any { it in snippet }
            if (isThemeConfig) {
                val fullConfig = if ("[theme]" in snippet) snippet else
                    "[theme]\nid = \"documentation-test\"\nname = \"文档示例\"\n\n$snippet"
                val (spec, validation) = validateThemeToml(fullConfig)
                assertNotNull(spec, "${file.name} 例子 ${index + 1}: ${validation.errors}")
                assertTrue(validation.warnings.isEmpty(), "${file.name} 例子 ${index + 1}: ${validation.warnings}")
            }
        }
    }
}
