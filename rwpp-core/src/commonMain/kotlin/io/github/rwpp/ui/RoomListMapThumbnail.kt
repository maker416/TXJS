/*
 * Copyright 2023-2025 RWPP contributors
 */

package io.github.rwpp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.rwpp.game.Game
import io.github.rwpp.game.map.GameMap
import io.github.rwpp.rwpp_core.generated.resources.Res
import io.github.rwpp.rwpp_core.generated.resources.error_missingmap
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject

internal val LocalRoomListMapIndex = staticCompositionLocalOf<Map<String, GameMap>> { emptyMap() }

internal fun buildRoomListMapIndex(maps: List<GameMap>): Map<String, GameMap> {
    val index = LinkedHashMap<String, GameMap>()
    fun putKey(key: String, map: GameMap) {
        val normalized = normalizeMapLookupKey(key)
        if (normalized.isNotEmpty()) index.putIfAbsent(normalized, map)
    }
    for (map in maps) {
        putKey(map.mapName, map)
        putKey(map.displayName(), map)
    }
    return index
}

private fun normalizeMapLookupKey(raw: String): String =
    raw.trim()
        .removeSuffix(".tmx")
        .removeSuffix(".rwsave")
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .lowercase()

internal fun resolveRoomListMap(index: Map<String, GameMap>, mapName: String): GameMap? {
    val key = normalizeMapLookupKey(mapName)
    if (key.isEmpty() || key == "unknown") return null
    return index[key]
}

@Composable
internal fun rememberRoomListMapIndex(): Map<String, GameMap> {
    val game = koinInject<Game>()
    return remember(game) {
        runCatching { buildRoomListMapIndex(game.getAllMaps(false)) }.getOrElse { emptyMap() }
    }
}

@Composable
internal fun ProvideRoomListMapIndex(content: @Composable () -> Unit) {
    val index = rememberRoomListMapIndex()
    CompositionLocalProvider(LocalRoomListMapIndex provides index, content = content)
}

/** 房间列表左侧 1:1 地图缩略图：按名称匹配本地原版/已安装地图，否则占位图。 */
@Composable
internal fun RoomListMapThumbnail(
    mapName: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val index = LocalRoomListMapIndex.current
    val map = remember(mapName, index) { resolveRoomListMap(index, mapName) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        if (map != null) {
            val context = LocalPlatformContext.current
            val request = remember(context, map) {
                ImageRequest.Builder(context).data(map).precision(Precision.INEXACT).build()
            }
            AsyncImage(
                request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painterResource(Res.drawable.error_missingmap),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                alpha = 0.72f,
            )
        }
    }
}
