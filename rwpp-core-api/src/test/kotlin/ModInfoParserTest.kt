/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.game.mod.ModInfoParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModInfoParserTest {
    private val qq = "\"\"\""

    @Test
    fun parseIniSupportsEqualsSeparator() {
        val meta = ModInfoParser.parseIni(
            """
            [mod]
            title=Alpha
            description=Hello\nWorld
            minVersion=1.15
            """.trimIndent(),
            "fallback",
        )
        assertEquals("Alpha", meta.name)
        assertEquals("Hello\nWorld", meta.description)
        assertEquals("1.15", meta.minVersion)
    }

    @Test
    fun parseIniSupportsColonSeparator() {
        val meta = ModInfoParser.parseIni(
            """
            [mod]
            title: Bravo
            description: 简介内容
            minVersion: 1.14
            """.trimIndent(),
            "fallback",
        )
        assertEquals("Bravo", meta.name)
        assertEquals("简介内容", meta.description)
        assertEquals("1.14", meta.minVersion)
    }

    @Test
    fun parseIniSupportsTripleQuotedMultilineDescription() {
        val ini = """
            [mod]
            title: Multi
            description: $qq
            第一行介绍
            第二行介绍
            $qq
            minVersion: 1.15
        """.trimIndent()
        val meta = ModInfoParser.parseIni(ini, "fallback")
        assertEquals("Multi", meta.name)
        assertEquals("第一行介绍\n第二行介绍", meta.description)
        assertEquals("1.15", meta.minVersion)
    }

    @Test
    fun parseIniSupportsSameLineTripleQuotes() {
        val meta = ModInfoParser.parseIni(
            "[mod]\ntitle=Same\ndescription=${qq}一行介绍$qq\nminVersion=1.15\n",
            "fallback",
        )
        assertEquals("Same", meta.name)
        assertEquals("一行介绍", meta.description)
    }

    @Test
    fun parseIniStripsBomAndFallsBackName() {
        val meta = ModInfoParser.parseIni(
            "\uFEFF[mod]\ndescription=仅描述\n",
            "FileName",
        )
        assertEquals("FileName", meta.name)
        assertEquals("仅描述", meta.description)
    }

    @Test
    fun parseFromRwmodReadsNestedUtf8ModInfo() {
        val root = createTempDirectory().toFile()
        val rwmod = File(root, "demo.rwmod")
        val content = """
            [mod]
            title: 测试模组
            description: $qq
            这是介绍
            $qq
            minVersion: 1.15
        """.trimIndent()
        ZipOutputStream(rwmod.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("nested/mod-info.txt"))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val meta = ModInfoParser.parseFromRwmod(rwmod)
        assertEquals("测试模组", meta.name)
        assertEquals("这是介绍", meta.description)
        assertEquals("1.15", meta.minVersion)
        assertTrue(rwmod.delete())
        root.deleteRecursively()
    }
}
