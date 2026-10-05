/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.app

import io.github.rwpp.net.UpdateDownloadPlan
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class UpdatePackageDownloaderTest {
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        } }
    }.toByteArray()
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun exercise(block: (MockWebServer, File, UpdatePackageDownloader) -> Unit) {
        val work = Files.createTempDirectory("update-test-").toFile()
        MockWebServer().use { server ->
            server.start()
            try { block(server, work, UpdatePackageDownloader(OkHttpClient())) }
            finally { work.deleteRecursively() }
        }
    }

    @Test fun `arbitrary zip boundaries preserve apk bytes and report all phases`() = exercise { server, dir, downloader ->
        val apk = ByteArray(130000) { (it * 31).toByte() }
        val zip = archive("RWJS-Android.apk" to apk)
        val parts = zip.toList().chunked(47).map { it.toByteArray() }
        parts.forEach { server.enqueue(MockResponse().setBody(Buffer().write(it))) }
        server.enqueue(MockResponse().setBody(hash(zip) + "  RWJS-Android.zip"))
        val stages = mutableListOf<UpdateStage>()
        val plan = UpdateDownloadPlan(parts.indices.map { server.url("/$it").toString() }, server.url("/sha").toString(), true,
            parts.map { it.size.toLong() })
        val output = downloader.download(plan, UpdateDownloadSession(), dir, File(dir, "output.apk"), ".apk") { stages += it.stage }
        assertContentEquals(apk, output.readBytes())
        assertTrue(stages.containsAll(listOf(UpdateStage.DOWNLOADING, UpdateStage.VERIFYING, UpdateStage.EXTRACTING)))
        assertFalse(File(dir, "merged.zip").exists())
    }

    @Test fun `sha mismatch fails before extracting installer`() = exercise { server, dir, downloader ->
        server.enqueue(MockResponse().setBody(Buffer().write(archive("app.apk" to byteArrayOf(1,2,3)))))
        server.enqueue(MockResponse().setBody("0".repeat(64)))
        val output = File(dir, "out.apk")
        assertFailsWith<java.io.IOException> {
            downloader.download(UpdateDownloadPlan(listOf(server.url("/zip").toString()), server.url("/sha").toString(), true),
                UpdateDownloadSession(), dir, output, ".apk") {}
        }
        assertFalse(output.exists())
    }

    @Test fun `wrong file type cannot become an apk`() = exercise { server, dir, downloader ->
        server.enqueue(MockResponse().setBody(Buffer().write(archive("setup.exe" to byteArrayOf(1)))))
        assertFailsWith<java.io.IOException> {
            downloader.download(UpdateDownloadPlan(listOf(server.url("/zip").toString()), isArchive = true),
                UpdateDownloadSession(), dir, File(dir, "out.apk"), ".apk") {}
        }
    }

    @Test fun `ambiguous archives are rejected`() = exercise { server, dir, downloader ->
        server.enqueue(MockResponse().setBody(Buffer().write(archive("one.apk" to byteArrayOf(1), "two.apk" to byteArrayOf(2)))))
        assertFailsWith<java.io.IOException> {
            downloader.download(UpdateDownloadPlan(listOf(server.url("/zip").toString()), isArchive = true),
                UpdateDownloadSession(), dir, File(dir, "out.apk"), ".apk") {}
        }
    }

    @Test fun `raw legacy installer still works and metadata catches truncation`() = exercise { server, dir, downloader ->
        server.enqueue(MockResponse().setBody("legacy apk"))
        val plan = UpdateDownloadPlan(listOf(server.url("/apk").toString()), isArchive = false)
        assertEquals("legacy apk", downloader.download(plan, UpdateDownloadSession(), dir, File(dir, "out.apk"), ".apk") {}.readText())
        server.enqueue(MockResponse().setBody("short"))
        assertFailsWith<java.io.IOException> {
            downloader.download(plan.copy(partSizes = listOf(10)), UpdateDownloadSession(), dir, File(dir, "bad.apk"), ".apk") {}
        }
    }

    @Test fun `cancel during progress stops before verification and installation`() = exercise { server, dir, downloader ->
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(100000))))
        val session = UpdateDownloadSession()
        assertFailsWith<UpdateDownloadCancelledException> {
            downloader.download(UpdateDownloadPlan(listOf(server.url("/apk").toString())), session, dir, File(dir, "out.apk"), ".apk") {
                session.cancel()
            }
        }
        var installed = false
        assertFalse(session.startInstallation { installed = true })
        assertFalse(installed)
    }
}
