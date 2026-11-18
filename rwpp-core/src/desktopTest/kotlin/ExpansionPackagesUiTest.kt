/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.expansion.ExpansionManifest
import io.github.rwpp.expansion.ExpansionTheme
import io.github.rwpp.expansion.InstalledExpansion
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.widget.UiMotionTheme
import net.peanuuutz.tomlkt.Toml
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ExpansionPackagesUiTest {
    private val installed = InstalledExpansion(File("unused-test-package"),
        ExpansionManifest(gameAppId = "11111111-1111-1111-1111-111111111111", name = "星海完整扩展包",
            theme = ExpansionTheme(packId = "22222222-2222-2222-2222-222222222222", version = "art-1", catalogHash = "b".repeat(64))),
        "1.0.0", "a".repeat(64), 2048 * 1024)

    @BeforeTest fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin { modules(module { single { Settings(enableAnimations = false) } }) }.koin
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
    }
    @AfterTest fun cleanup() { stopKoin(); reloadI18n() }

    @Test fun installedExpansionsHaveTheirOwnManagementActions() = runDesktopComposeUiTest(width = 960, height = 640) {
        var joined: InstalledExpansion? = null
        var deleted: InstalledExpansion? = null
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                ExpansionPackagesContent(listOf(installed), null, false, false, null, false,
                    {}, {}, { deleted = it }, {}, { joined = it }, {})
            }
        }
        onNodeWithText("星海完整扩展包").assertIsDisplayed()
        onNodeWithTag("expansion-join-${installed.sha256}").performClick()
        onNodeWithTag("expansion-delete-${installed.sha256}").performClick()
        runOnIdle { assertSame(installed, joined); assertSame(installed, deleted) }
        save("desktop.png")
    }

    @Test fun activePackageCannotBeDeletedOrEnabledElsewhere() = runDesktopComposeUiTest(width = 412, height = 844) {
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                ExpansionPackagesContent(listOf(installed), installed.sha256, false, true, null, false,
                    {}, {}, { error("active delete") }, {}, { error("offline enable") }, {})
            }
        }
        onNodeWithTag("expansion-delete-${installed.sha256}").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("expansion-join-${installed.sha256}").assertIsNotEnabled()
        save("portrait-active.png")
    }

    @Test fun emptyLibraryKeepsImportReachable() = runDesktopComposeUiTest(width = 412, height = 500) {
        var imports = 0
        setContent {
            UiMotionTheme(darkColorScheme(), Typography()) {
                ExpansionPackagesContent(emptyList(), null, false, false, null, false,
                    { imports++ }, {}, {}, {}, {}, {})
            }
        }
        onNodeWithTag("expansion-empty").assertIsDisplayed()
        onNodeWithTag("expansion-import").performClick()
        runOnIdle { assertEquals(1, imports) }
    }

    private fun ComposeUiTest.save(name: String) {
        val file = File("build/reports/expansion-library/$name").apply { parentFile.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }
}
