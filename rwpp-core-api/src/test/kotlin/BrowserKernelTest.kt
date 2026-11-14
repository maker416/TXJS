/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.app.UpdateDownloadSession
import io.github.rwpp.app.UpdateDownloadCancelledException
import io.github.rwpp.net.browser.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.AccessDeniedException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class BrowserKernelTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Fixture(entryPath: String = "lib/example.so", expectedPath: String = "lib/example.so", badFileHash: Boolean = false,
        retrySleep: (Long) -> Unit = {}) : AutoCloseable {
        val root = Files.createTempDirectory("browser-kernel-test").toFile()
        val server = MockWebServer()
        val content = ByteArray(180000).also { java.util.Random(42).nextBytes(it) }
        val requests = mutableListOf<String?>()
        var ignoreRange = false
        var badManifest = false
        var oversizedManifest = false
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
        val manager = BrowserKernelManager(root, OkHttpClient(), retrySleep)
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/engine.json") return MockResponse().setBody(when {
                        oversizedManifest -> " ".repeat(2 * 1024 * 1024 + 1)
                        badManifest -> text.replace("\"version\":\"1\"", "\"version\":\"2\"")
                        else -> text
                    })
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
        val manifestFailure = assertFailsWith<BrowserKernelException> { f.manager.install(f.requirement, f.source, UpdateDownloadSession()) }
        assertEquals(BrowserKernelFailureKind.VERIFICATION, manifestFailure.kind)
        assertEquals(BrowserKernelStage.VERIFYING, manifestFailure.stage)
        assertNull(f.manager.installed(f.requirement))
        f.badManifest = false; f.missingPart = true
        val networkFailure = assertFailsWith<BrowserKernelException> { f.manager.install(f.requirement, f.source, UpdateDownloadSession()) }
        assertEquals(BrowserKernelFailureKind.NETWORK, networkFailure.kind)
        assertEquals(BrowserKernelStage.DOWNLOADING, networkFailure.stage)
        assertTrue(networkFailure.systemReason.orEmpty().contains("HTTP 404"))
        assertNull(f.manager.installed(f.requirement))
    }
    @Test fun checksumFailureRemovesCorruptedPart(): Unit = Fixture().use { f ->
        f.corruptPart = true
        val failure = assertFailsWith<BrowserKernelException> { f.manager.install(f.requirement, f.source, UpdateDownloadSession()) }
        assertEquals(BrowserKernelFailureKind.VERIFICATION, failure.kind)
        assertEquals(BrowserKernelStage.VERIFYING, failure.stage)
        assertFalse(File(f.root, ".${f.manifest.id}.download/part-0").exists())
        assertNull(f.manager.installed(f.requirement))
    }
    @Test fun oversizedInstallationManifestIsVerificationFailureBeforeAnyPartDownload(): Unit = Fixture().use { f ->
        f.oversizedManifest = true
        val failure = assertFailsWith<BrowserKernelException> {
            f.manager.install(f.requirement, f.source, UpdateDownloadSession())
        }
        assertEquals(BrowserKernelFailureKind.VERIFICATION, failure.kind)
        assertEquals(BrowserKernelStage.VERIFYING, failure.stage)
        assertEquals("Kernel metadata too large", failure.systemReason)
        assertTrue(f.requests.isEmpty())
        assertNull(f.manager.installed(f.requirement))
        assertFalse(File(f.root, ".${f.manifest.id}.staging").exists())
    }
    @Test fun cancellationLeavesNoReadyKernelAndCanResume(): Unit = Fixture().use { f ->
        val session = UpdateDownloadSession()
        assertFailsWith<UpdateDownloadCancelledException> { f.manager.install(f.requirement, f.source, session) { if (it.stage == BrowserKernelStage.DOWNLOADING) session.cancel() } }
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
            val failure = assertFailsWith<BrowserKernelException> { f.manager.install(f.requirement, f.source, UpdateDownloadSession()) }
            assertEquals(BrowserKernelFailureKind.VERIFICATION, failure.kind)
            assertFalse(File(f.root, "escaped-kernel-test").exists()); assertNull(f.manager.installed(f.requirement))
        }
        Fixture(badFileHash = true).use { f ->
            val failure = assertFailsWith<BrowserKernelException> { f.manager.install(f.requirement, f.source, UpdateDownloadSession()) }
            assertEquals(BrowserKernelFailureKind.VERIFICATION, failure.kind)
            assertEquals(File(f.root, ".${f.manifest.id}.staging/lib/example.so").absolutePath, failure.path)
            assertNull(f.manager.installed(f.requirement))
        }
    }
    @Test fun refusesUnexpectedExistingOutputAndRetainsVerifiedDownloadForRetry(): Unit = Fixture().use { f ->
        val destination = File(f.root, ".${f.manifest.id}.staging/lib/example.so")
        var retryCount = 0
        val failure = assertFailsWith<BrowserKernelException> {
            f.manager.install(f.requirement, f.source, UpdateDownloadSession()) { progress ->
                if (progress.stage == BrowserKernelStage.RETRYING) retryCount++
                if (progress.stage == BrowserKernelStage.INSTALLING) {
                    destination.parentFile.mkdirs()
                    destination.writeText("unexpected existing file")
                }
            }
        }
        assertEquals(BrowserKernelFailureKind.FILE_ACCESS, failure.kind)
        assertEquals(BrowserKernelStage.INSTALLING, failure.stage)
        assertEquals(destination.absolutePath, failure.path)
        assertIs<FileAlreadyExistsException>(failure.cause)
        assertEquals(0, retryCount)
        assertEquals(true, assertNotNull(failure.fileState).exists)
        assertEquals(false, failure.fileState?.directory)
        assertNull(f.manager.installed(f.requirement))
        assertFalse(destination.exists()) // Cleanup ran, but the diagnostic retained its pre-cleanup state.
        assertTrue(File(f.root, ".${f.manifest.id}.download/part-0").isFile)
        assertEquals(1, f.requests.size)
        f.manager.install(f.requirement, f.source, UpdateDownloadSession())
        assertNotNull(f.manager.installed(f.requirement))
        assertEquals(1, f.requests.size) // A local installation failure does not discard a verified download.
    }
    @Test fun temporaryAccessDenialRetriesAfterCleanupAndReusesVerifiedPackage() {
        val waits = mutableListOf<Long>()
        Fixture(retrySleep = { waits += it }).use { f ->
            val staging = File(f.root, ".${f.manifest.id}.staging")
            val partial = File(staging, "partial-output")
            val retryProgress = mutableListOf<BrowserKernelProgress>()
            var attempts = 0
            f.manager.install(f.requirement, f.source, UpdateDownloadSession()) { progress ->
                when (progress.stage) {
                    BrowserKernelStage.INSTALLING -> {
                        attempts++
                        assertFalse(partial.exists())
                        if (attempts == 1) {
                            partial.writeText("incomplete output")
                            throw BrowserKernelException.from(AccessDeniedException(File(staging, "lib/example.so").absolutePath), BrowserKernelStage.INSTALLING)
                        }
                    }
                    BrowserKernelStage.RETRYING -> {
                        retryProgress += progress
                        assertFalse(staging.exists())
                        assertTrue(File(f.root, ".${f.manifest.id}.download/part-0").isFile)
                    }
                    else -> Unit
                }
            }
            assertEquals(2, attempts)
            assertEquals(listOf(1), retryProgress.map { it.retryAttempt })
            assertEquals(listOf(3), retryProgress.map { it.maxRetries })
            assertEquals(listOf(1_000L), retryProgress.map { it.retryDelayMillis })
            assertEquals(1_000L, waits.sum())
            assertTrue(waits.all { it in 1L..100L })
            assertEquals(1, f.requests.size)
            assertNotNull(f.manager.installed(f.requirement))
            assertFalse(partial.exists())
        }
    }
    @Test fun persistentAccessDenialStopsAfterThreeExtraAttemptsAndPreservesEarlierFailures() {
        val waits = mutableListOf<Long>()
        Fixture(retrySleep = { waits += it }).use { f ->
            val failures = mutableListOf<BrowserKernelException>()
            val retryProgress = mutableListOf<BrowserKernelProgress>()
            val failure = assertFailsWith<BrowserKernelException> {
                f.manager.install(f.requirement, f.source, UpdateDownloadSession()) { progress ->
                    if (progress.stage == BrowserKernelStage.INSTALLING) {
                        val denied = BrowserKernelException.from(AccessDeniedException("native-${failures.size}"), BrowserKernelStage.INSTALLING)
                        failures += denied
                        throw denied
                    }
                    if (progress.stage == BrowserKernelStage.RETRYING) retryProgress += progress
                }
            }
            assertEquals(4, failures.size)
            assertSame(failures.last(), failure)
            assertEquals(failures.take(3), failure.suppressed.toList())
            assertEquals(listOf(1, 2, 3), retryProgress.map { it.retryAttempt })
            assertEquals(listOf(1_000L, 2_000L, 4_000L), retryProgress.map { it.retryDelayMillis })
            assertEquals(7_000L, waits.sum())
            assertTrue(waits.all { it in 1L..100L })
            assertEquals(1, f.requests.size)
            assertNull(f.manager.installed(f.requirement))
            assertFalse(File(f.root, ".${f.manifest.id}.staging").exists())
        }
    }
    @Test fun cancellationDuringRetryWaitStopsBeforeAnotherAttemptAndKeepsVerifiedPackage() {
        val session = UpdateDownloadSession()
        var waitSlices = 0
        Fixture(retrySleep = { waitSlices++; session.cancel() }).use { f ->
            var attempts = 0
            assertFailsWith<UpdateDownloadCancelledException> {
                f.manager.install(f.requirement, f.source, session) { progress ->
                    if (progress.stage == BrowserKernelStage.INSTALLING) {
                        attempts++
                        throw BrowserKernelException.from(AccessDeniedException("native-file"), BrowserKernelStage.INSTALLING)
                    }
                }
            }
            assertEquals(1, attempts)
            assertEquals(1, waitSlices)
            assertNull(f.manager.installed(f.requirement))
            assertTrue(File(f.root, ".${f.manifest.id}.download/part-0").isFile)
            assertFalse(File(f.root, ".${f.manifest.id}.staging").exists())
        }
    }
    @Test fun verificationFailureDoesNotUseAccessDenialRetry() = Fixture(badFileHash = true).use { f ->
        var retries = 0
        var attempts = 0
        val failure = assertFailsWith<BrowserKernelException> {
            f.manager.install(f.requirement, f.source, UpdateDownloadSession()) { progress ->
                if (progress.stage == BrowserKernelStage.RETRYING) retries++
                if (progress.stage == BrowserKernelStage.INSTALLING) attempts++
            }
        }
        assertEquals(BrowserKernelFailureKind.VERIFICATION, failure.kind)
        assertEquals(1, attempts)
        assertEquals(0, retries)
    }
    @Test fun laterVerificationFailureRetainsTheAccessDenialThatTriggeredRetry() = Fixture().use { f ->
        val denied = BrowserKernelException.from(AccessDeniedException("native-file"), BrowserKernelStage.INSTALLING)
        val failure = assertFailsWith<BrowserKernelException> {
            f.manager.install(f.requirement, f.source, UpdateDownloadSession()) { progress ->
                if (progress.stage == BrowserKernelStage.INSTALLING) throw denied
                if (progress.stage == BrowserKernelStage.RETRYING) f.badManifest = true
            }
        }
        assertEquals(BrowserKernelFailureKind.VERIFICATION, failure.kind)
        assertSame(denied, failure.suppressed.single())
        assertEquals(1, f.requests.size)
    }
    @Test fun anotherInstallerCanCompleteWhileRetryWaitsAndTheNextAttemptReusesItsCommit() {
        var installDuringWait: (() -> Unit)? = null
        Fixture(retrySleep = {
            val install = installDuringWait
            installDuringWait = null
            install?.invoke()
        }).use { f ->
            val concurrent = BrowserKernelManager(f.root, OkHttpClient(), {})
            installDuringWait = { concurrent.install(f.requirement, f.source, UpdateDownloadSession()) }
            var extractionAttempts = 0
            val result = f.manager.install(f.requirement, f.source, UpdateDownloadSession()) { progress ->
                if (progress.stage == BrowserKernelStage.INSTALLING) {
                    extractionAttempts++
                    throw BrowserKernelException.from(AccessDeniedException("native-file"), BrowserKernelStage.INSTALLING)
                }
            }
            assertEquals(1, extractionAttempts)
            assertEquals(f.manager.installed(f.requirement), result)
            assertEquals(1, f.requests.size)
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
