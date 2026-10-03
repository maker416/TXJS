/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.graphics.toAwtImage
import io.github.rwpp.config.BrowserUploadSource
import io.github.rwpp.config.Settings
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.net.browser.BrowserModUploadCache
import io.github.rwpp.platform.BrowserFileUploadRequest
import io.github.rwpp.platform.EmbeddedBrowserState
import androidx.compose.ui.test.runDesktopComposeUiTest
import net.peanuuutz.tomlkt.Toml
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

@OptIn(ExperimentalTestApi::class)
class BrowserModUploadUiTest {
    private lateinit var directory: File
    private lateinit var cache: BrowserModUploadCache
    @BeforeTest fun setup() {
        directory = Files.createTempDirectory("browser-upload-ui-").toFile()
        cache = BrowserModUploadCache(directory)
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
    }
    @AfterTest fun cleanup() { cache.close(); directory.deleteRecursively() }

    @Test fun firstChoiceIsSavedAndLaterUploadsOpenTheSelectedSource() = runDesktopComposeUiTest(width = 360, height = 720) {
        val settings = Settings()
        var saved = 0
        var browsed = 0
        val browser = EmbeddedBrowserState("https://forum.example")
        fun request() = BrowserFileUploadRequest(emptyList(), false, cache, { browsed++ }, {})
        browser.offerFileUpload(request())
        setContent {
            MaterialTheme { browser.fileUpload?.let { request ->
                androidx.compose.runtime.key(request) { BrowserFileUploadDialog(request, settings, { saved++ }) { emptyList() } }
            } }
        }
        onNodeWithText("选择模组上传来源").assertIsDisplayed()
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
        val screenshot = File("build/reports/browser-upload/source-portrait.png").apply { parentFile.mkdirs() }
        ImageIO.write(onAllNodes(isRoot())[roots.lastIndex].captureToImage().toAwtImage(), "png", screenshot)
        onNodeWithText("浏览文件").performClick()
        waitForIdle()
        assertEquals(BrowserUploadSource.Files, settings.browserUploadSource)
        assertEquals(1, saved)
        assertEquals(1, browsed)
        runOnIdle { browser.offerFileUpload(request()) }
        waitForIdle()
        assertEquals(2, browsed)
        assertEquals(1, saved)
    }

    @Test fun managerChoicePackagesSelectedModAndSupportsCancellation() = runDesktopComposeUiTest(width = 720, height = 360) {
        val settings = Settings()
        val mod = File(directory, "folder").apply { mkdir(); File(this, "mod-info.txt").writeText("[mod]\ntitle: UI") }
        val browser = EmbeddedBrowserState("https://forum.example")
        var received: List<File>? = null
        var delivered = 0
        val request = BrowserFileUploadRequest(listOf(".rwmod"), false, cache, {}, { received = it; delivered++ })
        browser.offerFileUpload(request)
        setContent { MaterialTheme {
            browser.fileUpload?.let { BrowserFileUploadDialog(it, settings, {}) { listOf(BrowserUploadMod("测试模组", mod)) } }
        } }
        onNodeWithText("从模组管理器选择").performClick()
        waitUntil(timeoutMillis = 5000) { settings.browserUploadSource == BrowserUploadSource.Mods }
        waitForIdle()
        onNodeWithText("测试模组.rwmod").assertIsDisplayed()
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
        val screenshot = File("build/reports/browser-upload/mods-landscape.png").apply { parentFile.mkdirs() }
        ImageIO.write(onAllNodes(isRoot())[roots.lastIndex].captureToImage().toAwtImage(), "png", screenshot)
        onNodeWithText("测试模组").performClick()
        onNodeWithText("上传所选模组").performClick()
        waitUntil(timeoutMillis = 5000) { delivered == 1 }
        assertEquals("测试模组.rwmod", received!!.single().name)
        assertTrue(received!!.single().isFile)
        runOnIdle { request.cancel(); browser.pageStarted() }
        assertEquals(1, delivered)
        assertNull(browser.fileUpload)
    }

    @Test fun imageUploadUsesFilePickerWithoutChangingModPreference() = runDesktopComposeUiTest {
        val settings = Settings(browserUploadSource = BrowserUploadSource.Mods)
        var browsed = 0
        val request = BrowserFileUploadRequest(listOf("image/*"), true, cache, { browsed++ }, {})
        setContent { MaterialTheme { BrowserFileUploadDialog(request, settings, { error("Must not save") }) { error("Must not read mods") } } }
        waitForIdle()
        assertEquals(1, browsed)
        assertEquals(BrowserUploadSource.Mods, settings.browserUploadSource)
    }

    @Test fun archiveUploadsShowTitleInPortraitAndUseItForZipOnlyWebInputs() = runDesktopComposeUiTest(width = 360, height = 720) {
        val settings = Settings()
        val source = File(directory, "v033.rwmod").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val title = "破境边缘 V0.33（烛烬华章：革新）"
        var received: List<File>? = null
        val request = BrowserFileUploadRequest(listOf(".zip"), false, cache, {}, { received = it })
        setContent { MaterialTheme {
            BrowserFileUploadDialog(request, settings, {}) { listOf(BrowserUploadMod(title, source)) }
        } }
        onNodeWithText("从模组管理器选择").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 5000) {
            onAllNodesWithText("$title.zip").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("$title.zip").assertIsDisplayed()
        val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
        val screenshot = File("build/reports/browser-upload/title-portrait.png").apply { parentFile.mkdirs() }
        ImageIO.write(onAllNodes(isRoot())[roots.lastIndex].captureToImage().toAwtImage(), "png", screenshot)
        onNodeWithText(title).performClick()
        onNodeWithText("上传所选模组").performClick()
        waitUntil(timeoutMillis = 5000) {
            received != null
        }
        assertEquals("$title.zip", received!!.single().name)
        assertContentEquals(source.readBytes(), received!!.single().readBytes())
        assertEquals("v033.rwmod", source.name)
    }

    @Test fun emptyModTitleFallsBackToSourceNameAndUnacceptedFilesRemainExcluded() {
        val source = File(directory, "v033.rwmod").apply { writeText("archive") }
        val request = BrowserFileUploadRequest(listOf(".rwmod"), false, cache, {}, {})
        assertEquals("v033.rwmod", request.uploadName(source, ""))
        assertEquals("中文 V0.33.rwmod", request.uploadName(source, "中文 V0.33"))
        assertNull(request.uploadName(File(directory, "info.txt"), "中文"))
    }

    @Test fun navigationAndLateNativeResultCompleteCallbackOnlyOnce() {
        var delivered = 0
        val browser = EmbeddedBrowserState("https://forum.example")
        val request = BrowserFileUploadRequest(emptyList(), true, cache, {}, { delivered++; assertNull(it) })
        browser.offerFileUpload(request)
        browser.pageStarted()
        request.selectFiles(listOf(File("late.rwmod")))
        request.cancel()
        assertEquals(1, delivered)
        assertNull(browser.fileUpload)
    }
}
