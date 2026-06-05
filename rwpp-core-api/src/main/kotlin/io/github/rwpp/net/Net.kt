/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net

import com.eclipsesource.json.Json
import io.github.rwpp.config.CoreData
import io.github.rwpp.core.Initialization
import io.github.rwpp.io.GameInputStream
import io.github.rwpp.logger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.*
import java.util.*
import kotlin.reflect.full.createInstance

interface Net : KoinComponent, Initialization {
    /**
     * The map of packet decoders, key is the packet type, value is a lambda that takes a DataInputStream and returns a Packet.
     */
    val packetDecoders: MutableMap<Int, (DataInputStream) -> Packet>

    /**
     * listeners for each packet type, key is the packet type, value is a list of lambdas that take a Client and a Packet and return a Boolean.
     *
     * If the lambda returns true, then the packet would not be resolved by the game.
     */
    val listeners: MutableMap<Int, MutableList<(Client?, Packet) -> Boolean>>

    /**
     * Protocols for search mods.
     */
    val bbsProtocols: MutableList<BBSProtocol>

    /**
     * Default Okhttp3 client.
     */
    val client: OkHttpClient

    /**
     * The coroutine scope for network operations.
     */
    val scope: CoroutineScope

    /**
     * The map of room list providers, key is the name, value is a lambda that returns a list of RoomDescription.
     */
    val roomListProvider: MutableMap<String, suspend () -> List<RoomDescription>>

    /**
     * The map of room list host protocols, key is the name, value is a lambda that returns the host url of the room list.
     */
    val roomListHostProtocol: MutableMap<String, (maxPlayer: Int, enableMods: Boolean, isPublic: Boolean) -> String>

    /**
     * Build a quick-host command string in Q-series format.
     * @param enableMods whether to enable mods.
     * @param roomId optional room ID for private rooms (QC/QCM prefix).
     * @param maxPlayer max player count (P parameter).
     * @param unitLimit unit limit (U parameter).
     * @param credits initial credits (C parameter).
     * @param speedMultiplier game speed multiplier (Z parameter).
     * @return a command string like "QnewsP20C5000Z5" or "QCM6666P10".
     */
    fun buildQuickHostCommand(
        enableMods: Boolean,
        roomId: String? = null,
        maxPlayer: Int? = null,
        unitLimit: Int? = null,
        credits: Int? = null,
        speedMultiplier: Int? = null
    ): String

    /**
     * Send a packet to the server.
     */
    fun sendPacketToServer(packet: Packet)

    /**
     * Send a packet to all clients. (if host)
     * @see [io.github.rwpp.game.GameRoom.isHost]
     */
    fun sendPacketToClients(packet: Packet)

    fun openUriInBrowser(uri: String)

    fun getLatestVersionProfile(): LatestVersionProfile? {
        return runCatching {
            val request = Request.Builder().url(
                "https://gitee.com/api/v5/repos/maker416/TXJS/releases/latest"
            ).build()
            val response = client.newCall(request).execute()

            response.body?.string()?.let { str ->
                val json = Json.parse(str).asObject()
                val version = json.getString("tag_name", "null")
                val body = json.getString("body", "null")
                val prerelease = json.getBoolean("prerelease", false)
                val assets = json.get("assets")?.asArray()?.map {
                    val obj = it.asObject()
                    val name = obj.getString("name", "")
                    val url = obj.getString("browser_download_url", "")
                    ReleaseAsset(name, url)
                }?.filter { asset ->
                    !asset.name.endsWith(".zip") && !asset.name.endsWith(".tar.gz")
                } ?: emptyList()
                LatestVersionProfile(version, body, prerelease, assets)
            }
        }.getOrNull()
    }

