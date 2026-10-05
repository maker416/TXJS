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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.rwpp.i18n.i18nTable
import io.github.rwpp.net.browser.BrowserModDownload
import io.github.rwpp.net.browser.BrowserModTransfer
import io.github.rwpp.platform.EmbeddedBrowserState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.peanuuutz.tomlkt.Toml
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BrowserModDownloadUiTest {
    private lateinit var directory: File
    private lateinit var mapDirectory: File

    @BeforeTest fun setup() {
        directory = Files.createTempDirectory("browser-mod-ui-").toFile()
        mapDirectory = Files.createTempDirectory("browser-map-ui-").toFile()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
    }
    @AfterTest fun cleanup() { directory.deleteRecursively(); mapDirectory.deleteRecursively() }

    @Test fun confirmationPrecedesTransferAndProgressEndsWithInstalledMod() = runDesktopComposeUiTest(width = 360, height = 720) {
        val transfer = ControlledTransfer(archive())
        val browser = EmbeddedBrowserState("https://resources.example")
        browser.offerModDownload(BrowserModDownload("example.rwmod", 1000, transfer))
        setContent { MaterialTheme { BrowserModDownloadDialog(browser) { directory } } }
        onNodeWithText("下载并安装模组").assertIsDisplayed()
        runOnIdle { assertFalse(transfer.started.get()); assertTrue(directory.listFiles()!!.isEmpty()) }
        onNodeWithText("下载并安装").performClick()
        waitUntil(timeoutMillis = 5000) { transfer.started.get() }
        onNodeWithText("正在下载模组").assertIsDisplayed()
        onNodeWithText("10% · 100 B / 1000 B").assertIsDisplayed()
        runOnIdle { transfer.release.complete(Unit) }
        waitUntil(timeoutMillis = 5000) { File(directory, "example.rwmod").exists() }
        onNodeWithText("模组安装完成").assertIsDisplayed()
        runOnIdle { assertTrue(directory.listFiles()!!.none { it.extension == "part" }) }
        onNodeWithText("确定").performClick()
        runOnIdle { assertEquals(null, browser.modDownload) }
    }

    @Test fun rejectingAndCancellingKeepExistingModsAndRemovePartialFiles() = runDesktopComposeUiTest {
        val original = File(directory, "existing.rwmod").apply { writeText("keep existing") }
        val transfer = ControlledTransfer(archive())
        val browser = EmbeddedBrowserState("https://resources.example")
        browser.offerModDownload(BrowserModDownload("example.rwmod", null, transfer))
        setContent { MaterialTheme { BrowserModDownloadDialog(browser) { directory } } }
        onNodeWithText("取消").performClick()
        runOnIdle { assertFalse(transfer.started.get()); assertTrue(transfer.cancelled.get()) }
        val second = ControlledTransfer(archive())
        runOnIdle { browser.offerModDownload(BrowserModDownload("example.rwmod", null, second)) }
        onNodeWithText("下载并安装").performClick()
        waitUntil(timeoutMillis = 5000) { second.started.get() }
        onNodeWithText("取消").performClick()
        waitUntil(timeoutMillis = 5000) { directory.listFiles()!!.none { it.extension == "part" } }
        runOnIdle {
            assertEquals("keep existing", original.readText())
            assertEquals(listOf("existing.rwmod"), directory.list()!!.toList())
            assertEquals(null, browser.modDownload)
        }
    }

    @Test fun htmlNamedAsModShowsFailureAndDoesNotInstall() = runDesktopComposeUiTest {
        val transfer = ControlledTransfer("<html>login required</html>".toByteArray())
        val browser = EmbeddedBrowserState("https://resources.example")
        browser.offerModDownload(BrowserModDownload("fake.rwmod", null, transfer))
        setContent { MaterialTheme { BrowserModDownloadDialog(browser) { directory } } }
        onNodeWithText("下载并安装").performClick()
        runOnIdle { transfer.release.complete(Unit) }
        waitUntil(timeoutMillis = 5000) { transfer.started.get() && directory.listFiles()!!.isEmpty() }
        onNodeWithText("模组下载或安装失败").assertIsDisplayed()
        runOnIdle { assertFalse(File(directory, "fake.rwmod").exists()) }
    }

    @Test fun tmxIsConfirmedAndSavedToMapsInsteadOfMods() = runDesktopComposeUiTest(width = 360, height = 720) {
        val transfer = ControlledTransfer("<map width=\"1\" height=\"1\"/>".toByteArray())
        val browser = EmbeddedBrowserState("https://resources.example")
        browser.offerModDownload(BrowserModDownload("example.tmx", null, transfer))
        var refreshes = 0
        setContent { MaterialTheme { BrowserModDownloadDialog(browser, onMapsInstalled = { refreshes++ },
            mapDirectory = { mapDirectory }, directory = { directory }) } }
        onNodeWithText("下载并安装地图").assertIsDisplayed()
        runOnIdle { assertFalse(transfer.started.get()) }
        onNodeWithText("下载并安装").performClick()
        runOnIdle { transfer.release.complete(Unit) }
        waitUntil(timeoutMillis = 5000) { File(mapDirectory, "example.tmx").exists() }
        onNodeWithText("地图安装完成").assertIsDisplayed()
        onNodeWithText("已保存到地图目录，可在自定义地图中选择。").assertIsDisplayed()
        runOnIdle { assertTrue(directory.listFiles()!!.isEmpty()); assertEquals(1, refreshes) }
    }

    @Test fun zipMapChoiceExplainsExtractionAndPreservesPackFiles() = runDesktopComposeUiTest(width = 360, height = 720) {
        val transfer = ControlledTransfer(mapArchive())
        val browser = EmbeddedBrowserState("https://resources.example")
        browser.offerModDownload(BrowserModDownload("maps.zip", null, transfer))
        setContent { MaterialTheme { BrowserModDownloadDialog(browser, mapDirectory = { mapDirectory }, directory = { directory }) } }
        onNodeWithText("这个 ZIP 是什么资源？").assertIsDisplayed()
        onNodeWithText("地图包").performClick()
        onNodeWithText("会帮你自动解压此地图包，并放入地图文件夹，保留目录结构与配套文件。已有地图不会被覆盖。").assertIsDisplayed()
        runOnIdle { assertFalse(transfer.started.get()) }
        onNodeWithText("下载并解压").performClick()
        runOnIdle { transfer.release.complete(Unit) }
        waitUntil(timeoutMillis = 5000) { File(mapDirectory, "maps/folder/map.tmx").exists() }
        onNodeWithText("已解压到地图目录，共 1 张地图，可在自定义地图中选择。").assertIsDisplayed()
        runOnIdle {
            assertEquals("preview", File(mapDirectory, "maps/folder/map_map.png").readText())
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun completeModZipRequiresTwoChoicesAndIsSavedAsRwmod() = runDesktopComposeUiTest(width = 360, height = 720) {
        val transfer = ControlledTransfer(archive())
        val browser = EmbeddedBrowserState("https://resources.example")
        browser.offerModDownload(BrowserModDownload("example.zip", null, transfer))
        setContent { MaterialTheme { BrowserModDownloadDialog(browser, mapDirectory = { mapDirectory }, directory = { directory }) } }
        onNodeWithText("模组").performClick()
        onNodeWithText("这是一个完整的模组吗？").assertIsDisplayed()
        runOnIdle { assertFalse(transfer.started.get()) }
        onNodeWithText("是，完整模组").performClick()
        runOnIdle { transfer.release.complete(Unit) }
        waitUntil(timeoutMillis = 5000) { File(directory, "example.rwmod").exists() }
        onNodeWithText("模组安装完成").assertIsDisplayed()
        runOnIdle { assertTrue(mapDirectory.listFiles()!!.isEmpty()) }
    }

    @Test fun modCollectionIsRefusedBeforeAnyTransfer() = runDesktopComposeUiTest(width = 360, height = 720) {
        val transfer = ControlledTransfer(archive())
        val browser = EmbeddedBrowserState("https://resources.example")
        browser.offerModDownload(BrowserModDownload("collection.zip", null, transfer))
        setContent { MaterialTheme { BrowserModDownloadDialog(browser, mapDirectory = { mapDirectory }, directory = { directory }) } }
        onNodeWithText("模组").performClick()
        onNodeWithText("这是模组整合包").performClick()
        onNodeWithText("暂不支持模组整合包").assertIsDisplayed()
        onNodeWithText("目前还不支持模组整合包。请分别下载各个模组的 .rwmod 文件。").assertIsDisplayed()
        runOnIdle {
            assertFalse(transfer.started.get())
            assertTrue(transfer.cancelled.get())
            assertTrue(directory.listFiles()!!.isEmpty())
            assertTrue(mapDirectory.listFiles()!!.isEmpty())
        }
        onNodeWithText("确定").performClick()
        runOnIdle { assertEquals(null, browser.modDownload) }
    }

    @Test fun mapDownloadCancellationRemovesPartialFiles() = runDesktopComposeUiTest {
        val transfer = ControlledTransfer("<map/>".toByteArray())
        val browser = EmbeddedBrowserState("https://resources.example")
        browser.offerModDownload(BrowserModDownload("map.tmx", null, transfer))
        setContent { MaterialTheme { BrowserModDownloadDialog(browser, mapDirectory = { mapDirectory }, directory = { directory }) } }
        onNodeWithText("下载并安装").performClick()
        waitUntil(timeoutMillis = 5000) { transfer.started.get() }
        onNodeWithText("取消").performClick()
        waitUntil(timeoutMillis = 5000) { mapDirectory.listFiles()!!.isEmpty() }
        runOnIdle { assertTrue(transfer.cancelled.get()); assertEquals(null, browser.modDownload) }
    }

    @Test fun zipChoicesRemainReachableInALandscapeWindow() = runDesktopComposeUiTest(width = 720, height = 360) {
        val transfer = ControlledTransfer(archive())
        val browser = EmbeddedBrowserState("https://resources.example")
        browser.offerModDownload(BrowserModDownload("中文名称.zip", null, transfer))
        setContent { MaterialTheme { BrowserModDownloadDialog(browser) { directory } } }
        onNodeWithText("模组").performScrollTo().performClick()
        onNodeWithText("这是一个完整的模组吗？").performScrollTo().assertIsDisplayed()
        onNodeWithText("这是模组整合包").performScrollTo().performClick()
        onNodeWithText("暂不支持模组整合包").performScrollTo().assertIsDisplayed()
        onNodeWithText("确定").performScrollTo().performClick()
        runOnIdle { assertEquals(null, browser.modDownload); assertFalse(transfer.started.get()) }
    }

    private fun mapArchive(): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, content) in listOf("folder/map.tmx" to "<map/>", "folder/map_map.png" to "preview")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray()

    private class ControlledTransfer(private val bytes: ByteArray) : BrowserModTransfer {
        val started = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val release = CompletableDeferred<Unit>()
        override suspend fun download(target: File, onProgress: (Long, Long?) -> Unit) {
            withContext(Dispatchers.IO) { target.writeBytes(bytes) }
            onProgress(100, 1000)
            started.set(true)
            release.await()
        }
        override fun cancel() { cancelled.set(true) }
    }

    private fun archive(): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("mod-info.txt"))
            zip.write("[mod]\ntitle: Browser UI test\n".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
}
