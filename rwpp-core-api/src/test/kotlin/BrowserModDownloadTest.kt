/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.BrowserModHttpTransfer
import io.github.rwpp.net.browser.BrowserModFiles
import io.github.rwpp.net.browser.browserModFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserModDownloadTest {
    @Test fun responseNamesTakePriorityAndUrlQueriesDoNotHideExtension() {
        assertEquals("中文模组.rwmod", browserModFileName("https://example.com/download?id=7",
            "attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87%E6%A8%A1%E7%BB%84.rwmod"))
        assertEquals("test.rwmod", browserModFileName("https://example.com/test.RWMOD?token=secret"))
        assertEquals("a+b.rwmod", browserModFileName("", "attachment; filename*=UTF-8''a+b.rwmod"))
        assertEquals("quoted;name.rwmod", browserModFileName("", "attachment; filename=\"quoted;name.rwmod\""))
        assertNull(browserModFileName("https://example.com/looks.rwmod", "attachment; filename=actual.png"))
        assertNull(browserModFileName("https://example.com/file.zip?name=mod.rwmod"))
        assertEquals("generated.rwmod", browserModFileName("blob:https://example.com/id", suggestedName = "generated.rwmod"))
    }

    @Test fun remoteNamesCannotEscapeDirectoryOrUseWindowsReservedNames() {
        assertEquals("evil.rwmod", browserModFileName("", suggestedName = "../../evil.rwmod"))
        assertEquals("evil.rwmod", browserModFileName("", suggestedName = "C:\\tmp\\evil.rwmod"))
        assertEquals("_CON.rwmod", browserModFileName("", suggestedName = "CON.rwmod"))
        val shortened = browserModFileName("", suggestedName = "🙂".repeat(100) + ".rwmod")!!
        assertTrue(shortened.toByteArray(Charsets.UTF_8).size <= 186)
        assertTrue(shortened.endsWith(".rwmod"))
    }

    @Test fun installingDuplicatesKeepsOriginalAndNeverExposesPartialAsMod() = runBlocking { inDirectory { directory ->
        val original = File(directory, "example.rwmod").apply { writeText("original contents") }
        val partial = BrowserModFiles.createPartial(directory)
        assertEquals("part", partial.extension)
        val bytes = archive()
        partial.writeBytes(bytes)
        val installed = BrowserModFiles.install(partial, directory, "example.rwmod")
        assertEquals("example (2).rwmod", installed.name)
        assertEquals("original contents", original.readText())
        assertContentEquals(bytes, installed.readBytes())
        assertTrue(!partial.exists())
        val again = BrowserModFiles.createPartial(directory).apply { writeBytes(bytes) }
        assertEquals("example (3).rwmod", BrowserModFiles.install(again, directory, "example.rwmod").name)
    } }

    @Test fun invalidOrTruncatedArchivesDoNotBecomeInstalledMods() = runBlocking { inDirectory { directory ->
        val partial = BrowserModFiles.createPartial(directory).apply { writeText("<html>login needed</html>") }
        assertFailsWith<IOException> { BrowserModFiles.install(partial, directory, "fake.rwmod") }
        partial.writeBytes(archive().dropLast(24).toByteArray())
        assertFailsWith<IOException> { BrowserModFiles.install(partial, directory, "fake.rwmod") }
        assertTrue(directory.listFiles()!!.none { it.extension == "rwmod" })
        assertFailsWith<IllegalArgumentException> { BrowserModFiles.install(partial, directory, "../escape.rwmod") }
        Unit
    } }

    @Test fun httpTransferUsesBrowserHeadersAndReevaluatesCookiesOnRedirect() = runBlocking {
        inDirectory { directory ->
            MockWebServer().use { source -> MockWebServer().use { destination ->
                val bytes = archive()
                source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/payload")))
                destination.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                val transfer = BrowserModHttpTransfer(OkHttpClient(), source.url("/download").toString(),
                    "browser-user-agent", "https://resources.example/page") { url ->
                    if (url.startsWith(source.url("/").toString())) "session=source-only" else null
                }
                val partial = BrowserModFiles.createPartial(directory)
                val progress = mutableListOf<Pair<Long, Long?>>()
                transfer.download(partial) { received, total -> progress += received to total }
                assertContentEquals(bytes, partial.readBytes())
                assertEquals(bytes.size.toLong(), progress.last().first)
                assertEquals(bytes.size.toLong(), progress.last().second)
                assertTrue(progress.zipWithNext().all { (a, b) -> a.first <= b.first })
                val initial = source.takeRequest()
                val final = destination.takeRequest()
                assertEquals("session=source-only", initial.getHeader("Cookie"))
                assertNull(final.getHeader("Cookie"))
                assertEquals("browser-user-agent", final.getHeader("User-Agent"))
                assertEquals("https://resources.example/page", final.getHeader("Referer"))
            } }
        }
    }

    @Test fun httpFailureAndCancellationCannotInstallAnything() = runBlocking {
        inDirectory { directory ->
            MockWebServer().use { server ->
                val partial = BrowserModFiles.createPartial(directory)
                server.enqueue(MockResponse().setResponseCode(403).setBody("Access denied"))
                val rejected = BrowserModHttpTransfer(OkHttpClient(), server.url("/denied").toString(), null, null)
                assertFailsWith<IOException> { rejected.download(partial) { _, _ -> } }
                assertEquals(0L, partial.length())
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val slow = BrowserModHttpTransfer(OkHttpClient(), server.url("/slow").toString(), null, null)
                val active = async(Dispatchers.IO) { slow.download(partial) { _, _ -> } }
                server.takeRequest()
                server.takeRequest(2, TimeUnit.SECONDS) ?: error("Slow request was not started")
                slow.cancel()
                withTimeout(2000) { active.join() }
                assertTrue(active.isCancelled)
                assertTrue(directory.listFiles()!!.none { it.extension == "rwmod" })
            }
        }
    }

    private suspend fun <T> inDirectory(block: suspend (File) -> T): T {
        val directory = Files.createTempDirectory("browser-mod-test-").toFile()
        return try { block(directory) } finally { directory.deleteRecursively() }
    }

    private fun archive(): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("mod-info.txt"))
            zip.write("[mod]\ntitle: Browser test\n".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
}
