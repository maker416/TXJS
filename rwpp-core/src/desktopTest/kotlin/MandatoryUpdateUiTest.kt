/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.rwpp.app.*
import io.github.rwpp.net.LatestVersionProfile
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.i18n.readI18n
import io.github.rwpp.i18n.setI18nOverride
import net.peanuuutz.tomlkt.Toml
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MandatoryUpdateUiTest {
    private val release = LatestVersionProfile("1.21.0", "## 更流畅的全新体验\n\n- 新增 Android 分卷更新\n- 更新后自动清理旧产物\n- 保留您的模组与个人数据\n\n### 体验优化\n联机与资源管理体验优化。", false, emptyList())

    private fun updateText(key: String) = readI18n("mandatoryUpdate.$key")

    private fun loadLanguage(language: String) {
        // 切换 bundle 时同时清空 readI18n 缓存，确保英文测试实际使用英文。
        setI18nOverride(null)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_$language.toml").readText())
    }

    private fun screenshot(
        name: String,
        width: Int,
        height: Int,
        progress: UpdateProgress,
        action: String = "download",
        language: String = "zh",
        fontScale: Float = 1f,
        percentage: String? = null,
    ) =
        runDesktopComposeUiTest(width = width, height = height) {
            loadLanguage(language)
            val localizedRelease = if (language == "en") release.copy(
                body = "## A smoother experience\n\n- Improved update downloads\n- Automatic cleanup after updates\n- Your settings and mods are preserved\n\n### Improvements\nBetter multiplayer and resource management.",
            ) else release
            if (language == "en") assertEquals("Update available", updateText("title"))
            var updates = 0
            var cancels = 0
            var exits = 0
            var manualInstalls = 0
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    MandatoryUpdateScreen(localizedRelease, progress, { updates++ }, { cancels++ }, { exits++ }, { manualInstalls++ })
                }
            }
            onNodeWithText(updateText("exit")).assertIsDisplayed()
            onNodeWithText(updateText(action)).assertIsDisplayed()
            onNodeWithText(updateText("manual")).assertIsDisplayed()
            onNodeWithText("稍后提醒").assertDoesNotExist()
            if (percentage == null) {
                onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
            } else {
                onNodeWithText(percentage).assertIsDisplayed()
            }
            if (progress.stage == UpdateStage.FAILED) {
                onNodeWithText(progress.error!!).assertIsDisplayed()
            }
            val output = File("build/reports/update-ui/$name.png").apply { parentFile.mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", output)
            onNodeWithText(updateText("manual")).performClick()
            onNodeWithText(updateText(action)).performClick()
            onNodeWithText(updateText("exit")).performClick()
            assertEquals(1, manualInstalls)
            assertEquals(if (action == "stop") 0 else 1, updates)
            assertEquals(if (action == "stop") 1 else 0, cancels)
            assertEquals(1, exits)
        }

    @Test fun desktopReady() = screenshot("desktop", 1100, 700, UpdateProgress(UpdateStage.READY))
    @Test fun phonePortrait() = screenshot("phone", 360, 720, UpdateProgress(UpdateStage.READY))
    @Test fun phoneLandscapeDownloading() = screenshot("landscape", 800, 360,
        UpdateProgress(UpdateStage.DOWNLOADING, .42f, 65000000, 150000000, 2000000, 1, 2),
        action = "stop", percentage = "42%")

    @Test fun failedUpdateOffersRetry() = screenshot("failed", 360, 720,
        UpdateProgress(UpdateStage.FAILED, error = "网络连接已中断，请检查网络后重试。"), action = "retry")

    @Test fun permissionRequiredOffersContinuation() = screenshot("permission", 360, 720,
        UpdateProgress(UpdateStage.PERMISSION_REQUIRED), action = "grant")

    @Test fun openedInstallerCanBeOpenedAgain() = screenshot("installing", 360, 720,
        UpdateProgress(UpdateStage.INSTALLING), action = "reinstall")

    @Test fun unknownDownloadSizeDoesNotInventPercentage() = screenshot("unknown-size", 360, 720,
        UpdateProgress(UpdateStage.DOWNLOADING, downloadedBytes = 65000000, bytesPerSecond = 2000000), action = "stop")

    @Test fun verificationDoesNotReuseDownloadPercentage() = screenshot("verifying", 800, 360,
        UpdateProgress(UpdateStage.VERIFYING, 1f, 150000000, 150000000), action = "stop")

    @Test fun extractionDoesNotReuseDownloadPercentage() = screenshot("extracting", 800, 360,
        UpdateProgress(UpdateStage.EXTRACTING, 1f, 150000000, 150000000), action = "stop")

    @Test fun narrowEnglishLayoutKeepsActionsVisibleWithLargeText() = screenshot("english-large-text", 320, 720,
        UpdateProgress(UpdateStage.READY), language = "en", fontScale = 1.3f)

    @Test fun longReleaseNotesScrollWithoutHidingActions() = runDesktopComposeUiTest(width = 360, height = 720) {
        loadLanguage("zh")
        val endMarker = "更新说明结束"
        val longRelease = release.copy(body = (1..30).joinToString("\n\n") { "第 $it 项改进：优化联机与资源管理体验。" } + "\n\n$endMarker")
        var updates = 0
        setContent { MandatoryUpdateScreen(longRelease, UpdateProgress(UpdateStage.READY), { updates++ }, {}, {}, {}) }
        onNodeWithText(updateText("download")).assertIsDisplayed()
        onNodeWithText(endMarker).performScrollTo().assertIsDisplayed()
        onNodeWithText(updateText("exit")).assertIsDisplayed()
        onNodeWithText(updateText("manual")).assertIsDisplayed()
        onNodeWithText(updateText("download")).assertIsDisplayed().performClick()
        assertEquals(1, updates)
    }

    @Test fun lightThemeTextColorsDoNotLeakIntoUpdatePage() = runDesktopComposeUiTest(width = 1100, height = 700) {
        loadLanguage("zh")
        val blackText = TextStyle(color = Color.Black, fontFamily = FontFamily.Monospace)
        val previewRelease = release.copy(body = "", prerelease = true)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(onSurface = Color.Black),
                typography = Typography(
                    headlineLarge = blackText,
                    headlineMedium = blackText,
                    headlineSmall = blackText,
                    bodyLarge = blackText,
                    bodyMedium = blackText,
                    bodySmall = blackText,
                ),
            ) {
                MandatoryUpdateScreen(previewRelease, UpdateProgress(UpdateStage.READY), {}, {}, {}, {})
            }
        }
        onNodeWithText(updateText("preview")).assertIsDisplayed()
        for (key in listOf("title", "subtitle", "notes", "emptyNotes")) {
            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithText(updateText(key), useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty(), "Missing text layout for $key")
            for (layout in layouts) {
                val color = layout.layoutInput.style.color
                assertTrue(color != Color.Unspecified, "Text color must be resolved for $key")
                assertTrue(color.luminance() > 0.35f, "Dark theme text is unreadable for $key: $color")
                if (key == "title") {
                    assertEquals(FontFamily.Monospace, layout.layoutInput.style.fontFamily, "The selected theme font must be retained")
                }
            }
        }
        val output = File("build/reports/update-ui/light-theme-preview.png").apply { parentFile.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", output)
    }
}
