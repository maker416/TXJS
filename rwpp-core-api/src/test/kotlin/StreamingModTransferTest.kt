/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
import io.github.rwpp.io.HashUtils
import io.github.rwpp.net.sync.ModSyncClient
import io.github.rwpp.game.mod.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class StreamingModTransferTest {
    private val data = ByteArray(128 * 1024) { (it % 239).toByte() }
    @BeforeTest fun setupLogger() { io.github.rwpp.logger = org.slf4j.LoggerFactory.getLogger("StreamingModTransferTest") }

    @Test fun resumesDroppedBodyAtExactRange() = withServer { server, client, target ->
        server.enqueue(MockResponse().setBody(Buffer().write(data)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes 65536-131071/131072")
            .setBody(Buffer().write(data, 65536, 65536)))
        client.downloadFileTo(HashUtils.sha256(data), data.size.toLong(), target)
        assertContentEquals(data, target.readBytes())
        server.takeRequest()
        assertEquals("bytes=65536-", server.takeRequest().getHeader("Range"))
    }

    @Test fun ignoredRangeRestartsInsteadOfAppending() = withServer { server, client, target ->
        server.enqueue(MockResponse().setBody(Buffer().write(data)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(MockResponse().setBody(Buffer().write(data)))
        client.downloadFileTo(HashUtils.sha256(data), data.size.toLong(), target)
        assertContentEquals(data, target.readBytes())
    }

    @Test fun mismatchedRangeDeletesPartialFile() = withServer { server, client, target ->
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 1-131071/131072")
            .setBody(Buffer().write(data, 1, data.size - 1)))
        assertFailsWith<IOException> { client.downloadFileTo(HashUtils.sha256(data), data.size.toLong(), target) }
        assertFalse(target.exists())
    }

    @Test fun badHashOrLengthNeverPublishesPayload() = withServer { server, client, target ->
        server.enqueue(MockResponse().setBody(Buffer().write(data)))
        assertFailsWith<IOException> { client.downloadFileTo("a".repeat(64), data.size.toLong(), target) }
        assertFalse(target.exists())
        server.enqueue(MockResponse().setBody("oversized"))
        assertFailsWith<IOException> { client.downloadFileTo(HashUtils.sha256(data), 1, target) }
        assertFalse(target.exists())
    }

    @Test fun cancelledDownloadDeletesStaging() = withServer { server, client, target ->
        server.enqueue(MockResponse().setBody(Buffer().write(data)).setBodyDelay(3, TimeUnit.SECONDS))
        coroutineScope {
            val job = launch { client.downloadFileTo(HashUtils.sha256(data), data.size.toLong(), target) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(3_000) { job.cancelAndJoin() }
        }
        assertFalse(target.exists())
    }

    @Test fun encryptedChunksRejectTamperTruncationAndTrailingData() {
        val root = Files.createTempDirectory("mod-aead").toFile()
        try {
            val source = File(root, "source").apply { writeBytes(data) }
            val descriptor = NetworkModDescriptor.fromBytes("fixture", data)
            val envelope = File(root, "encrypted")
            val key = ByteArray(32)
            envelope.outputStream().use { ChunkedModEnvelope.write(source, descriptor, 42, key, it) }
            val bytes = envelope.readBytes()
            assertEquals(42L, ChunkedModEnvelope.read(envelope, key).createdAtMillis)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            envelope.writeBytes(bytes)
            assertFails { ChunkedModEnvelope.read(envelope, key) }
            envelope.writeBytes(bytes.copyOf(bytes.size - 20))
            assertFails { ChunkedModEnvelope.read(envelope, key) }
            envelope.outputStream().use { ChunkedModEnvelope.write(source, descriptor, 42, key, it) }
            envelope.appendBytes(byteArrayOf(1))
            assertFails { ChunkedModEnvelope.read(envelope, key) }
        } finally { root.deleteRecursively() }
    }

    private fun withServer(test: suspend (MockWebServer, ModSyncClient, File) -> Unit) = runBlocking {
        val server = MockWebServer().apply { start() }
        val root = Files.createTempDirectory("mod-download").toFile()
        val http = OkHttpClient()
        try { test(server, ModSyncClient(listOf(server.url("/").toString()), http), File(root, "payload.tmp")) }
        finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
            root.deleteRecursively()
        }
    }
}
