/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync.direct

import io.github.rwpp.game.mod.NetworkModCache
import io.github.rwpp.game.mod.NetworkModDescriptor
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface DirectModSyncTransport {
    suspend fun getManifest(): DirectModManifest
    suspend fun download(descriptor: NetworkModDescriptor, stagingDirectory: File? = null)
    suspend fun reserve(scope: String, token: ByteArray): DirectModLease =
        throw DirectModSyncUnsupportedException("Direct mod sync reservations are unavailable")
    suspend fun renew(scope: String, token: ByteArray): DirectModLease =
        throw DirectModSyncUnsupportedException("Direct mod sync reservations are unavailable")
    suspend fun release(scope: String, token: ByteArray) {
        throw DirectModSyncUnsupportedException("Direct mod sync reservations are unavailable")
    }
}

/** 一连接一请求的阻塞 Socket 客户端；协程取消通过关闭活动 Socket 打断阻塞读。 */
class DirectModSyncClient @JvmOverloads constructor(
    private val endpoint: DirectJoinEndpoint,
    private val cache: NetworkModCache,
    private val connectTimeoutMillis: Int = 3_000,
    private val probeReadTimeoutMillis: Int = 3_000,
    private val downloadReadTimeoutMillis: Int = 60_000,
    private val maxAttempts: Int = 3,
    private val preferredProtocolVersion: Int? = null,
) : DirectModSyncTransport {
    @Volatile
    private var recognizedProtocol = false
    @Volatile
    private var negotiatedProtocolVersion: Int? = preferredProtocolVersion

    init {
        require(connectTimeoutMillis > 0 && probeReadTimeoutMillis > 0 && downloadReadTimeoutMillis > 0) {
            "Direct mod sync timeouts must be positive"
        }
        require(maxAttempts > 0) { "Direct mod sync attempts must be positive" }
        require(preferredProtocolVersion == null || preferredProtocolVersion in 1..2) { "Unsupported direct protocol version" }
    }

    override suspend fun getManifest(): DirectModManifest = withContext(Dispatchers.IO) {
        val selected = negotiatedProtocolVersion
        if (selected != null) return@withContext probeManifest(selected)
        try {
            probeManifest(DIRECT_MOD_SYNC_RESERVATION_PROTOCOL_VERSION)
        } catch (_: DirectModSyncUnsupportedException) {
            probeManifest(DIRECT_MOD_SYNC_PROTOCOL_VERSION)
        }
    }

    private suspend fun probeManifest(protocolVersion: Int): DirectModManifest {
        return try {
            withSocket(probeReadTimeoutMillis) { _, input, output ->
                DirectModSyncProtocol.writeGetManifest(output, protocolVersion)
                DirectModSyncProtocol.readManifest(input).also { manifest ->
                    recognizedProtocol = true
                    negotiatedProtocolVersion = protocolVersion
                    if (manifest.protocolVersion != protocolVersion) throw IOException("Direct manifest protocol version mismatch")
                    if (input.read() != -1) throw IOException("Trailing bytes after direct manifest response")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: DirectModSyncServerException) {
            recognizedProtocol = true
            negotiatedProtocolVersion = protocolVersion
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            // 只有尚未收到合法 MANIFEST/ERROR 时才允许上层回落 relay 或旧版 Direct。
            if (recognizedProtocol) throw DirectModSyncException("Direct mod sync manifest request failed at $endpoint", error)
            throw DirectModSyncUnsupportedException("Direct mod sync is not available at $endpoint", error)
        }
    }

    override suspend fun reserve(scope: String, token: ByteArray): DirectModLease = leaseRequest(DirectModSyncProtocol.RESERVE, scope, token)

    override suspend fun renew(scope: String, token: ByteArray): DirectModLease = leaseRequest(DirectModSyncProtocol.RENEW, scope, token)

    private suspend fun leaseRequest(kind: Int, scope: String, token: ByteArray): DirectModLease = withContext(Dispatchers.IO) {
        requireReservations()
        val credential = token.copyOf()
        withSocket(probeReadTimeoutMillis) { _, input, output ->
            DirectModSyncProtocol.writeLeaseRequest(output, kind, scope, credential)
            DirectModSyncProtocol.readLease(input, scope).also {
                if (input.read() != -1) throw IOException("Trailing bytes after direct lease response")
            }
        }
    }

    override suspend fun release(scope: String, token: ByteArray) = withContext(Dispatchers.IO) {
        requireReservations()
        val credential = token.copyOf()
        withSocket(probeReadTimeoutMillis) { _, input, output ->
            DirectModSyncProtocol.writeLeaseRequest(output, DirectModSyncProtocol.RELEASE, scope, credential)
            DirectModSyncProtocol.readReleased(input)
            if (input.read() != -1) throw IOException("Trailing bytes after direct release response")
        }
    }

    private fun requireReservations() {
        if (!recognizedProtocol || negotiatedProtocolVersion != DIRECT_MOD_SYNC_RESERVATION_PROTOCOL_VERSION) {
            throw DirectModSyncUnsupportedException("Direct mod sync reservations require protocol v2")
        }
    }

    override suspend fun download(descriptor: NetworkModDescriptor, stagingDirectory: File?) = withContext(Dispatchers.IO) {
        require(descriptor.payloadSize <= DIRECT_MOD_SYNC_MAX_FILE_BYTES) { "Direct mod exceeds size limit" }
        val staging = File.createTempFile("rwjs-direct-", ".part", stagingDirectory)
        var lastError: Exception? = null
        try {
            repeat(maxAttempts) { attempt ->
                currentCoroutineContext().ensureActive()
                try {
                    downloadAttempt(descriptor, staging)
                    if (!descriptor.matches(staging)) throw IOException("Downloaded direct mod hash mismatch")
                    currentCoroutineContext().ensureActive()
                    cache.storeVerified(descriptor, staging)
                    return@withContext
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    lastError = error
                    // 已收满但 hash/尾随校验失败时不能从文件末尾继续；截断则保留字节续传。
                    if (staging.length() >= descriptor.payloadSize) {
                        staging.delete()
                        staging.createNewFile()
                    }
                    if (attempt + 1 == maxAttempts) throw DirectModSyncException("Direct mod download failed: ${descriptor.name}", error)
                }
            }
            throw DirectModSyncException("Direct mod download failed: ${descriptor.name}", lastError)
        } finally {
            staging.delete()
        }
    }

    private suspend fun downloadAttempt(descriptor: NetworkModDescriptor, staging: File) {
        val offset = staging.length()
        if (offset > descriptor.payloadSize) {
            staging.delete()
            staging.createNewFile()
        }
        val actualOffset = staging.length()
        withSocket(downloadReadTimeoutMillis) { _, input, output ->
            DirectModSyncProtocol.writeGetFile(output, descriptor, actualOffset, negotiatedProtocolVersion ?: DIRECT_MOD_SYNC_PROTOCOL_VERSION)
            val response = DirectModSyncProtocol.readFileResponse(input, expected = descriptor, requestedOffset = actualOffset)
            val digest = MessageDigest.getInstance("SHA-256")
            if (actualOffset > 0) staging.inputStream().use { existing -> updateDigest(digest, existing) }
            FileOutputStream(staging, actualOffset > 0).use { file ->
                var remaining = response.bodyBytes
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("Truncated direct mod response")
                    file.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
            }
            if (staging.length() != descriptor.payloadSize || sha256Hex(digest) != descriptor.normalizedSha256) {
                throw IOException("Direct mod size or hash mismatch")
            }
            if (input.read() != -1) throw IOException("Trailing bytes after direct mod response")
        }
    }

    private suspend fun <T> withSocket(readTimeoutMillis: Int, block: suspend (Socket, BufferedInputStream, BufferedOutputStream) -> T): T =
        kotlinx.coroutines.coroutineScope {
            val socket = Socket()
            // 独立子协程在父协程“开始取消”时即进入 finally，不等阻塞 IO 返回。
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    runCatching { socket.close() }
                }
            }
            try {
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMillis)
                socket.soTimeout = readTimeoutMillis
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
                val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                block(socket, input, output)
            } finally {
                cancellation.cancel()
                runCatching { socket.close() }
            }
        }

    private fun updateDigest(digest: MessageDigest, input: java.io.InputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            digest.update(buffer, 0, read)
        }
    }
}
