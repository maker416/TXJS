/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.app.UpdateDownloadSession
import io.github.rwpp.net.browser.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class BrowserKernelTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Fixture(entryPath: String = "lib/example.so", expectedPath: String = "lib/example.so", badFileHash: Boolean = false) : AutoCloseable {
        val root = Files.createTempDirectory("browser-kernel-test").toFile()
        val server = MockWebServer()
        val content = ByteArray(180000).also { java.util.Random(42).nextBytes(it) }
        val requests = mutableListOf<String?>()
        var ignoreRange = false
        var badManifest = false
        var missingPart = false
        var corruptPart = false
        var disconnectOnce = false
        var rangeChunkSize = Int.MAX_VALUE
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry(entryPath)); z.write(content); z.closeEntry() } }.toByteArray()
        private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        val manifest = BrowserKernelManifest(1, "gecko-1-v1-android-arm64-v8a", "gecko", "1", "android", "arm64-v8a", "v1",
            sha(bytes), listOf(BrowserKernelPart("engine.kernel.001", bytes.size.toLong(), sha(bytes))),
            listOf(BrowserKernelFile(expectedPath, content.size.toLong(), if (badFileHash) "0".repeat(64) else sha(content))))
        val text = Json.encodeToString(manifest)
        val requirement = BrowserKernelRequirement.read(buildJsonObject {
            put("id", manifest.id); put("releaseTag", "v1"); put("manifest", "engine.json"); put("manifestSha256", sha(text.toByteArray()))
            put("embeddedManifest", Json.parseToJsonElement(text))
        }.toString())
        val source = BrowserKernelSource { _, name, _ -> server.url("/$name").toString() }
        val manager = BrowserKernelManager(root, OkHttpClient())
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/engine.json") return MockResponse().setBody(if (badManifest) text.replace("\"version\":\"1\"", "\"version\":\"2\"") else text)
                    if (missingPart) return MockResponse().setResponseCode(404)
                    val range = request.getHeader("Range"); synchronized(requests) { requests.add(range) }
                    val start = if (ignoreRange) 0 else range?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
                    val end = if (start > 0) minOf(bytes.size, start + minOf(rangeChunkSize, bytes.size - start)) else bytes.size
                    val payload = bytes.copyOfRange(start, end).also { if (corruptPart) it[0] = (it[0].toInt() xor 1).toByte() }
                    return MockResponse().setBody(Buffer().write(payload)).apply {
                        if (start > 0) setResponseCode(206).setHeader("Content-Range", "bytes $start-${end - 1}/${bytes.size}")
                        if (disconnectOnce) { disconnectOnce = false; setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
                    }
                }
            }
            server.start()
        }
        fun prefix() = File(root, ".${manifest.id}.download/part-0").apply { parentFile.mkdirs(); writeBytes(bytes.copyOfRange(0, 40000)) }
        override fun close() { server.shutdown(); root.deleteRecursively() }
    }
    @Test fun installsAndReusesOfflineAndRepairsCorruptFiles(): Unit = Fixture().use { f ->
        val directory = f.manager.install(f.requirement, f.source, UpdateDownloadSession())
        assertContentEquals(f.content, File(directory, "lib/example.so").readBytes())
        val offline = BrowserKernelSource { _, _, _ -> error("Offline reuse must not request release metadata") }
        assertEquals(directory, f.manager.install(f.requirement, offline, UpdateDownloadSession()))
        File(directory, "lib/example.so").writeText("corrupted")
        assertNull(f.manager.installed(f.requirement))
        f.manager.install(f.requirement, f.source, UpdateDownloadSession())
        assertNotNull(f.manager.installed(f.requirement))
    }
    @Test fun resumesVerifiedPackageUsingRange(): Unit = Fixture().use { f ->
        f.prefix(); f.manager.install(f.requirement, f.source, UpdateDownloadSession())
        assertEquals("bytes=40000-", f.requests.single())
    }
    @Test fun restartsPartWhenServerIgnoresRange(): Unit = Fixture().use { f ->
        f.prefix(); f.ignoreRange = true
        f.manager.install(f.requirement, f.source, UpdateDownloadSession())
        assertNotNull(f.manager.installed(f.requirement))
    }
    @Test fun rejectsUnpinnedManifestAndMissingParts(): Unit = Fixture().use { f ->
        f.badManifest = true
        assertFails { f.manager.install(f.requirement, f.source, UpdateDownloadSession()) }
        assertNull(f.manager.installed(f.requirement))
        f.badManifest = false; f.missingPart = true
        assertFails { f.manager.install(f.requirement, f.source, UpdateDownloadSession()) }
        assertNull(f.manager.installed(f.requirement))
    }
    @Test fun checksumFailureRemovesCorruptedPart(): Unit = Fixture().use { f ->
        f.corruptPart = true
        assertFails { f.manager.install(f.requirement, f.source, UpdateDownloadSession()) }
        assertFalse(File(f.root, ".${f.manifest.id}.download/part-0").exists())
        assertNull(f.manager.installed(f.requirement))
    }
    @Test fun cancellationLeavesNoReadyKernelAndCanResume(): Unit = Fixture().use { f ->
        val session = UpdateDownloadSession()
        assertFails { f.manager.install(f.requirement, f.source, session) { if (it.stage == BrowserKernelStage.DOWNLOADING) session.cancel() } }
        assertNull(f.manager.installed(f.requirement))
        assertTrue(File(f.root, ".${f.manifest.id}.download/part-0").isFile)
        f.manager.install(f.requirement, f.source, UpdateDownloadSession())
        assertNotNull(f.manager.installed(f.requirement))
    }
    @Test fun rejectsWrongIdentityDuplicatePartsAndTraversalBeforeDownload(): Unit = Fixture().use { f ->
        assertFails { f.requirement.copy(id = "different").validate() }
        assertFails { f.requirement.copy(manifest = f.manifest.copy(arch = "x86_64")).validate() }
        assertFails { f.requirement.copy(manifest = f.manifest.copy(parts = f.manifest.parts + f.manifest.parts)).validate() }
        for (path in listOf("../outside", "/outside", "lib/../../outside", "C:/outside", "lib\\outside", ".ready")) {
            assertFails { f.requirement.copy(manifest = f.manifest.copy(files = listOf(f.manifest.files.single().copy(path = path)))).validate() }
        }
    }
    @Test fun ignoresAbandonedStagingAndConcurrentInstallersShareResult(): Unit = Fixture().use { f ->
        File(f.root, ".${f.manifest.id}.staging/lib").mkdirs()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { executor.submit<File> { f.manager.install(f.requirement, f.source, UpdateDownloadSession()) } }.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(results[0], results[1]); assertEquals(1, f.requests.size)
            assertFalse(File(f.root, ".${f.manifest.id}.staging").exists())
        } finally { executor.shutdownNow() }
    }
    @Test fun interruptedResponseResumesWithoutDiscardingReceivedBytes(): Unit = Fixture().use { f ->
        f.disconnectOnce = true
        f.manager.install(f.requirement, f.source, UpdateDownloadSession())
        assertTrue(f.requests.drop(1).any { it?.startsWith("bytes=") == true })
        assertNotNull(f.manager.installed(f.requirement))
    }
    @Test fun validCappedRangeResponsesContinueBeyondRetryCount(): Unit = Fixture().use { f ->
        f.prefix(); f.rangeChunkSize = 20000
        f.manager.install(f.requirement, f.source, UpdateDownloadSession())
        assertTrue(f.requests.size > 3); assertNotNull(f.manager.installed(f.requirement))
    }
    @Test fun archiveTraversalUnexpectedFilesAndBadFileHashesNeverBecomeReady() {
        for (entry in listOf("../escaped-kernel-test", "lib/unexpected.so")) Fixture(entryPath = entry).use { f ->
            assertFails { f.manager.install(f.requirement, f.source, UpdateDownloadSession()) }
            assertFalse(File(f.root, "escaped-kernel-test").exists()); assertNull(f.manager.installed(f.requirement))
        }
        Fixture(badFileHash = true).use { f ->
            assertFails { f.manager.install(f.requirement, f.source, UpdateDownloadSession()) }
            assertNull(f.manager.installed(f.requirement))
        }
    }
    @Test fun legacyImportOnlyCopiesVerifiedRuntimeFiles(): Unit = Fixture().use { f ->
        val legacy = File(f.root, "legacy").apply { mkdirs() }
        File(legacy, "lib").mkdirs(); File(legacy, "lib/example.so").writeBytes(f.content)
        File(legacy, "profile-secret").writeText("keep separate")
        val imported = assertNotNull(f.manager.adoptLegacy(f.requirement, legacy))
        assertFalse(File(imported, "profile-secret").exists()); assertTrue(File(legacy, "profile-secret").isFile)
        assertNotNull(f.manager.installed(f.requirement))
    }
}
