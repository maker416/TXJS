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
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import org.apache.commons.csv.CSVFormat
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
            val locale = Locale.getDefault()
            val request = Request.Builder().url(
                if (locale.country == "CN")
                    "https://gitee.com/api/v5/repos/minxyzgo/RWPP/releases/latest"
                else "https://api.github.com/repos/Minxyzgo/RWPP/releases/latest"
            ).build()
            val response = client.newCall(request).execute()

            response.body?.string()?.let { str ->
                val version = Json.parse(str).asObject().getString("tag_name", "null")
                val body = Json.parse(str).asObject().getString("body", "null")
                val prerelease = Json.parse(str).asObject().getBoolean("prerelease", false)
                LatestVersionProfile(version, body, prerelease)
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


    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun CoroutineScope.getRoomListFromSourceUrl(url: List<String>): List<RoomDescription> =
        withContext(Dispatchers.IO) {
            withTimeout(5000L) {
                val channel = Channel<Response>()

                url.map {
                    val request = Request.Builder()
                        .url(it)
                        .addHeader("User-Agent", "rw android 176 zh")
                        .addHeader("Language", "zh")
                        .get()
                        .build()

                    client.newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (response.isSuccessful && channel.isEmpty) {
                                val result = channel.trySend(response)
                                if (result.isFailure) response.close()
                            } else response.close()
                        }
                    })
                }

                val result = channel.receive()

                val roomDescriptions = mutableListOf<RoomDescription>()

                val body = result.body!!.string()
                result.close()
                channel.close()

                val headerEnd = body.indexOfAny(charArrayOf('\r', '\n'))
                val header = if (headerEnd < 0) body else body.substring(0, headerEnd)
                if (!header.contains("CORRODINGGAMES")) {
                    throw RuntimeException("Unknown header: $header")
                }
                val payload = if (headerEnd < 0) "" else body.substring(headerEnd).trimStart('\r', '\n')

                val records = runCatching {
                    CSVFormat.RFC4180.builder()
                        .setIgnoreEmptyLines(true)
                        .build()
                        .parse(StringReader(payload))
                        .records
                }.getOrElse { e ->
                    throw RuntimeException("Failed to parse room list CSV body", e)
                }

                var blankUuidSeq = 0
                for (record in records) {
                    val fields = record.toList()
                    val desc = runCatching {
                        when (fields.size) {
                            13 -> parseNewProtocolRecord(fields, blankUuidSeq).also {
                                if (fields[0].toIntOrNull() == 0) blankUuidSeq++
                            }
                            22 -> parseLegacyRecord(fields, blankUuidSeq).also {
                                if (fields[0].toIntOrNull() == 0) blankUuidSeq++
                            }
                            else -> {
                                logger.warn("Skip room list record with unexpected column count ${fields.size}: $fields")
                                null
                            }
                        }
                    }.onFailure {
                        logger.error("Parse error when reading: ${fields.joinToString(",")}", it)
                    }.getOrNull()

                    if (desc != null) roomDescriptions.add(desc)
                }

                roomDescriptions
            }
        }

}

private fun parseNewProtocolRecord(f: List<String>, blankUuidSeq: Int): RoomDescription {
    val rawUuid = f[0]
    val uuid = if (rawUuid.toIntOrNull() == 0) rawUuid + blankUuidSeq else rawUuid
    val roomOwner = f[1]
    val address = f[2]
    val portStr = f[3].trim()
    val requiredPassword = f[4].equals("true", ignoreCase = true)
    val mapName = f[5]
    val statusRaw = f[6]
    val status = when (statusRaw) {
        "pending" -> "battleroom"
        else -> statusRaw
    }
    val roomType = f[7].ifBlank { RoomJoinType.IP }
    val currentPlayers = f[8].toIntOrNull()
    val maxPlayers = f[9].toIntOrNull()
    val isUpperCase = f[10].equals("true", ignoreCase = true)
    val roomMode = f[11]
    val mods = f[12]

    val port = portStr.toLongOrNull() ?: if (roomType == RoomJoinType.SHORT) 0L else 5123L
    val versionLabel = when {
        roomMode.equals("modded", ignoreCase = true) -> "modded"
        roomMode.equals("vanilla", ignoreCase = true) -> "vanilla"
        else -> roomMode
    }

    return RoomDescription(
        uuid = uuid,
        roomOwner = roomOwner,
        netWorkAddress = address,
        port = port,
        isOpen = !requiredPassword,
        creator = roomOwner,
        requiredPassword = requiredPassword,
        mapName = mapName,
        status = status,
        version = versionLabel,
        displayMapName = mapName,
        playerCurrentCount = currentPlayers,
        playerMaxCount = maxPlayers,
        isUpperCase = isUpperCase,
        mods = mods,
        roomJoinType = roomType,
    )
}

private fun parseLegacyRecord(r: List<String>, blankUuidSeq: Int): RoomDescription {
    val rawUuid = r[0]
    val uuid = if (rawUuid.toIntOrNull() == 0) rawUuid + blankUuidSeq else rawUuid
    return RoomDescription(
        uuid,
        r[1],
        r[2].toInt(),
        r[3],
        r[4],
        r[5].toLong(),
        r[6].toBooleanStrict(),
        r[7],
        r[8].toBooleanStrict(),
        r[9],
        r[10],
        r[11],
        r[12],
        r[13].toBooleanStrict(),
        r[14],
        r[15].toIntOrNull(),
        r[16].toIntOrNull(),
        r[17].toBooleanStrict(),
        r[18].ifBlank { r[0] },
        r[19].toBooleanStrict(),
        r[20],
        r[21].toInt(),
    )
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
