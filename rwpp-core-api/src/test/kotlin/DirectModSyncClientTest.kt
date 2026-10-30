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
        withServer(connections = 2, handler = { socket ->
            val offset = assertRequest(socket, DirectModSyncProtocol.GET_FILE, descriptor, null)
            offsets += offset
            if (offsets.size == 1) {
                socket.writeFile(descriptor, offset, bytes.copyOfRange(offset.toInt(), bytes.size), closeAfter = 100)
            } else {
                socket.writeFile(descriptor, offset, bytes.copyOfRange(offset.toInt(), bytes.size))
            }
        }) { port ->
            client(port, cache, attempts = 2).download(descriptor, tempDir())
        }
        assertEquals(listOf(0L, 100L), offsets)
        assertContentEquals(bytes, cache.stored.single().readBytes())
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

    private fun client(port: Int, cache: NetworkModCache, attempts: Int, readTimeout: Int = 5_000) = DirectModSyncClient(
        DirectJoinEndpoint("127.0.0.1", port), cache,
        connectTimeoutMillis = 2_000, probeReadTimeoutMillis = 2_000,
        downloadReadTimeoutMillis = readTimeout, maxAttempts = attempts,
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

    private fun assertRequest(socket: Socket, kind: Int, descriptor: NetworkModDescriptor, expectedOffset: Long?): Long {
        val input = DataInputStream(socket.getInputStream())
        assertContentEquals(DirectModSyncProtocol.MAGIC, input.readNBytes(8))
        assertEquals(40, input.readInt())
        assertEquals(kind, input.readInt())
        assertEquals(descriptor.normalizedSha256, DirectModSyncProtocol.encodeSha256(input.readNBytes(32)))
        return input.readLong().also { if (expectedOffset != null) assertEquals(expectedOffset, it) }
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
