/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** 真实解析深层 select；需要显式指定完整桌面游戏资源和可用 OpenGL / 音频设备。 */
class DeepSelectHeapIntegrationTest {
    @Test
    fun realCoreLoadsDeeplyNestedSelectWithoutOverflowingItsThreadStack() {
        val path = System.getProperty("rwpp.heap.integrationGameRoot")
        assumeTrue("仅在指定 rwpp.heap.integrationGameRoot 时运行真实核心测试", !path.isNullOrBlank())
        val root = File(requireNotNull(path)).absoluteFile
        assertTrue(EngineHeapAnalyzer.isGameRoot(root), "需要完整桌面游戏目录：$root")
        val directory = createTempDirectory("heap-core-deep-select-").toFile()
        try {
            val source = File(root, "assets/units/scout")
            val original = File(source, "scout.ini").readText()
            val name = Regex("(?m)^name\\s*[:=][^\\r\\n]*")
            assertTrue(name.containsMatchIn(original), "原版 scout.ini 缺少单位名称")
            val expression = (1..400).fold("0") { nested, _ -> "select(self.hp > 0, 0, $nested)" }
            File(directory, "scout.ini").writeText(
                original.replaceFirst(name, "name: rwjsHeapDeepSelectProbe") + "\n\n" + """
                    [hiddenAction_deepSelectProbe]
                    autoTriggerOnEvent: created
                    setUnitStats: moveSpeed= $expression
                """.trimIndent(),
            )
            listOf("base.png", "base_dead.png").forEach { image ->
                File(source, image).copyTo(File(directory, image))
            }
            val result = EngineHeapAnalyzer.measure(directory, root)
            assertEquals(1, result.unitCount, "深层表达式必须完成原版解析和单位注册")
            assertEquals("rwjsHeapDeepSelectProbe", result.units.single().name)
            assertTrue(result.definitionHeapBytes > 0, "必须测量实际加载的单位对象")
            assertTrue(result.loadedHeapBytes > 0)
        } finally {
            directory.deleteRecursively()
        }
    }
}
