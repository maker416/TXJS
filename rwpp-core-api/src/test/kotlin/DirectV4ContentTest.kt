/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync.direct

import io.github.rwpp.expansion.EXPANSION_MEDIA_TYPE
import io.github.rwpp.game.mod.*
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DirectV4ContentTest {
    private val work = Files.createTempDirectory("rwjs-direct-v4-test-").toFile()
    private val bytes = ByteArray(128 * 1024) { (it * 7).toByte() }
    private val hash = DirectModSyncProtocol.encodeSha256(MessageDigest.getInstance("SHA-256").digest(bytes))
    private val artifact = DirectContentArtifact("Expansion 1.0", EXPANSION_MEDIA_TYPE, "1.0.0", bytes.size.toLong(), hash)
    private val manifest = DirectModManifest(4, "176", "scope", "ready", true, 123, emptyList(),
        "server-instance", "lobby", 3, hash, artifact)
    @AfterTest fun cleanup() { work.deleteRecursively() }

    @Test fun v3AndV4HaveStrictDistinctManifestLayoutsAndContentIdentity() {
        assertEquals(manifest, DirectModSyncProtocol.readManifest(DirectModSyncProtocol.encodeManifest(manifest).inputStream()))
        val old = manifest.copy(protocolVersion = 3, contentRevision = "", artifact = null)
        assertEquals(old, DirectModSyncProtocol.readManifest(DirectModSyncProtocol.encodeManifest(old).inputStream()))
        assertFalse(manifest.sameRoomContent(manifest.copy(serverInstance = "another")))
        assertFalse(manifest.sameRoomContent(manifest.copy(artifact = artifact.copy(version = "2.0"))))
        assertFailsWith<java.io.IOException> {
            DirectModSyncProtocol.readManifest(DirectModSyncProtocol.encodeManifest(manifest.copy(contentRevision = "0".repeat(64))).inputStream())
        }
        val trailing = DirectModSyncProtocol.encodeManifest(manifest) + byteArrayOf(0)
        java.nio.ByteBuffer.wrap(trailing).putInt(trailing.size - 8)
        assertFailsWith<java.io.IOException> { DirectModSyncProtocol.readManifest(trailing.inputStream()) }
    }

    @Test fun readinessAndRecoveryQueriesBindRevisionAndAttemptNonce() {
        val token = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(16) { (it + 3).toByte() }
        val out = ByteArrayOutputStream()
        DirectModSyncProtocol.writeLeaseRequest(out, DirectModSyncProtocol.CONTENT_READY, "scope", token, 4, hash)
        DataInputStream(out.toByteArray().inputStream()).use { input ->
            assertContentEquals(DirectModSyncProtocol.MAGIC_V4, input.readNBytes(8))
            val length = input.readInt()
            assertEquals(13, input.readInt())
            assertEquals("scope", input.readProtocolString())
            assertContentEquals(token, input.readNBytes(32))
            assertEquals(hash, input.readProtocolString())
            assertEquals(4 + 5 + 32 + 4 + 64, length)
            assertEquals(-1, input.read())
        }
        out.reset()
        DirectModSyncProtocol.writeResumeCheck(out, "scope", token, nonce)
        DataInputStream(out.toByteArray().inputStream()).use { input ->
            assertContentEquals(DirectModSyncProtocol.MAGIC_V4, input.readNBytes(8))
            assertEquals(4 + 5 + 32 + 16, input.readInt())
            assertEquals(14, input.readInt())
            assertEquals("scope", input.readProtocolString())
            assertContentEquals(token, input.readNBytes(32)); assertContentEquals(nonce, input.readNBytes(16))
        }
    }

    @Test fun realTcpDownloadsWholeArchiveWithOffsetAndNeverWritesOrdinaryModCache() = runBlocking<Unit> {
        val token = ByteArray(32) { (it * 3).toByte() }
        val offsets = mutableListOf<Long>()
        var ready = false
        val cache = ForbiddenCache()
        server(6, { index, connection ->
            val input = DataInputStream(connection.getInputStream())
            assertContentEquals(DirectModSyncProtocol.MAGIC_V4, input.readNBytes(8))
            val length = input.readInt()
            val kind = input.readInt()
            val output = connection.getOutputStream()
            when (kind) {
                1 -> { assertEquals(0, length); output.write(DirectModSyncProtocol.encodeManifest(manifest)) }
                6, 13, 9 -> {
                    assertEquals("scope", input.readProtocolString()); assertContentEquals(token, input.readNBytes(32))
                    if (kind != 9) assertEquals(hash, input.readProtocolString())
                    if (kind == 13) ready = true
                    output.write(if (kind == 9) DirectModSyncProtocol.encodeReleased() else
                        DirectModSyncProtocol.encodeLease(DirectModLease("scope", 90_000, 30_000, "reserved", 1, 10, 1)))
                }
                3 -> {
                    assertEquals(40, length); assertContentEquals(DirectModSyncProtocol.decodeSha256(hash), input.readNBytes(32))
                    val offset = input.readLong(); offsets += offset
                    val remaining = bytes.size - offset.toInt()
                    val response = DataOutputStream(output)
                    response.writeInt(48 + remaining); response.writeInt(5)
                    response.write(DirectModSyncProtocol.fileResponseHeader(bytes.size.toLong(), offset, artifact.descriptor()))
                    response.write(bytes, offset.toInt(), if (index == 2) 100 else remaining)
                }
                else -> error("Unexpected request kind $kind")
            }
            output.flush()
        }) { port ->
            val client = DirectModSyncClient(DirectJoinEndpoint("127.0.0.1", port), cache, maxAttempts = 2)
            assertEquals(manifest, client.getManifest())
            assertEquals("127.0.0.1:$port", client.peerEndpoint)
            client.reserveContent("scope", token, hash)
            val file = client.downloadArtifact(artifact, work) { }
            assertContentEquals(bytes, file.readBytes())
            assertFalse(ready)
            client.contentReady("scope", token, hash)
            client.release("scope", token)
        }
        assertEquals(listOf(0L, 100L), offsets)
        assertTrue(ready)
    }

    @Test fun recognizedV4ErrorsAndInvalidManifestsNeverProbeAnOlderProtocol() = runBlocking<Unit> {
        for (response in listOf(DirectModSyncProtocol.encodeError(DirectModSyncErrorCode.UPGRADE_REQUIRED),
            DirectModSyncProtocol.encodeManifest(manifest.copy(contentRevision = "0".repeat(64))))) {
            server(1, { _, connection ->
                DataInputStream(connection.getInputStream()).readNBytes(16)
                connection.getOutputStream().apply { write(response); flush() }
            }) { port ->
                val error = assertFailsWith<DirectModSyncException> {
                    DirectModSyncClient(DirectJoinEndpoint("127.0.0.1", port), ForbiddenCache()).getManifest()
                }
                assertFalse(error is DirectModSyncUnsupportedException)
            }
        }
    }

    @Test fun nativeQueryOnlyExposesTheCurrentScopedRecoveryAttempt() = runBlocking<Unit> {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { connection -> listener.accept().use {
                val secret = "a".repeat(64); val nonce = "b".repeat(32)
                val handle = DirectJoinReservationCredentials.open(DirectJoinEndpoint("127.0.0.1", listener.localPort), secret, 4, true, nonce)
                assertEquals("txjsmd4r_${secret}_$nonce", DirectJoinReservationCredentials.queryFor(connection))
                assertFalse(handle.toString().contains(secret))
                handle.close()
                assertNull(DirectJoinReservationCredentials.queryFor(connection))
            } }
        }
    }

    @Test fun cancellationWhileDeliveringAVerifiedArchiveDeletesTheUndeliveredFile() = runBlocking<Unit> {
        val sent = java.util.concurrent.atomic.AtomicBoolean(false)
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val caller = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (armed.get() && sent.get()) context[kotlinx.coroutines.Job]?.cancel()
                executor.execute(block)
            }
        }
        try {
            server(2, { index, connection ->
                val input = DataInputStream(connection.getInputStream())
                assertContentEquals(DirectModSyncProtocol.MAGIC_V4, input.readNBytes(8))
                input.readInt(); val kind = input.readInt()
                val out = DataOutputStream(connection.getOutputStream())
                if (index == 0) { assertEquals(1, kind); out.write(DirectModSyncProtocol.encodeManifest(manifest)) }
                else {
                    assertEquals(3, kind)
                    out.writeInt(48 + bytes.size); out.writeInt(5)
                    out.write(DirectModSyncProtocol.fileResponseHeader(bytes.size.toLong(), 0, artifact.descriptor()))
                    out.write(bytes); out.flush(); sent.set(true)
                }
                out.flush()
            }) { port ->
                assertFailsWith<kotlinx.coroutines.CancellationException> {
                    kotlinx.coroutines.withContext(caller) {
                        val client = DirectModSyncClient(DirectJoinEndpoint("127.0.0.1", port), ForbiddenCache())
                        client.getManifest()
                        armed.set(true)
                        client.downloadArtifact(artifact, work) { }
                    }
                }
            }
            assertFalse(work.listFiles().orEmpty().any { it.extension == "part" })
        } finally { executor.shutdownNow() }
    }

    private suspend fun server(connections: Int, handler: (Int, Socket) -> Unit, action: suspend (Int) -> Unit) {
        val pool = Executors.newSingleThreadExecutor()
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 10_000
            val task = pool.submit { repeat(connections) { index -> listener.accept().use { it.soTimeout = 5_000; handler(index, it) } } }
            try { action(listener.localPort); task.get(5, TimeUnit.SECONDS) }
            finally { listener.close(); pool.shutdownNow() }
        }
    }

    private class ForbiddenCache : NetworkModCache {
        override fun prepareStartup() = error("Expansion must not use ordinary cache")
        override fun find(descriptor: NetworkModDescriptor): NetworkModCacheEntry? = error("Expansion must not use ordinary cache")
        override fun storeVerified(descriptor: NetworkModDescriptor, bytes: ByteArray): NetworkModCacheEntry = error("Expansion must not use ordinary cache")
        override fun activate(descriptor: NetworkModDescriptor): NetworkModCacheEntry = error("Expansion must not activate an ordinary Mod")
        override fun descriptorForManagedPath(path: String): NetworkModDescriptor? = null
    }
}