    fun searchBBS(
        protocol: BBSProtocol,
        page: Int,
        keyword: String,
        type: ResourceType,
        callback: (Result<Array<NetResourceInfo>>) -> Unit
    ) {
        // 构建请求对象
        val request = Request.Builder()
            .url(protocol.url)
            .post(protocol.requestBodyProvider(page, keyword, type))
            .build()

        // 异步执行请求
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = if (response.isSuccessful) {
                    protocol.resultParser(response)?.let {
                        Result.success(it)
                    } ?: Result.failure(IOException("Unknown response body"))
                } else {
                    Result.failure(IOException("HTTP error code: ${response.code}"))
                }
                callback(result)
            }
        })
    }

    fun loginInBBS(
        username: String,
        password: String,
        callback: (Result<String>) -> Unit
    ) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("username", username)
            .addFormDataPart("password", password)
            .build()
        // 构建请求对象
        val request = Request.Builder()
            .url("https://www.rtsbox.cn/api/login/api.php")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val coreData = get<CoreData>()
                val result = if (response.isSuccessful) {
                    val json = response.body?.string() ?: ""
                    val obj = Json.parse(json).asObject()
                    val code = obj.getInt("code", 0)
                    val msg = String(obj.getString("msg", "").encodeToByteArray(), Charsets.UTF_8)
                    if (code == 1) {
                        coreData.loginCookie = response.headers("Set-Cookie")
                        coreData.userId = obj.getInt("user", 0)
                        Result.success(msg)
                    } else {
                        Result.failure(IllegalArgumentException(msg))
                    }
                } else {
                    Result.failure(IOException("HTTP error code: ${response.code}"))
                }
                callback(result)
            }
        })
    }

    fun getBytes(url: String): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .build()
        return client.newCall(request).execute().body?.byteStream()?.readBytes()
    }

    fun downloadFile(
        url: String,
        outputFile: File,
        progressCallback: (progress: Float) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                progressCallback(-1f)

                logger.error(e.stackTraceToString())
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    throw IOException("Unexpected code $response")
                }

                var downloadedBytes: Long = 0
                val body = response.body ?: return
                val contentLength = body.contentLength()
                var inputStream: InputStream? = null
                var outputStream: RandomAccessFile? = null

                try {
                    inputStream = body.byteStream()
                    outputStream = RandomAccessFile(outputFile, "rw")

                    val buffer = ByteArray(4096)
                    var read: Int

                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                        downloadedBytes += read.toLong()
                        progressCallback((downloadedBytes.toFloat() / contentLength.toFloat()).apply {
                            logger.info("downloading: $this")
                        })
                    }

                    logger.info("Download completed: ${outputFile.absoluteFile}")
                } catch (e: Exception) {
                    logger.error(e.stackTraceToString())
                    progressCallback(-1f)
                } finally {
                    inputStream?.close()
                    outputStream?.close()
                }
            }
        })
    }


    /**
     * Fetch room types from RWList `GET /servers/room-types` for each configured base URL.
     * Merges results from all bases; returns empty list if every request fails.
     */
    suspend fun fetchRoomTypes(roomListApiUrls: String): List<String> =
        withContext(Dispatchers.IO) {
            val bases = roomListApiBasesWithDefaultFallback(roomListApiUrls)
            if (bases.isEmpty()) return@withContext emptyList()

            val merged = linkedSetOf<String>()
            for (base in bases) {
                runCatching {
                    val request = Request.Builder()
                        .url("$base/servers/room-types")
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@runCatching
                        val body = response.body?.string() ?: return@runCatching
                        merged.addAll(parseRwListRoomTypes(body))
                    }
                }.onFailure {
                    logger.warn("Failed to fetch room types from $base: ${it.message}")
                }
            }
            merged.sorted()
        }

    /** @see fetchRoomTypes */
    suspend fun fetchRoomListLabels(roomListApiUrls: String): List<String> =
        fetchRoomTypes(roomListApiUrls)

    suspend fun CoroutineScope.getRoomListFromSourceUrl(url: List<String>): List<RoomDescription> =
        withContext(Dispatchers.IO) {
            if (url.isEmpty()) return@withContext emptyList()

            val allEntries = mutableListOf<RwListServerEntry>()
            var lastError: Throwable? = null

            for (base in url.map { normalizeRwListBaseUrl(it) }.filter { it.isNotEmpty() }.distinct()) {
                runCatching {
                    allEntries.addAll(fetchRwListServersForBase(base))
                }.onFailure {
                    lastError = it
                    logger.warn("Failed to fetch RWList servers from $base: ${it.message}")
                }
            }

            if (allEntries.isEmpty() && lastError != null) {
                throw RuntimeException(
                    "无法连接到任何列表服务器，请你检查网络连接或更新客户端",
                    lastError
                )
            }

            mapRwListEntriesToRoomDescriptions(allEntries)
        }

    private fun fetchRwListServersForBase(base: String): List<RwListServerEntry> {
        val pageSize = 100
        var page = 1
        val collected = mutableListOf<RwListServerEntry>()
        var total = Int.MAX_VALUE

        while ((page - 1) * pageSize < total) {
            val requestUrl = "$base/servers?page=$page&page_size=$pageSize"
            val request = Request.Builder().url(requestUrl).get().build()
            val pageData = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP error ${response.code} for $requestUrl")
                }
                val body = response.body?.string()
                    ?: throw IOException("Empty response body for $requestUrl")
                parseRwListServersPage(body)
            }
            if (pageData.list.isEmpty()) break
            collected.addAll(pageData.list)
            total = pageData.total
            if (page * pageData.pageSize >= total) break
            page++
        }
        return collected
    }

    /**
     * 将本机房间发布到 RWList 公开房间列表。
     * 对应 `POST /servers/public`。
     */
    suspend fun publishServerToPublicList(
        baseUrl: String,
        name: String,
        ip: String,
        roomtype: String
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val jsonBody = """{"name":"$name","ip":"$ip","roomtype":"$roomtype"}"""
                val url = java.net.URL("$baseUrl/servers/public")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { os ->
                    os.write(jsonBody.toByteArray(Charsets.UTF_8))
                }
                val responseCode = conn.responseCode
                val body = if (responseCode in 200..299) {
                    conn.inputStream.use { it.reader().readText() }
                } else {
                    val errorBody = conn.errorStream?.use { it.reader().readText() } ?: ""
                    throw IOException("HTTP $responseCode: $errorBody")
                }
                body
            }
        }

    /**
     * 续期已发布的房间记录，重置 TTL 倒计时。
     * 对应 `POST /servers/refresh`。
     */
    suspend fun refreshServer(
        baseUrl: String, serverId: String, secretKey: String
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val jsonBody = """{"server_id":"$serverId","secret_key":"$secretKey"}"""
                val request = Request.Builder()
                    .url("$baseUrl/servers/refresh")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${response.body?.string()}")
                    }
                    val body = response.body?.string() ?: ""
                    val code = Json.parse(body).asObject().getInt("code", -1)
                    if (code != 0) {
                        val msg = Json.parse(body).asObject().getString("message", "unknown")
                        throw IOException("RWList error $code: $msg")
                    }
                }
            }
        }

    /**
     * 查询已发布房间在列表中的剩余存活时间（秒）。
     * 对应 `POST /servers/expiry`。
     */
    suspend fun getServerExpiry(
        baseUrl: String, serverId: String, secretKey: String
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val jsonBody = """{"server_id":"$serverId","secret_key":"$secretKey"}"""
                val request = Request.Builder()
                    .url("$baseUrl/servers/expiry")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${response.body?.string()}")
                    }
                    val body = response.body?.string() ?: ""
                    val root = Json.parse(body).asObject()
                    val code = root.getInt("code", -1)
                    if (code != 0) {
                        val msg = root.getString("message", "unknown")
                        throw IOException("RWList error $code: $msg")
                    }
                    root.get("data")?.asObject()?.getInt("remaining_seconds", 0) ?: 0
                }
            }
        }

}

@Suppress("UNCHECKED_CAST")
inline fun <reified T : Packet> Net.registerPacketListener(
    packetType: Int,
    noinline listener: (Client?, T) -> Boolean
) {
    val method = T::class.java.getDeclaredMethod("readPacket", GameInputStream::class.java)
    packetDecoders[packetType] =
        {
            val p = T::class.createInstance()
            method.invoke(p, GameInputStream(it))
            p
        }
    listeners.getOrPut(packetType) { mutableListOf() }.add(listener as (Client?, Packet) -> Boolean)
}
