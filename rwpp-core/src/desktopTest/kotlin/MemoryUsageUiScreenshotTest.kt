/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.rwpp.core.MemoryUsage
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.I18nType
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.reloadI18n
import io.github.rwpp.i18n.setI18nOverride
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.title
import io.github.rwpp.widget.v2.LineSpinFadeLoaderIndicator
import net.peanuuutz.tomlkt.Toml
import org.jetbrains.compose.resources.painterResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 固定内存样本验证小屏文字与警告，不读取实际进程内存或触发 GC。 */
@OptIn(ExperimentalTestApi::class)
class MemoryUsageUiScreenshotTest {
    private val elevated = MemoryUsage(
        heapUsedBytes = 420L * MiB,
        heapMaxBytes = 512L * MiB,
        processResidentBytes = 1024L * MiB,
        nativeHeapBytes = 500L * MiB,
    )
    private val critical = elevated.copy(heapUsedBytes = 470L * MiB)

    @BeforeTest
    fun setup() {
        clearProtectedModLoadHint()
        reloadI18n()
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(
            File("src/commonMain/composeResources/files/bundle_zh.toml").readText(),
        )
    }

    @AfterTest
    fun tearDown() {
        clearProtectedModLoadHint()
        reloadI18n()
    }

    @Test
    fun expandedPanelFitsPortraitAndExplainsMemoryMeasurements() =
        runDesktopComposeUiTest(width = 360, height = 720) {
            setContent {
                TestTheme {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        MemoryUsageContent(elevated, Modifier.testTag("memory-panel"))
                    }
                }
            }
            waitForIdle()
            assertTextFits("420.0 MiB")
            assertTextFits("512.0 MiB")
            assertTextFits("进程物理内存（RSS）：1.0 GiB")
            assertTextFits("原生堆：500.0 MiB")
            assertTextFits(readI18n("memory.explanation"))
            assertTextFits(readI18n("memory.elevated"))
            save("memory_portrait_expanded.png")
        }

    @Test
    fun compactDarkPanelFitsShortStartupLayout() =
        runDesktopComposeUiTest(width = 720, height = 360) {
            val protectedName = "大型加固模组"
            loadingProtectedModNames = listOf(protectedName)
            setContent {
                TestTheme {
                    // 与启动页相同的短横屏空间：标题图、加载动画、单位进度和内存卡片。
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.weight(0.10f))
                        Image(
                            painterResource(Res.drawable.title),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(0.36f).padding(10.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.weight(0.06f))
                        LineSpinFadeLoaderIndicator(radius = 18f, penThickness = 6f, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            Modifier.widthIn(max = 520.dp).fillMaxWidth(0.86f),
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Black.copy(alpha = 0.48f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        ) {
                            Column(
                                Modifier.verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                StructuredLoadingContent(
                                    StructuredLoadingMessage("units", "253", "正在加载重型模组 / custom_unit"),
                                )
                                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                                MemoryUsageContent(
                                    elevated,
                                    Modifier.testTag("memory-panel"),
                                    compact = true,
                                    darkBackground = true,
                                )
                                ProtectedModLoadNotice(loadingText = "Loading units - 253 ($protectedName)", darkSplash = true)
                            }
                        }
                        Spacer(Modifier.weight(0.16f))
                    }
                }
            }
            waitForIdle()
            assertTextFits("420.0 MiB")
            assertTextFits("512.0 MiB")
            assertTextFits("进程物理内存（RSS）：1.0 GiB")
            assertTextFits(readI18n("memory.elevated"))
            val panel = onNodeWithTag("memory-panel").fetchSemanticsNode().boundsInRoot
            assertTrue(panel.top >= 0f && panel.bottom <= 360f, "startup memory panel clipped: $panel")
            save("memory_startup_landscape.png")
            onNodeWithText(readI18n("mod.protectedLoadHint", I18nType.RWPP, protectedName))
                .performScrollTo().assertIsDisplayed()
        }

    @Test
    fun criticalHeapAndPhysicalMemoryRemainReadableInNarrowLoadingCard() =
        runDesktopComposeUiTest(width = 360, height = 360) {
            setContent {
                TestTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Surface(Modifier.width(300.dp), shape = RoundedCornerShape(12.dp)) {
                            Column(
                                Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                StructuredLoadingContent(StructuredLoadingMessage("units", "893", "custom_unit"))
                                MemoryUsageContent(critical, compact = true)
                                LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
                            }
                        }
                    }
                }
            }
            waitForIdle()
            assertTextFits("470.0 MiB")
            assertTextFits("512.0 MiB")
            assertTextFits("进程物理内存（RSS）：1.0 GiB")
            assertTextFits(readI18n("memory.critical"))
            save("memory_loading_narrow_critical.png")
        }

    private fun ComposeUiTest.assertTextFits(text: String) {
        val node = onNodeWithText(text, substring = true, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        assertTrue(layouts.isNotEmpty(), "missing text layout: $text")
        assertFalse(layouts.any { it.hasVisualOverflow }, "memory text clipped: $text")
    }

    private fun ComposeUiTest.save(name: String) {
        val target = File("build/reports/memory-ui/$name")
        check(target.parentFile.exists() || target.parentFile.mkdirs())
        check(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target))
        check(target.isFile && target.length() > 0L)
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = defaultRWPPColorScheme) {
            Box(Modifier.fillMaxSize().background(Color(53, 57, 53))) { content() }
        }
    }

    private companion object {
        const val MiB = 1_048_576L
    }
}
