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

    @BeforeTest fun setup() {
        directory = Files.createTempDirectory("browser-mod-ui-").toFile()
        i18nTable = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_zh.toml").readText())
    }
    @AfterTest fun cleanup() { directory.deleteRecursively() }

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
