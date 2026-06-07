/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.impl

import com.eclipsesource.json.Json
import io.github.rwpp.config.ConfigIO
import io.github.rwpp.config.MultiplayerPreferences
import io.github.rwpp.config.ServerType
import io.github.rwpp.logger
import io.github.rwpp.net.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import org.koin.core.component.get
import java.io.DataInputStream
import java.util.concurrent.TimeUnit

abstract class BaseNetImpl : Net {
    override val packetDecoders: MutableMap<Int, (DataInputStream) -> Packet> = mutableMapOf()
    override val listeners: MutableMap<Int, MutableList<(Client?, Packet) -> Boolean>> = mutableMapOf()
    override val client: OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(Interceptor { chain ->
            val request = chain.request().newBuilder()
                .removeHeader("Accept-Encoding")
                .build()
            chain.proceed(request)
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override val scope: CoroutineScope = CoroutineScope(SupervisorJob())
    override val bbsProtocols: MutableList<BBSProtocol> = mutableListOf(RTSBoxProtocol, RTSBoxDownloadWeeklyProtocol)
    override val roomListProvider: MutableMap<String, suspend () -> List<RoomDescription>> = mutableMapOf()
    override val roomListHostProtocol: MutableMap<String, (maxPlayer: Int, enableMods: Boolean, isPublic: Boolean) -> String> = mutableMapOf()

    override fun init() {
        val prefs = get<MultiplayerPreferences>()
        val configIO = get<ConfigIO>()
        val roomLists = prefs.allServerConfig.filter { it.type == ServerType.RoomList }
        var prefsChanged = false

        val migratedUrls = migrateRoomListApiUrls(prefs.roomListApiUrls)
        if (migratedUrls != prefs.roomListApiUrls) {
            logger.info(
                "Migrated room list API URLs from legacy masterserver to RWList: " +
                    "${prefs.roomListApiUrls} -> $migratedUrls"
            )
            prefs.roomListApiUrls = migratedUrls
            prefsChanged = true
        }
        if (prefs.allServerConfig.removeAll { it.type == ServerType.RoomList }) {
            prefsChanged = true
        }
        if (prefsChanged) {
            configIO.saveConfig(prefs)
        }

        roomListHostProtocol["RCN"] = { maxPlayer, enableMods, isPublic ->
            if (enableMods) {
                if (isPublic) {
                    "Rmodupp$maxPlayer"
                } else {
                    "Rmodp$maxPlayer"
                }
            } else if (isPublic) {
                "Rnewupp$maxPlayer"
            } else {
                "Rnewp$maxPlayer"
            }
        }

        roomListHostProtocol["SCN"] = { maxPlayer, enableMods, isPublic ->
            if (enableMods) {
                if (isPublic) {
                    "Smodupp$maxPlayer"
                } else {
                    "Smodp$maxPlayer"
                }
            } else if (isPublic) {
                "Snewupp$maxPlayer"
            } else {
                "Snewp$maxPlayer"
            }
        }

        roomListHostProtocol["QN"] = { _, enableMods, _ ->
            if (enableMods) "Qmods" else "Qnews"
        }
    }

    override fun buildQuickHostCommand(
        enableMods: Boolean,
        roomId: String?,
        maxPlayer: Int?,
        unitLimit: Int?,
        credits: Int?,
        speedMultiplier: Int?,
        prefix: HostCommandPrefix,
        isPublic: Boolean,
    ): String {
        val base = when (prefix) {
            HostCommandPrefix.Q -> when {
                roomId != null && enableMods -> "QCM$roomId"
                roomId != null -> "QC$roomId"
                enableMods -> "Qmods"
                else -> "Qnews"
            }
            HostCommandPrefix.R -> {
                val playerCount = maxPlayer ?: DEFAULT_R_HOST_MAX_PLAYERS
                roomListHostProtocol["RCN"]?.invoke(playerCount, enableMods, isPublic)
                    ?: if (enableMods) {
                        if (isPublic) "Rmodupp$playerCount" else "Rmodp$playerCount"
                    } else if (isPublic) {
                        "Rnewupp$playerCount"
                    } else {
                        "Rnewp$playerCount"
                    }
            }
        }
        val params = buildString {
            if (prefix == HostCommandPrefix.Q) {
                maxPlayer?.let { append("P$it") }
            }
            unitLimit?.let { append("U$it") }
            credits?.let { append("C$it") }
            speedMultiplier?.let { append("Z$it") }
        }
        return base + params
    }
}


val RTSBoxProtocol = BBSProtocol(
    "https://www.rtsbox.cn/api/search_bbs.php",
    "铁锈盒子",
    { page, keyword, type ->
        MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                addFormDataPart("bbs_id", if (type == ResourceType.Mod) "4" else "5")
                addFormDataPart("keyword", keyword)
                addFormDataPart("page", page.toString())
            }
            .build()
    },
    { response ->
        runCatching {
            val jsonBody = Json.parse(response.body?.string()).asObject()
            var id = 0
            buildList {
                for (data in jsonBody.get("data").asArray()) {
                    val info = data.asObject()
                    val title = String(info.getString("title", "???").toByteArray(), Charsets.UTF_8)

                    add(
                        NetResourceInfo(
                            title + id, // ???
                            title,
                            bbsUrl = info.getString("bbsurl", "???"),
                            //downloadUrl = info.getString("downurl", "???"),
                            imageUrl = info.getString("img", null)
                        )
                    )

                    id++
                }
            }.toTypedArray()
        }.getOrNull()
    }
)

val RTSBoxDownloadWeeklyProtocol = BBSProtocol(
    "https://www.rtsbox.cn/api/lt_api/data.php",
    "铁锈盒子 下载周榜",
    { page, _, type ->
        MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                addFormDataPart("catID", if (type == ResourceType.Mod) "mod" else "map")
                addFormDataPart("type", "WeekDownload")
                addFormDataPart("page", page.toString())
            }
            .build()
    },
    { response ->
        runCatching {
            val jsonBody = Json.parse(response.body?.string().apply { println(this) })
            buildList {
                for (data in jsonBody.asArray()) {
                    val info = data.asObject()
                    val title = String(info.getString("title", "???").toByteArray(), Charsets.UTF_8)
                    val postID = info.getInt("postID", -1)
                    val downloadNum = info.getInt("download_num", -1)

                    add(
                        NetResourceInfo(
                            postID.toString(),
                            title,
                            bbsUrl = "https://www.rtsbox.cn/$postID.html",
                            imageUrl = info.getString("img_url", null),
                            downloadNum = downloadNum
                        )
                    )
                }
            }.toTypedArray()
        }.getOrNull()
    }
)
