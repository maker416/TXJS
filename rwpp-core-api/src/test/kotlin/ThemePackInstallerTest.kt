/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThemePackInstallerTest {

    private lateinit var workDir: File
    private lateinit var themesRoot: File

    @BeforeTest
    fun setup() {
        workDir = File(System.getProperty("java.io.tmpdir"), "rwpp-theme-test-${System.nanoTime()}")
        themesRoot = File(workDir, "themes")
        themesRoot.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private fun pack(entries: Map<String, String>): File {
        val zipFile = File(workDir, "pack-${System.nanoTime()}.rwtheme")
        ZipOutputStream(zipFile.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return zipFile
    }

    private val validThemeToml = """
        [theme]
        id = "sample"
        name = "示例主题"

        [colors]
        primary = "#EEC4D8"
    """.trimIndent()

    @Test
    fun installValidPackExtractsFiles() {
        val zip = pack(
            mapOf(
                "theme.toml" to validThemeToml,
                "strings_zh.toml" to "[menu]\nsinglePlayerGame = \"孤胆征程\"\n",
                "assets/title.png" to "fake-png-bytes"
            )
        )
        val result = ThemePackInstaller.install(zip, themesRoot)
        assertTrue(result.success, "errors: ${result.errors}")
        assertEquals("sample", result.themeId)
        assertEquals("示例主题", result.themeName)
        assertTrue(File(themesRoot, "sample/theme.toml").exists())
        assertTrue(File(themesRoot, "sample/strings_zh.toml").exists())
        // 嵌套目录条目必须连同父目录一起解出
        assertTrue(File(themesRoot, "sample/assets/title.png").exists())
    }

    @Test
    fun missingThemeTomlIsRejected() {
        val zip = pack(mapOf("readme.txt" to "hello"))
        val result = ThemePackInstaller.install(zip, themesRoot)
        assertFalse(result.success)
        assertTrue(result.errors.any { it.contains("theme.toml") })
        assertTrue(themesRoot.listFiles().isNullOrEmpty(), "拒绝导入时不得落盘")
    }

    @Test
    fun duplicateIdIsRejected() {
        ThemePackInstaller.install(pack(mapOf("theme.toml" to validThemeToml)), themesRoot)
            .also { assertTrue(it.success) }
        val second = ThemePackInstaller.install(pack(mapOf("theme.toml" to validThemeToml)), themesRoot)
        assertFalse(second.success)
        assertTrue(second.errors.any { it.contains("相同 id") })
    }

    @Test
    fun unsafeEntryIsRejected() {
        val zip = pack(
            mapOf(
                "theme.toml" to validThemeToml,
                "../evil.txt" to "pwn"
            )
        )
        val result = ThemePackInstaller.install(zip, themesRoot)
        assertFalse(result.success)
        assertFalse(File(workDir, "evil.txt").exists())
    }

    @Test
    fun malformedStringsTomlIsRejected() {
        val zip = pack(
            mapOf(
                "theme.toml" to validThemeToml,
                "strings_zh.toml" to "this is = = not toml"
            )
        )
        val result = ThemePackInstaller.install(zip, themesRoot)
        assertFalse(result.success)
        assertTrue(result.errors.any { it.contains("strings_zh.toml") })
    }

    @Test
    fun warningsAreSurfaced() {
        val zip = pack(
            mapOf(
                "theme.toml" to """
                    [theme]
                    id = "warn"
                    name = "警告"

                    [colors]
                    primray = "#EEC4D8"
                """.trimIndent()
            )
        )
        val result = ThemePackInstaller.install(zip, themesRoot)
        assertTrue(result.success)
        assertTrue(result.warnings.any { it.contains("primray") })
        assertNotNull(result.themeId)
    }
}
