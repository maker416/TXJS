/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.sync

import io.github.rwpp.logger
import io.github.rwpp.net.useCancellable
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val API_PREFIX = "/api/v1"
private const val SECRET_HEADER = "X-Secret"
private const val PEER_SECRET_HEADER = "X-Peer-Secret"
private const val BUFFER_SIZE = 64 * 1024

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()

/**
 * 同步服务器调用失败。
 *
 * [statusCode] 为服务端返回的 HTTP 状态码（含 4xx/5xx）；
 * 网络层失败（连接失败、超时、下载数据不完整）时为 null。
 */
class ModSyncException(message: String, val statusCode: Int?) : IOException(message)

private class InvalidBlob(message: String) : IOException(message)

/**
 * 模组同步服务器（Go 端 relaymod）的 HTTP 客户端。
 *
 * - 全部方法为 suspend，并在 [Dispatchers.IO] 上执行，支持协程取消（取消即中断底层 OkHttp 调用）；
 * - 多 [baseUrls] 按序 failover：网络错误与 5xx 换下一个地址，4xx（含 404/409）立即抛出不再重试；
 * - 服务端错误体会按 [ErrorResponse] 解析，提取其中的 `error` 作为异常信息。
 */
class ModSyncClient(
    baseUrls: List<String>,
    private val client: OkHttpClient,
) {
    /** 规范化后的 baseUrl 列表（trim 并去掉尾部斜杠，丢弃空白项）。 */
    private val baseUrls: List<String> = baseUrls
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotEmpty() }

    init {
        require(this.baseUrls.isNotEmpty()) { "ModSyncClient 至少需要一个同步服务器 baseUrl" }
    }

    private val json = Json {
        // 容忍服务端后续新增字段，避免老客户端解析失败
        ignoreUnknownKeys = true
    }

    /**
     * blob 下载专用客户端。
     * [readTimeout] 是**块与块之间**的空闲超时：15s 会把 TTFB/短暂卡顿误判失败；
     * 10 分钟又会让中途断流的进度条假死很久。60s 空闲即重试，整次调用最多 15 分钟。
     */
    private val blobClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES)
            .build()
    }

    /**
     * 注册房间同步记录（status=preparing）。对应 `POST /rooms`。
     * secret 随请求体传输；重复 key 且 secret 不匹配时服务端返回 409（抛 [ModSyncException]）。
     */
    suspend fun register(req: RoomRegisterRequest) {
        withFailover("$API_PREFIX/rooms", {
            post(json.encodeToString(req).toRequestBody(JSON_MEDIA_TYPE))
        }) { }
    }

    /** 标记房间全部模组已上传完毕（status=ready）。对应 `POST /rooms/{key}/ready`。 */
    suspend fun markReady(key: String, secret: String) {
        withFailover("$API_PREFIX/rooms/${enc(key)}/ready", {
            header(SECRET_HEADER, secret)
            post(EMPTY_BODY)
        }) { }
    }

    /** 为房间追加别名（发布到列表后绑定 `sid:<server_id>`）。对应 `POST /rooms/{key}/alias`。 */
    suspend fun addAlias(key: String, secret: String, alias: String) {
        withFailover("$API_PREFIX/rooms/${enc(key)}/alias", {
            header(SECRET_HEADER, secret)
            post(json.encodeToString(AliasRequest(alias)).toRequestBody(JSON_MEDIA_TYPE))
        }) { }
    }

    /** 心跳续期房间记录（推荐每 60s 一次）。对应 `POST /rooms/{key}/heartbeat`。 */
    suspend fun heartbeat(key: String, secret: String) {
        withFailover("$API_PREFIX/rooms/${enc(key)}/heartbeat", {
            header(SECRET_HEADER, secret)
            post(EMPTY_BODY)
        }) { }
    }

    /** 注销房间同步记录并解除 blob 引用。对应 `DELETE /rooms/{key}`。 */
    suspend fun unregister(key: String, secret: String) {
        withFailover("$API_PREFIX/rooms/${enc(key)}", {
            header(SECRET_HEADER, secret)
            delete()
        }) { }
    }

    /**
     * 批量查询服务端缺失的 blob。对应 `POST /files/check`（key 以查询参数附带，供服务端校验/审计）。
     *
     * @return 服务端尚不存在的 sha256 列表（需要上传的）。
     */
    suspend fun checkFiles(key: String, secret: String, hashes: List<String>): List<String> {
        val encodedKey = enc(key)
        return withFailover("$API_PREFIX/files/check?key=$encodedKey", {
            header(SECRET_HEADER, secret)
            post(json.encodeToString(FilesCheckRequest(hashes)).toRequestBody(JSON_MEDIA_TYPE))
        }) { response ->
            json.decodeFromString<FilesCheckResponse>(response.body?.string().orEmpty()).missing
        }
    }

    /**
     * 上传模组 blob（PUT 原始字节，`Content-Type: application/octet-stream`）。
     * 对应 `PUT /files/{sha256}`；服务端复算 sha256 与路径一致才接受。
     *
     * @param onProgress 按缓冲区回调上传进度（0..1）。
     */
    suspend fun uploadFile(
        sha256: String,
        secret: String,
        bytes: ByteArray,
        onProgress: (Float) -> Unit = {},
    ) {
        withFailover("$API_PREFIX/files/$sha256", {
            header(SECRET_HEADER, secret)
            put(progressRequestBody(bytes, onProgress))
        }) { }
    }

    /** Upload an immutable snapshot; retries reopen the file instead of retaining it in memory. */
    suspend fun uploadFile(sha256: String, secret: String, file: File, onProgress: (Float) -> Unit = {}) {
        val size = file.length()
        val context = currentCoroutineContext()
        val body = object : RequestBody() {
            override fun contentType() = OCTET_STREAM_MEDIA_TYPE
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(BUFFER_SIZE)
                var sent = 0L
                file.inputStream().use { input ->
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        sent += count
                        if (sent > size) throw IOException("Upload snapshot changed")
                        onProgress(if (size == 0L) 1f else sent.toFloat() / size)
                    }
                }
                if (sent != size) throw IOException("Upload snapshot changed")
                onProgress(1f)
            }
        }
        withFailover("$API_PREFIX/files/$sha256", {
            header(SECRET_HEADER, secret)
            put(body)
        }) { }
    }

    /**
     * 查询房间同步清单。对应 `GET /rooms/{key}`。
     *
     * @return 清单响应；404（无同步记录）时返回 null 而非抛异常。
     */
    suspend fun fetchManifest(key: String): RoomManifestResponse? = try {
        withFailover("$API_PREFIX/rooms/${enc(key)}", { get() }) { response ->
            json.decodeFromString<RoomManifestResponse>(response.body?.string().orEmpty())
        }
    } catch (e: ModSyncException) {
        if (e.statusCode == 404) null else throw e
    }

    /**
     * 加入者 upsert 带外同步进度。对应 `PUT /rooms/{key}/peers/{peer_id}`。
     *
     * 新建时 [peerSecret] 可空，服务端返回一次性 secret；更新时必须传入已保存的 secret。
     * @return 新建时返回 `peer_secret`；更新时返回 null。
     */
    suspend fun upsertPeer(
        key: String,
        peerId: String,
        progress: SyncPeerUpsertRequest,
        peerSecret: String? = null,
    ): String? = withFailover("$API_PREFIX/rooms/${enc(key)}/peers/${enc(peerId)}", {
        if (!peerSecret.isNullOrEmpty()) header(PEER_SECRET_HEADER, peerSecret)
        put(json.encodeToString(progress).toRequestBody(JSON_MEDIA_TYPE))
    }) { response ->
        val body = response.body?.string().orEmpty()
        val decoded = json.decodeFromString<SyncPeerProgress>(body)
        decoded.peerSecret.takeIf { it.isNotBlank() }
    }

    /**
     * 加入者清理进度 Presence。对应 `DELETE /rooms/{key}/peers/{peer_id}`（幂等）。
     * 加入者传 [peerSecret]；房主亦可传 [roomSecret]（`X-Secret`）。
     */
    suspend fun deletePeer(
        key: String,
        peerId: String,
        peerSecret: String? = null,
        roomSecret: String? = null,
    ) {
        withFailover("$API_PREFIX/rooms/${enc(key)}/peers/${enc(peerId)}", {
            if (!peerSecret.isNullOrEmpty()) header(PEER_SECRET_HEADER, peerSecret)
            if (!roomSecret.isNullOrEmpty()) header(SECRET_HEADER, roomSecret)
            delete()
        }) { }
    }

    /**
     * 房主清空该房全部 Presence。对应 `DELETE /rooms/{key}/peers`。
     */
    suspend fun clearPeers(key: String, roomSecret: String) {
        withFailover("$API_PREFIX/rooms/${enc(key)}/peers", {
            header(SECRET_HEADER, roomSecret)
            delete()
        }) { }
    }

    /**
     * 房主拉取未过期的加入者进度列表。对应 `GET /rooms/{key}/peers`（须 [roomSecret]）。
     * 房间不存在时抛 [ModSyncException]（404）。
     */
    suspend fun listPeers(key: String, roomSecret: String): List<SyncPeerProgress> =
        withFailover("$API_PREFIX/rooms/${enc(key)}/peers", {
            header(SECRET_HEADER, roomSecret)
            get()
        }) { response ->
            json.decodeFromString<SyncPeerListResponse>(response.body?.string().orEmpty()).peers
        }

    /**
     * 加入者以 peer 身份拉取同房间其他加入者的进度列表。对应 `GET /rooms/{key}/peers`，
     * 带 `X-Peer-Secret` 头（区别于房主版的 `X-Secret`）。
     *
     * 响应 JSON 结构与房主版相同（`{"peers": [...]}`），仅字段为子集（不含 ip），
     * 复用 [SyncPeerListResponse] 解析，缺失字段走可空/默认值。
     * 房间不存在或 secret 错误时抛 [ModSyncException]。
     */
    suspend fun listPeersAsPeer(key: String, peerSecret: String): List<SyncPeerProgress> =
        withFailover("$API_PREFIX/rooms/${enc(key)}/peers", {
            header(PEER_SECRET_HEADER, peerSecret)
            get()
        }) { response ->
            json.decodeFromString<SyncPeerListResponse>(response.body?.string().orEmpty()).peers
        }

    /**
     * 使用 charset 名称重载编码路径/查询参数。
     * 勿用 `URLEncoder.encode(String, Charset)`：该重载在 Android API 33 之前不存在，
     * 运行期会抛 [NoSuchMethodError]（本项目 minSdk=26）。
     */
    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    /** Legacy in-memory API. Application transfers use [downloadFileTo]. */
    @Deprecated("Use downloadFileTo to avoid retaining a whole mod in heap")
    suspend fun downloadFile(
        sha256: String,
        expectedSize: Long,
        onProgress: (Float) -> Unit = {},
    ): ByteArray {
        val file = File.createTempFile("rwpp-blob-", ".tmp")
        return try { downloadFileTo(sha256, expectedSize, file, onProgress).readBytes() }
        finally { file.delete() }
    }

    /**
     * Download into a fresh staging file with bounded memory, Range resume and SHA-256 validation.
     * The caller owns a successful file; any failure/cancellation deletes this staging file.
     */
    suspend fun downloadFileTo(
        sha256: String,
        expectedSize: Long,
        target: File,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        require(expectedSize >= 0 && sha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(!target.exists() || target.length() == 0L) { "Download requires a fresh staging file" }
        val context = currentCoroutineContext()
        var completed = false
        try {
            RandomAccessFile(target, "rw").use { output ->
                var downloaded = 0L
                var lastError: IOException? = null
                var lastProgress = 0L
                val buffer = ByteArray(BUFFER_SIZE)
                for (attempt in 0 until BLOB_DOWNLOAD_ATTEMPTS) {
                    context.ensureActive()
                    try {
                        executeBlob("$API_PREFIX/files/$sha256", {
                            get()
                            header("Accept-Encoding", "identity")
                            if (downloaded > 0) header("Range", "bytes=$downloaded-")
                        }) { response ->
                            val endExclusive = when (response.code) {
                                200 -> {
                                    output.setLength(0)
                                    output.seek(0)
                                    downloaded = 0
                                    expectedSize
                                }
                                206 -> {
                                    val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
                                        .matchEntire(response.header("Content-Range").orEmpty())
                                        ?: throw InvalidBlob("Invalid Content-Range")
                                    val start = range.groupValues[1].toLongOrNull()
                                    val end = range.groupValues[2].toLongOrNull()
                                    val total = range.groupValues[3].toLongOrNull()
                                    if (start != downloaded || end == null || end < downloaded ||
                                        total != expectedSize || end >= expectedSize) {
                                        throw InvalidBlob("Content-Range does not match the requested mod")
                                    }
                                    output.seek(downloaded)
                                    end + 1
                                }
                                else -> throw InvalidBlob("Unexpected blob HTTP ${response.code}")
                            }
                            val body = response.body ?: throw InvalidBlob("Empty blob response")
                            if (body.contentLength() >= 0 && body.contentLength() != endExclusive - downloaded) {
                                throw InvalidBlob("Blob Content-Length does not match manifest/range")
                            }
                            body.byteStream().use { input ->
                                while (true) {
                                    context.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count.toLong() > endExclusive - downloaded) throw InvalidBlob("Blob exceeds declared size")
                                    // Local disk failure is terminal, not a network retry.
                                    try { output.write(buffer, 0, count) }
                                    catch (error: IOException) { throw InvalidBlob("Cannot write mod staging file: ${error.message}") }
                                    downloaded += count
                                    val now = System.currentTimeMillis()
                                    if (expectedSize > 0 && now - lastProgress >= PROGRESS_THROTTLE_MS) {
                                        lastProgress = now
                                        onProgress((downloaded.toDouble() / expectedSize).toFloat())
                                    }
                                }
                            }
                            if (downloaded != endExclusive) throw IOException("Truncated blob response")
                        }
                        if (downloaded != expectedSize) throw IOException("Incomplete blob: $downloaded/$expectedSize")
                        output.seek(0)
                        val digest = MessageDigest.getInstance("SHA-256")
                        while (true) {
                            context.ensureActive()
                            val count = output.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!actual.equals(sha256, ignoreCase = true)) throw InvalidBlob("Mod SHA-256 mismatch")
                        context.ensureActive()
                        onProgress(1f)
                        completed = true
                        return@withContext target
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: InvalidBlob) {
                        throw error
                    } catch (error: ModSyncException) {
                        if (error.statusCode == 416) {
                            output.setLength(0)
                            output.seek(0)
                            downloaded = 0
                        } else if (error.statusCode != null && error.statusCode in 400..499) throw error
                        lastError = error
                    } catch (error: IOException) {
                        context.ensureActive()
                        lastError = error
                    }
                }
                throw lastError ?: IOException("Mod download failed")
            }
        } finally {
            if (!completed) target.delete()
        }
    }

    private fun progressRequestBody(bytes: ByteArray, onProgress: (Float) -> Unit): RequestBody =
        object : RequestBody() {
            override fun contentType(): MediaType = OCTET_STREAM_MEDIA_TYPE

            override fun contentLength(): Long = bytes.size.toLong()

            override fun writeTo(sink: BufferedSink) {
                if (bytes.isEmpty()) {
                    onProgress(1f)
                    return
                }
                var offset = 0
                while (offset < bytes.size) {
                    val count = minOf(BUFFER_SIZE, bytes.size - offset)
                    sink.write(bytes, offset, count)
                    sink.flush()
                    offset += count
                    onProgress(offset.toFloat() / bytes.size)
                }
            }
        }

    /**
     * blob 下载专用 failover：连不上 / 5xx 换下一个地址。
     * 已经开始读 body 后断流不再换镜像（已收字节留给外层 Range 续传），避免下一台 404 把整次下载判死。
     */
    private suspend fun executeBlob(
        path: String,
        customize: Request.Builder.() -> Unit,
        onResponse: (Response) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            var lastError: IOException? = null
            var startedBody = false
            for (base in baseUrls) {
                coroutineContext.ensureActive()
                val request = Request.Builder()
                    .url(base + path)
                    .apply(customize)
                    .build()
                try {
                    blobClient.useCancellable(request) { response ->
                        when {
                            response.isSuccessful -> {
                                startedBody = true
                                onResponse(response)
                                return@withContext
                            }
                            response.code in 500..599 -> {
                                logger.warn("同步服务器 $base 返回 HTTP ${response.code}，尝试下一个地址")
                                lastError = response.toModSyncException()
                            }
                            else -> throw response.toModSyncException()
                        }
                    }
                } catch (e: ModSyncException) {
                    throw e
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    lastError = e
                    if (startedBody) {
                        logger.warn("[MODSYNC] blob stream interrupted on $base: ${e.message}")
                        throw e
                    }
                    logger.warn("连接同步服务器 $base 失败：${e.message}，尝试下一个地址")
                }
            }
            throw lastError ?: ModSyncException("没有可用的同步服务器地址", null)
        }
    }

    /**
     * 多 baseUrl 按序 failover：任一地址 2xx 即返回；5xx/网络错误记录后尝试下一个；
     * 4xx（含 404/409）立即抛 [ModSyncException]；全部失败则抛出最后记录的错误。
     */
    private suspend fun <T> withFailover(
        path: String,
        customize: Request.Builder.() -> Unit,
        http: OkHttpClient = client,
        onSuccess: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        for (base in baseUrls) {
            coroutineContext.ensureActive()
            val request = Request.Builder()
                .url(base + path)
                .apply(customize)
                .build()
            try {
                http.useCancellable(request) { response ->
                    when {
                        response.isSuccessful -> return@withContext onSuccess(response)
                        response.code in 500..599 -> {
                            logger.warn("同步服务器 $base 返回 HTTP ${response.code}，尝试下一个地址")
                            lastError = response.toModSyncException()
                        }
                        else -> throw response.toModSyncException()
                    }
                }
            } catch (e: ModSyncException) {
                throw e
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                logger.warn("连接同步服务器 $base 失败：${e.message}，尝试下一个地址")
                lastError = e
            }
        }
        throw lastError ?: ModSyncException("没有可用的同步服务器地址", null)
    }

    private fun Response.toModSyncException(): ModSyncException {
        val detail = runCatching {
            json.decodeFromString<ErrorResponse>(body?.string().orEmpty()).error
        }.getOrNull().orEmpty()
        return ModSyncException(
            if (detail.isNotBlank()) detail else "HTTP $code",
            code,
        )
    }

    private companion object {
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null, 0, 0)
        const val BLOB_DOWNLOAD_ATTEMPTS = 3
        const val PROGRESS_THROTTLE_MS = 200L
    }
}
