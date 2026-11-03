/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.core.MemoryUsage
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import net.peanuuutz.tomlkt.Toml
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 固定样本验证顶栏摘要和可按需展开的详情，不读取进程内存。 */
@OptIn(ExperimentalTestApi::class)
class MemoryUsageButtonUiTest {
    private val usage = MemoryUsage(
        heapUsedBytes = 470L * MiB,
        heapMaxBytes = 512L * MiB,
        processResidentBytes = 1024L * MiB,
        nativeHeapBytes = 500L * MiB,
        totalPhysicalBytes = 8192L * MiB,
        availablePhysicalBytes = 2048L * MiB,
    )

    @BeforeTest
    fun setup() {
        runCatching { stopKoin() }
        appKoin = startKoin {
            modules(module { single { Settings(enableAnimations = false) } })
        }.koin
        setI18nOverride(null)
        loadLanguage("zh")
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        reloadI18n()
        setI18nOverride(null)
    }

    @Test
    fun shortLandscapeKeepsSummaryCompactAndDetailsScrollable() =
        runDesktopComposeUiTest(width = 720, height = 320) {
            setContent { TestContent(summaryWidth = 90) }

            onNodeWithTag("memory-summary")
                .assertWidthIsEqualTo(90.dp)
                .assertHeightIsAtLeast(48.dp)
            saveSummary("memory_summary_short_landscape.png")
            assertTextFits("92%")
            onNodeWithTag("memory-details").assertDoesNotExist()
            onNodeWithText(readI18n("memory.explanation")).assertDoesNotExist()

            onNodeWithTag("memory-summary").performClick()
            onNodeWithTag("memory-details").assertIsDisplayed()
            onNodeWithText(readI18n("common.close")).assertIsDisplayed()
            onNodeWithText("470.0 MiB", substring = true, useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
            onNodeWithText(readI18n("memory.explanation"), useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
            assertTextFits(readI18n("memory.explanation"))
            onNodeWithText(readI18n("memory.critical"), useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
            saveDetails("memory_details_short_landscape.png")

            onNodeWithText(readI18n("common.close")).assertIsDisplayed().performClick()
            onNodeWithTag("memory-details").assertDoesNotExist()
            onNodeWithTag("memory-summary").assertIsDisplayed()
        }

    @Test
    fun narrowEnglishSummaryRetainsThePercentageAndDetailsCloseInPortrait() =
        runDesktopComposeUiTest(width = 360, height = 720) {
            loadLanguage("en")
            setContent { TestContent(summaryWidth = 70) }

            onNodeWithTag("memory-summary").assertWidthIsEqualTo(70.dp)
            saveSummary("memory_summary_narrow_english.png")
            assertTextFits("92%")
            onNodeWithTag("memory-summary").performClick()
            onNodeWithText(readI18n("memory.explanation"), useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
            assertTextFits(readI18n("memory.explanation"))
            saveDetails("memory_details_portrait_english.png")
            onNodeWithText(readI18n("common.close")).assertIsDisplayed().performClick()
            onNodeWithTag("memory-details").assertDoesNotExist()
        }

    @Composable
    private fun TestContent(summaryWidth: Int) {
        KoinContext(appKoin) {
            MaterialTheme(colorScheme = defaultRWPPColorScheme) {
                Box(Modifier.fillMaxSize().padding(12.dp)) {
                    MemoryUsageButtonContent(usage, Modifier.width(summaryWidth.dp))
                }
            }
        }
    }

    private fun ComposeUiTest.assertTextFits(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty(), "Missing text layout: $text")
        assertFalse(layouts.any { it.hasVisualOverflow }, "Text clipped: $text; ${layouts.map {
            "size=${it.size}, widthOverflow=${it.didOverflowWidth}, heightOverflow=${it.didOverflowHeight}, constraints=${it.layoutInput.constraints}"
        }}")
    }

    private fun ComposeUiTest.saveSummary(name: String) {
        val target = File("build/reports/memory-ui/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        check(ImageIO.write(onNodeWithTag("memory-summary").captureToImage().toAwtImage(), "png", target))
    }

    private fun ComposeUiTest.saveDetails(name: String) {
        val target = File("build/reports/memory-ui/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        check(ImageIO.write(onNodeWithTag("memory-details").captureToImage().toAwtImage(), "png", target))
    }

    private fun loadLanguage(language: String) {
        reloadI18n()
        i18nTable = Toml.parseToTomlTable(
            File("src/commonMain/composeResources/files/bundle_$language.toml").readText(),
        )
    }

    private companion object {
        const val MiB = 1_048_576L
    }
}
