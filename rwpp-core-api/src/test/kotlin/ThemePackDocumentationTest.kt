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
    fun publishedTomlExamplesParseAndThemeExamplesAreValid() {
        val document = File("../docs/theme-pack-format.md").readText()
        val snippets = Regex("```toml\\r?\\n([\\s\\S]*?)\\r?\\n```")
            .findAll(document).map { it.groupValues[1] }.toList()
        assertTrue(snippets.isNotEmpty())
        snippets.forEachIndexed { index, snippet ->
            Toml.parseToTomlTable(snippet)
            // 文案文件与主题配置使用不同模型；配置片段补元数据后走真实导入校验。
            val isThemeConfig = listOf("[theme]", "[menu.layout]", "[menu.buttons.", "[fonts]", "[colors]")
                .any { it in snippet }
            if (isThemeConfig) {
                val fullConfig = if ("[theme]" in snippet) snippet else
                    "[theme]\nid = \"documentation-test\"\nname = \"文档示例\"\n\n$snippet"
                val (spec, validation) = validateThemeToml(fullConfig)
                assertNotNull(spec, "文档例子 ${index + 1}: ${validation.errors}")
                assertTrue(validation.warnings.isEmpty(), "文档例子 ${index + 1}: ${validation.warnings}")
            }
        }
    }
}
