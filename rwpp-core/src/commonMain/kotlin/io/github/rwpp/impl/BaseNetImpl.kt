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
    override val bbsProtocols: MutableList<BBSProtocol> = mutableListOf(RTSBoxProtocol, RTSBoxHotProtocol)
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
    ): String {
        val letter = when (prefix) {
            HostCommandPrefix.Q -> 'Q'
            HostCommandPrefix.R -> 'R'
        }
        val base = when {
            roomId != null && enableMods -> "${letter}CM$roomId"
            roomId != null -> "${letter}C$roomId"
            enableMods -> "${letter}mods"
            else -> "${letter}news"
        }
        val params = buildString {
            maxPlayer?.let { append("P$it") }
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

/**
 * 铁锈盒子「热门资源」列表（与 https://www.rtsbox.cn/category/rts-mod 下载站模块一致）。
 * 公开侧无 JSON，需解析移动端模块 HTML。
 */
val RTSBoxHotProtocol = BBSProtocol(
    "https://www.rtsbox.cn/wp-content/module/mobile/page/lt_download_list/0_index_html.php?password=echo_html",
    "铁锈盒子 热门资源",
    { page, _, _ ->
        MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("page", page.toString())
            .build()
    },
    { response ->
        runCatching {
            val html = response.body?.string() ?: return@runCatching null
            parseRtsBoxHotResources(html)
        }.getOrNull()
    }
)

private fun unescapeHtmlLight(text: String): String =
    text.replace("&#038;", "&")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&nbsp;", " ")
        .trim()

/**
 * 从下载站首页 HTML 中截取「热门资源」区块并解析卡片。
 * 热门列表为站方人工精选，无分页；调用方应整表替换而非追加。
 *
 * [NetResourceInfo.downloadNum] 在此协议下表示「热度」（非下载次数）；
 * 站点可能返回 `2.12w` 形式，会换算为整数（×10000）供展示格式化。
 */
internal fun parseRtsBoxHotResources(html: String): Array<NetResourceInfo> {
    val marker = "热门资源</span>"
    val start = html.indexOf(marker)
    if (start < 0) return emptyArray()

    val after = html.substring(start + marker.length)
    val nextTitle = Regex("""<span class="lt_download_list_top_titel">([^.<][^<]*)</span>""")
        .find(after)
    val block = if (nextTitle != null) after.substring(0, nextTitle.range.first) else after.take(20_000)

    val ids = Regex("""data-post_id="([0-9,]+)"""")
        .find(block)
        ?.groupValues
        ?.get(1)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

    if (ids.isEmpty()) return emptyArray()

    val titlePattern = Regex("""lt_title_span[^>]*>([^<]+)|<div class="title">([^<]+)</div>""")
    val imagePattern = Regex("""<img src="([^"]+)"|background-image:url\(([^)]+)\)""")
    val heatPattern = Regex(
        """(?:jinsom-fire-fill|lt_text_shadow)[\s\S]{0,160}?>([\d.]+w?)</span>"""
    )
    val authorPattern = Regex("""author/\d+"[^>]*>([^<]+)""")
    val badgePattern = Regex("""(?:独家|精选)[^<]{0,40}""")
    val nextCardPattern = Regex("""jinsom-post-\d+""")

    return ids.mapNotNull { id ->
        val pos = listOf(
            Regex("""post_id=$id&url=https://www\.rtsbox\.cn/$id\.html"""),
            Regex("""jinsom-post-$id"""),
            Regex("""www\.rtsbox\.cn/$id\.html""")
        ).firstNotNullOfOrNull { pat -> pat.find(block)?.range?.first } ?: return@mapNotNull null

        val nextPos = nextCardPattern.find(block, startIndex = pos + 10)?.range?.first
            ?: minOf(pos + 2500, block.length)
        val cardHtml = block.substring(pos, nextPos)
        // 首卡「独家/精选」角标可能写在链接正前方
        val lookbackHtml = block.substring(maxOf(0, pos - 280), pos)

        val titleMatch = titlePattern.find(cardHtml)
        val title = unescapeHtmlLight(
            titleMatch?.groupValues?.get(1)?.ifEmpty { null }
                ?: titleMatch?.groupValues?.get(2)?.ifEmpty { null }
                ?: "帖子 $id"
        )
        val imageMatch = imagePattern.find(cardHtml)
        val imageUrl = imageMatch?.groupValues?.get(1)?.ifEmpty { null }
            ?: imageMatch?.groupValues?.get(2)?.ifEmpty { null }
        val heatRaw = heatPattern.find(cardHtml)?.groupValues?.get(1)
        val author = authorPattern.find(cardHtml)?.groupValues?.get(1)?.let(::unescapeHtmlLight)
        val badge = (
            badgePattern.find(cardHtml)?.value
                ?: badgePattern.find(lookbackHtml)?.value
            )?.let(::unescapeHtmlLight)

        NetResourceInfo(
            id = id,
            title = title,
            description = badge,
            author = author,
            bbsUrl = "https://www.rtsbox.cn/$id.html",
            imageUrl = imageUrl,
            downloadNum = parseRtsBoxHeat(heatRaw)
        )
    }.toTypedArray()
}

/** 解析站点热度：`5852` 或 `2.12w`（万）。 */
internal fun parseRtsBoxHeat(raw: String?): Int? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (text.endsWith("w", ignoreCase = true)) {
        val value = text.dropLast(1).toDoubleOrNull() ?: return null
        (value * 10_000).toInt()
    } else {
        text.toIntOrNull()
    }
}

/** 热度展示：≥10000 时用站点同款 `x.xxw`。 */
internal fun formatRtsBoxHeat(heat: Int): String =
    if (heat >= 10_000) {
        val w = heat / 10_000.0
        val formatted = (((w * 100).toInt()) / 100.0).toString().trimEnd('0').trimEnd('.')
        "${formatted}w"
    } else {
        heat.toString()
    }
