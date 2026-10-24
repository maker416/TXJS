/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
import com.sun.net.httpserver.HttpServer
import io.github.rwpp.game.mod.*
import io.github.rwpp.io.HashUtils
import io.github.rwpp.io.zipFolderToByte
import io.github.rwpp.net.sync.ModSyncClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Also run by lowMemoryModTransferTest with -Xmx96m. Neither fixture nor server retains the payload. */
class ModTransferMemoryTest {
    @Test fun largeDownloadUploadAndEncryptedActivationStayWithinSmallHeap() = runBlocking {
        val root = Files.createTempDirectory("mod-transfer-memory").toFile()
        val size = 60L * 1024 * 1024
        val source = File(root, "source.rwmod")
        val block = ByteArray(64 * 1024) { (it % 251).toByte() }
        source.outputStream().use { output -> repeat((size / block.size).toInt()) { output.write(block) } }
        val hash = HashUtils.sha256(source)
        val uploaded = AtomicLong(-1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/files/$hash") { exchange ->
            if (exchange.requestMethod == "PUT") {
                var count = 0L
                val buffer = ByteArray(64 * 1024)
                exchange.requestBody.use { input -> while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    count += n
                } }
                uploaded.set(count)
                exchange.sendResponseHeaders(200, -1)
            } else {
                exchange.sendResponseHeaders(200, size)
                exchange.responseBody.use { output -> source.inputStream().use { it.copyTo(output, 64 * 1024) } }
            }
            exchange.close()
        }
        server.start()
        val http = OkHttpClient()
        try {
            val client = ModSyncClient(listOf("http://127.0.0.1:${server.address.port}"), http)
            val download = File(root, "download.tmp")
            client.downloadFileTo(hash, size, download)
            assertEquals(hash, HashUtils.sha256(download))
            client.uploadFile(hash, "test", download)
            assertEquals(size, uploaded.get())
            val descriptor = NetworkModDescriptor("large", size, hash)
            val key = ByteArray(32) { it.toByte() }
            val encrypted = File(root, "encrypted")
            encrypted.outputStream().use { ChunkedModEnvelope.write(download, descriptor, 123, key, it) }
            val active = File(root, "active")
            active.outputStream().use { ChunkedModEnvelope.read(encrypted, key, it, descriptor) }
            assertTrue(descriptor.matches(active))
        } finally {
            server.stop(0)
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            root.deleteRecursively()
        }
    }

    @Test fun folderPayloadRetainsExistingWireIdentity() {
        val root = Files.createTempDirectory("mod-folder-stream").toFile()
        try {
            val source = File(root, "mod").apply { mkdir() }
            File(source, "unit.ini").writeText("[core]\nname:unit\n")
            File(source, "nested").mkdir()
            File(source, "nested/data.txt").writeText("payload")
            val expected = NetworkModDescriptor.fromBytes("folder", source.zipFolderToByte())
            val mod = testMod(source)
            assertEquals(expected, mod.payloadDescriptor())
            mod.preparePayload(File(root, "staging")).use { snapshot ->
                assertEquals(expected, snapshot.descriptor)
                assertTrue(expected.matches(snapshot.file))
            }
            assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    private fun testMod(file: File) = object : Mod {
        override val id = 1
        override val name = "folder"
        override val description = ""
        override val minVersion = ""
        override val errorMessage: String? = null
        override var isEnabled = true
        override val path = file.path
        override fun getRamUsed() = ""
        override fun getSize() = 0L
        override fun getBytes(): ByteArray = error("Streaming path must not call getBytes")
    }
}
