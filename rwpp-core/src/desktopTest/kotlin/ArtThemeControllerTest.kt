/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nOverrideTable
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.koinInit
import io.github.rwpp.logger
import io.github.rwpp.theme.ArtThemeController
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 主题美术包控制器的端到端管线（无 GUI）：导入 → 扫描 → 启用热切换 → 停用 → 删除。
 * 通过 [ArtThemeController.themeRootOverride] 把主题根目录指向临时目录，不触碰真实游戏目录。
 */
class ArtThemeControllerTest {

    private lateinit var workDir: File
    private lateinit var themesRoot: File
    private lateinit var settings: Settings

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        settings = Settings(autoCheckUpdate = false, language = "zh")
        val koin = startKoin {
            modules(module { single { settings } })
        }.koin
        appKoin = koin
        koinInit = true
        logger = LoggerFactory.getLogger("ArtThemeControllerTest")

        workDir = File(System.getProperty("java.io.tmpdir"), "rwpp-theme-ctl-${System.nanoTime()}")
        themesRoot = File(workDir, "themes").apply { mkdirs() }
        ArtThemeController.themeRootOverride = themesRoot
    }

    @AfterTest
    fun tearDown() {
        ArtThemeController.apply(null)
        ArtThemeController.themeRootOverride = null
        setI18nOverride(null)
        runCatching { stopKoin() }
        koinInit = false
        workDir.deleteRecursively()
    }

    private fun buildPack(entries: Map<String, String>): File {
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

    @Test
    fun importApplyDisableDeletePipeline() {
        runBlocking {
        val pack = buildPack(
            mapOf(
                "theme.toml" to """
                    [theme]
                    id = "sample"
                    name = "示例主题"

                    [colors]
                    primary = "#EEC4D8"

                    [menu.layout]
                    orientation = "vertical"
                    columns = 3

                    [fonts]
                    regular = "fonts/regular.ttf"
                """.trimIndent(),
                "strings_zh.toml" to "[menu]\nsinglePlayerGame = \"孤胆征程\"\n",
                "title.png" to "fake-png",
                "buttons/mods.png" to "fake-png",
                "buttons/notAButton.png" to "fake-png",
                "fonts/regular.ttf" to "fake-ttf"
            )
        )

        // 导入
        val result = ArtThemeController.import(pack)
        assertTrue(result.success, "errors: ${result.errors}")
        assertEquals(1, ArtThemeController.installedThemes.size)
        val theme = ArtThemeController.installedThemes.single()
        assertEquals("sample", theme.id)
        assertNotNull(theme.titleFile)
        assertNotNull(theme.stringsZhFile)
        // v2：按钮图只承认语义 id，字体文件按包内相对路径解析
        assertEquals(setOf("mods"), theme.buttonImages.keys)
        assertNotNull(theme.fontRegularFile)
        assertNull(theme.fontBoldFile)
        assertEquals("vertical", theme.spec.menu.layout.orientation)

        // 启用：配色 + 文本覆盖 + 持久化字段同时生效
        ArtThemeController.apply("sample")
        assertEquals("sample", ArtThemeController.activeTheme?.id)
        assertEquals("sample", settings.selectedThemePack)
        assertEquals(0xFFEEC4D8.toInt(), theme.colorScheme.primary.value.shr(32).toInt())
        assertNotNull(i18nOverrideTable)

        // 停用：全部回落
        ArtThemeController.apply(null)
        assertNull(ArtThemeController.activeTheme)
        assertNull(settings.selectedThemePack)
        assertNull(i18nOverrideTable)

        // 删除
        assertTrue(ArtThemeController.delete("sample"))
        assertTrue(ArtThemeController.installedThemes.isEmpty())
        assertFalse(File(themesRoot, "sample").exists())
        }
    }

    @Test
    fun deleteRejectsUnknownId() {
        assertEquals(false, ArtThemeController.delete("not-exist"))
    }
}
