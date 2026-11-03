/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync.direct

import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModCacheEntry
import io.github.rwpp.game.mod.NetworkModDescriptor
import io.github.rwpp.io.HashUtils
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class DirectModSyncClientTest {
    private val temporaryDirectories = Collections.synchronizedList(mutableListOf<File>())

    @AfterTest
    fun cleanup() {
        temporaryDirectories.forEach { it.deleteRecursively() }
        temporaryDirectories.clear()
    }

    @Test
    fun readsManifestFromOneShortConnection() = runBlocking {
        val manifest = DirectModManifest(1, "176", "instance-one", "ready", true, -123, emptyList())
        withServer(handler = { socket ->
            assertManifestRequest(socket)
            socket.getOutputStream().apply { write(DirectModSyncProtocol.encodeManifest(manifest)); flush() }
        }) { port ->
            assertEquals(manifest, client(port, RecordingCache(), attempts = 1).getManifest())
        }
    }

    @Test
    fun aValidManifestFollowedByTrailingBytesCannotFallBack() = runBlocking {
        val manifest = DirectModManifest(1, "176", "instance-one", "ready", true, 0, emptyList())
        withServer(handler = { socket ->
            assertManifestRequest(socket)
            socket.getOutputStream().apply { write(DirectModSyncProtocol.encodeManifest(manifest) + 0); flush() }
        }) { port ->
            val error = assertFailsWith<DirectModSyncException> { client(port, RecordingCache(), attempts = 1).getManifest() }
            assertFalse(error is DirectModSyncUnsupportedException)
        }
    }

    @Test
    fun onlyUnrecognizedEndpointsMayFallBack() = runBlocking {
        withServer(handler = { socket ->
            assertManifestRequest(socket)
            DataOutputStream(socket.getOutputStream()).apply { writeInt(0); writeInt(106); flush() }
        }) { port ->
            assertFailsWith<DirectModSyncUnsupportedException> { client(port, RecordingCache(), attempts = 1).getManifest() }
        }

        val manifest = DirectModManifest(1, "176", "instance-one", "ready", true, 0, emptyList())
        var requests = 0
        withServer(connections = 2, handler = { socket ->
            assertManifestRequest(socket)
            if (requests++ == 0) {
                socket.getOutputStream().apply { write(DirectModSyncProtocol.encodeManifest(manifest)); flush() }
            }
        }) { port ->
            val transport = client(port, RecordingCache(), attempts = 1)
            assertEquals(manifest, transport.getManifest())
            val error = assertFailsWith<DirectModSyncException> { transport.getManifest() }
            assertFalse(error is DirectModSyncUnsupportedException)
        }
    }

    @Test
    fun manifestServerErrorsRemainAuthoritativeOnLaterRequests() = runBlocking {
        var requests = 0
        withServer(connections = 2, handler = { socket ->
            assertManifestRequest(socket)
            if (requests++ == 0) {
                socket.getOutputStream().apply { write(DirectModSyncProtocol.encodeError(DirectModSyncErrorCode.PREPARING)); flush() }
            }
        }) { port ->
            val transport = client(port, RecordingCache(), attempts = 1)
            val serverError = assertFailsWith<DirectModSyncServerException> { transport.getManifest() }
            assertEquals(DirectModSyncErrorCode.PREPARING, serverError.code)
            val error = assertFailsWith<DirectModSyncException> { transport.getManifest() }
            assertFalse(error is DirectModSyncUnsupportedException)
        }
    }

    @Test
    fun v2NegotiationReservesRenewsAndReleasesWithTheSameSecret() = runBlocking {
        val manifest = DirectModManifest(2, "176", "instance-v2", "ready", true, 123, emptyList())
        val token = ByteArray(32) { (it * 7).toByte() }
        var requests = 0
        withServer(connections = 4, handler = { socket ->
            if (requests++ == 0) {
                assertManifestRequest(socket, version = 2)
                socket.getOutputStream().apply { write(DirectModSyncProtocol.encodeManifest(manifest)); flush() }
            } else {
                val expectedKind = when (requests) { 2 -> DirectModSyncProtocol.RESERVE; 3 -> DirectModSyncProtocol.RENEW; else -> DirectModSyncProtocol.RELEASE }
                val input = DataInputStream(socket.getInputStream())
                assertContentEquals(DirectModSyncProtocol.MAGIC_V2, input.readNBytes(8))
                assertEquals(4 + manifest.scope.toByteArray().size + 32, input.readInt())
                assertEquals(expectedKind, input.readInt())
                assertEquals(manifest.scope, input.readProtocolString())
                assertContentEquals(token, input.readNBytes(32))
                val response = if (expectedKind == DirectModSyncProtocol.RELEASE) DirectModSyncProtocol.encodeReleased()
                    else DirectModSyncProtocol.encodeLease(DirectModLease(manifest.scope, 45_000, 15_000,
                        if (expectedKind == DirectModSyncProtocol.RENEW) "admitted" else "reserved", 2, 10, 1))
                socket.getOutputStream().apply { write(response); flush() }
            }
        }) { port ->
            val transport = client(port, RecordingCache(), attempts = 1, version = null)
            assertEquals(manifest, transport.getManifest())
            assertEquals("reserved", transport.reserve(manifest.scope, token).state)
            assertEquals("admitted", transport.renew(manifest.scope, token).state)
            transport.release(manifest.scope, token)
        }
    }

    @Test
    fun autoNegotiationUsesV1OnlyAfterUnrecognizedV2Probe() = runBlocking {
        val manifest = DirectModManifest(1, "176", "legacy", "ready", true, 0, emptyList())
        var requests = 0
        withServer(connections = 2, handler = { socket ->
            if (requests++ == 0) {
                assertManifestRequest(socket, version = 2)
                DataOutputStream(socket.getOutputStream()).apply { writeInt(0); writeInt(106); flush() }
            } else {
                assertManifestRequest(socket, version = 1)
                socket.getOutputStream().apply { write(DirectModSyncProtocol.encodeManifest(manifest)); flush() }
            }
        }) { port ->
            val transport = client(port, RecordingCache(), attempts = 1, version = null)
            assertEquals(manifest, transport.getManifest())
            assertFailsWith<DirectModSyncUnsupportedException> { transport.reserve(manifest.scope, ByteArray(32)) }
        }
    }

    @Test
    fun authoritativeV2ErrorsNeverCauseAV1Probe() = runBlocking {
        withServer(handler = { socket ->
            assertManifestRequest(socket, version = 2)
            socket.getOutputStream().apply { write(DirectModSyncProtocol.encodeError(DirectModSyncErrorCode.ROOM_FULL)); flush() }
        }) { port ->
            val error = assertFailsWith<DirectModSyncServerException> {
                client(port, RecordingCache(), attempts = 1, version = null).getManifest()
            }
            assertEquals(DirectModSyncErrorCode.ROOM_FULL, error.code)
        }
    }

    @Test
    fun downloadsUseTheNegotiatedV2Prefix() = runBlocking {
        val bytes = ByteArray(8192) { (it * 19).toByte() }
        val descriptor = NetworkModDescriptor.fromBytes("v2-file", bytes)
        val manifest = DirectModManifest(2, "176", "instance-v2", "ready", true, 123, listOf(descriptor))
        val cache = RecordingCache()
        var requests = 0
        withServer(connections = 2, handler = { socket ->
            if (requests++ == 0) {
                assertManifestRequest(socket, version = 2)
                socket.getOutputStream().apply { write(DirectModSyncProtocol.encodeManifest(manifest)); flush() }
            } else {
                assertRequest(socket, DirectModSyncProtocol.GET_FILE, descriptor, 0, version = 2)
                socket.writeFile(descriptor, 0, bytes)
            }
        }) { port ->
            val transport = client(port, cache, attempts = 1, version = null)
            transport.getManifest()
            transport.download(descriptor, tempDir())
        }
        assertContentEquals(bytes, cache.stored.single().readBytes())
    }

    @Test
    fun downloadsOverLoopbackAndStoresOnlyVerifiedFile() = runBlocking {
        val bytes = ByteArray(128 * 1024) { (it * 31).toByte() }
        val descriptor = NetworkModDescriptor.fromBytes("loopback", bytes)
        val cache = RecordingCache()
        withServer(handler = { socket ->
            assertRequest(socket, DirectModSyncProtocol.GET_FILE, descriptor, 0)
            socket.writeFile(descriptor, 0, bytes)
        }) { port ->
            client(port, cache, attempts = 1).download(descriptor, tempDir())
        }
        assertEquals(1, cache.stored.size)
        assertContentEquals(bytes, cache.stored.single().readBytes())
    }

    @Test
    fun resumesTemporaryFileAtOffsetAfterTruncatedResponse() = runBlocking {
        val bytes = ByteArray(96 * 1024) { (it * 17).toByte() }
        val descriptor = NetworkModDescriptor.fromBytes("resume", bytes)
        val cache = RecordingCache()
        val offsets = Collections.synchronizedList(mutableListOf<Long>())
        val progress = mutableListOf<DirectModDownloadProgress>()
        withServer(connections = 2, handler = { socket ->
            val offset = assertRequest(socket, DirectModSyncProtocol.GET_FILE, descriptor, null)
            offsets += offset
            if (offsets.size == 1) {
                socket.writeFile(descriptor, offset, bytes.copyOfRange(offset.toInt(), bytes.size), closeAfter = 100)
            } else {
                socket.writeFile(descriptor, offset, bytes.copyOfRange(offset.toInt(), bytes.size))
            }
        }) { port ->
            client(port, cache, attempts = 2).downloadWithProgress(descriptor, tempDir()) { progress += it }
        }
        assertEquals(listOf(0L, 100L), offsets)
        val downloading = progress.filter { it.phase == DirectModDownloadPhase.DOWNLOADING }
        assertEquals(0L, downloading.first().downloadedBytes)
        assertTrue(downloading.any { it.downloadedBytes == 100L }, "The retry must report its retained offset")
        assertEquals(bytes.size.toLong(), downloading.last().downloadedBytes)
        assertTrue(progress.all { it.downloadedBytes in 0L..it.totalBytes })
        assertEquals(
            DirectModDownloadProgress(DirectModDownloadPhase.CACHING, bytes.size.toLong(), bytes.size.toLong()),
            progress.last(),
        )
        assertTrue(progress.any { it.phase == DirectModDownloadPhase.VERIFYING && it.downloadedBytes == bytes.size.toLong() })
        assertContentEquals(bytes, cache.stored.single().readBytes())
    }

    @Test
    fun byteProgressIsThrottledButAttemptBoundariesCompletionAndStagesAreImmediate() = runBlocking {
        val events = mutableListOf<DirectModDownloadProgress>()
        var now = 0L
        val reporter = DirectModDownloadProgressReporter(1000, { events += it }, { now })
        reporter.report(DirectModDownloadPhase.DOWNLOADING, 0, force = true)
        for (bytes in 1L..450L) {
            now += 1_000_000L
            reporter.report(DirectModDownloadPhase.DOWNLOADING, bytes)
        }
        assertEquals(listOf(0L, 200L, 400L), events.map { it.downloadedBytes })

        // Retry start and final byte cannot wait for another interval, even with no clock advance.
        reporter.report(DirectModDownloadPhase.DOWNLOADING, 450, force = true)
        reporter.report(DirectModDownloadPhase.DOWNLOADING, 1000, force = true)
        reporter.report(DirectModDownloadPhase.VERIFYING, 1000)
        reporter.report(DirectModDownloadPhase.CACHING, 1000)
        assertEquals(listOf(450L, 1000L, 1000L, 1000L), events.takeLast(4).map { it.downloadedBytes })
        assertEquals(
            listOf(DirectModDownloadPhase.VERIFYING, DirectModDownloadPhase.CACHING),
            events.takeLast(2).map { it.phase },
        )
    }

    @Test
    fun progressEntryPointKeepsLegacyTransportImplementationsWorking() = runBlocking {
        val descriptor = NetworkModDescriptor.fromBytes("legacy", byteArrayOf(1))
        val staging = tempDir()
        var called = false
        val transport = object : DirectModSyncTransport {
            override suspend fun getManifest(): DirectModManifest = error("unused")
            override suspend fun download(descriptor: NetworkModDescriptor, stagingDirectory: File?) {
                assertEquals("legacy", descriptor.name)
                assertEquals(staging, stagingDirectory)
                called = true
            }
        }
        transport.downloadWithProgress(descriptor, staging) { error("Legacy transports do not emit byte progress") }
        assertTrue(called)
    }

    @Test
    fun progressCallbackFailureIsPropagatedWithoutRetrying() = runBlocking {
        val descriptor = NetworkModDescriptor.fromBytes("observer-failure", byteArrayOf(1))
        val cache = RecordingCache()
        val staging = tempDir()
        var callbacks = 0
        val error = assertFailsWith<IllegalStateException> {
            // The initial progress callback fails before any socket is opened.
            client(1, cache, attempts = 3).downloadWithProgress(descriptor, staging) {
                callbacks++
                error("progress observer failed")
            }
        }
        assertEquals("progress observer failed", error.message)
        assertEquals(1, callbacks)
        assertTrue(cache.stored.isEmpty())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsTruncationAndHashMismatchWithoutPublishing() = runBlocking {
        val bytes = ByteArray(4096) { 7 }
        val descriptor = NetworkModDescriptor.fromBytes("bad", bytes)
        listOf("truncate", "hash").forEach { mode ->
            val cache = RecordingCache()
            assertFailsWith<DirectModSyncException> {
                withServer(handler = { socket ->
                    assertRequest(socket, DirectModSyncProtocol.GET_FILE, descriptor, 0)
                    val sent = if (mode == "hash") ByteArray(bytes.size) else bytes
                    socket.writeFile(descriptor, 0, sent, closeAfter = if (mode == "truncate") 100 else null)
                }) { port -> client(port, cache, attempts = 1).download(descriptor, tempDir()) }
            }
            assertTrue(cache.stored.isEmpty(), mode)
        }
    }

    @Test
    fun downloadPreservesServerErrorCodesWithoutPublishing() = runBlocking {
        val descriptor = NetworkModDescriptor.fromBytes("server-error", byteArrayOf(1, 2, 3))
        DirectModSyncErrorCode.entries.forEach { code ->
            val cache = RecordingCache()
            val staging = tempDir()
            val error = assertFailsWith<DirectModSyncException> {
                withServer(handler = { socket ->
                    assertRequest(socket, DirectModSyncProtocol.GET_FILE, descriptor, 0)
                    socket.getOutputStream().apply {
                        write(DirectModSyncProtocol.encodeError(code))
                        flush()
                    }
                }) { port -> client(port, cache, attempts = 1).download(descriptor, staging) }
            }
            // withContext 的协程堆栈恢复可能复制外层 IOException，服务端错误须在 cause 链中保留。
            val serverError = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<DirectModSyncServerException>()
                .firstOrNull()
            assertNotNull(serverError, "Missing server error for $code: $error")
            assertEquals(code, serverError.code)
            assertTrue(cache.stored.isEmpty(), code.name)
            assertTrue(staging.listFiles().orEmpty().isEmpty(), code.name)
        }
    }

    @Test
    fun cancellationClosesSocketAndDoesNotPublishPartialFile() = runBlocking {
        val bytes = ByteArray(128 * 1024)
        val descriptor = NetworkModDescriptor.fromBytes("cancel", bytes)
        val cache = RecordingCache()
        val started = CountDownLatch(1)
        val staging = tempDir()
        withServer(handler = { socket ->
            assertRequest(socket, DirectModSyncProtocol.GET_FILE, descriptor, 0)
            socket.writeFile(descriptor, 0, bytes, closeAfter = 10, awaitClose = true) { started.countDown() }
        }) { port ->
            val job = async(kotlinx.coroutines.Dispatchers.IO) { client(port, cache, attempts = 1, readTimeout = 30_000).download(descriptor, staging) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
        }
        assertTrue(cache.stored.isEmpty())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellingFromVerificationOrCachingProgressDoesNotPublishCompletedStagingFile() = runBlocking {
        val bytes = ByteArray(128 * 1024) { (it * 13).toByte() }
        val descriptor = NetworkModDescriptor.fromBytes("cancel-complete", bytes)
        listOf(DirectModDownloadPhase.VERIFYING, DirectModDownloadPhase.CACHING).forEach { cancelAt ->
            val cache = RecordingCache()
            val staging = tempDir()
            var reached = false
            assertFailsWith<CancellationException> {
                withServer(handler = { socket ->
                    assertRequest(socket, DirectModSyncProtocol.GET_FILE, descriptor, 0)
                    socket.writeFile(descriptor, 0, bytes)
                }) { port ->
                    client(port, cache, attempts = 1).downloadWithProgress(descriptor, staging) {
                        if (it.phase == cancelAt) {
                            assertEquals(bytes.size.toLong(), it.downloadedBytes)
                            reached = true
                            throw CancellationException("cancel at $cancelAt")
                        }
                    }
                }
            }
            assertTrue(reached, cancelAt.name)
            assertTrue(cache.stored.isEmpty(), cancelAt.name)
            assertTrue(staging.listFiles().orEmpty().isEmpty(), cancelAt.name)
        }
    }

    private fun client(port: Int, cache: NetworkModCache, attempts: Int, readTimeout: Int = 5_000, version: Int? = 1) = DirectModSyncClient(
        DirectJoinEndpoint("127.0.0.1", port), cache,
        connectTimeoutMillis = 2_000, probeReadTimeoutMillis = 2_000,
        downloadReadTimeoutMillis = readTimeout, maxAttempts = attempts, preferredProtocolVersion = version,
    )

    private suspend fun withServer(connections: Int = 1, handler: (Socket) -> Unit, block: suspend (Int) -> Unit) {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = thread {
            try {
                repeat(connections) { server.accept().use(handler) }
            } finally {
                server.close()
            }
        }
        try {
            block(server.localPort)
        } finally {
            server.close()
            worker.join(5_000)
        }
    }

    private fun assertRequest(socket: Socket, kind: Int, descriptor: NetworkModDescriptor, expectedOffset: Long?, version: Int = 1): Long {
        val input = DataInputStream(socket.getInputStream())
        assertContentEquals(if (version == 2) DirectModSyncProtocol.MAGIC_V2 else DirectModSyncProtocol.MAGIC, input.readNBytes(8))
        assertEquals(40, input.readInt())
        assertEquals(kind, input.readInt())
        assertEquals(descriptor.normalizedSha256, DirectModSyncProtocol.encodeSha256(input.readNBytes(32)))
        return input.readLong().also { if (expectedOffset != null) assertEquals(expectedOffset, it) }
    }

    private fun assertManifestRequest(socket: Socket, version: Int = 1) {
        val input = DataInputStream(socket.getInputStream())
        assertContentEquals(if (version == 2) DirectModSyncProtocol.MAGIC_V2 else DirectModSyncProtocol.MAGIC, input.readNBytes(8))
        assertEquals(0, input.readInt())
        assertEquals(DirectModSyncProtocol.GET_MANIFEST, input.readInt())
    }

    private fun Socket.writeFile(
        descriptor: NetworkModDescriptor,
        offset: Long,
        body: ByteArray,
        closeAfter: Int? = null,
        awaitClose: Boolean = false,
        onStarted: () -> Unit = {},
    ) {
        val header = DirectModSyncProtocol.fileResponseHeader(descriptor.payloadSize, offset, descriptor)
        val output = DataOutputStream(getOutputStream())
        output.writeInt(header.size + body.size)
        output.writeInt(DirectModSyncProtocol.FILE)
        output.write(header)
        output.write(body, 0, closeAfter ?: body.size)
        output.flush()
        onStarted()
        if (awaitClose) {
            runCatching { getInputStream().read() }
            Thread.sleep(100)
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("direct-client-test-").toFile().also { temporaryDirectories += it }

    private class RecordingCache : NetworkModCache {
        val stored = mutableListOf<File>()
        override fun prepareStartup() {}
        override fun find(descriptor: NetworkModDescriptor): NetworkModCacheEntry? = null
        override fun storeVerified(descriptor: NetworkModDescriptor, bytes: ByteArray): NetworkModCacheEntry = error("unused")
        override fun storeVerified(descriptor: NetworkModDescriptor, file: File): NetworkModCacheEntry {
            check(descriptor.matches(file))
            val copy = File.createTempFile("verified-", ".rwmod")
            file.copyTo(copy, overwrite = true)
            stored += copy
            return NetworkModCacheEntry(descriptor, copy, null, 0)
        }
        override fun activate(descriptor: NetworkModDescriptor): NetworkModCacheEntry = error("unused")
        override fun descriptorForManagedPath(path: String): NetworkModDescriptor? = null
    }
}
