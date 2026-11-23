/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import io.github.rwpp.expansion.ExpansionBinding
import io.github.rwpp.rwpp_core_api.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.koin.core.annotation.Single

/** Default RWList service base URLs (no path); multiple mirrors separated by `;`. */
const val DEFAULT_ROOM_LIST_API_URLS = BuildConfig.ROOM_LIST_API_URLS

/** 发布房间和续租使用同一个 RWList，镜像用于读取列表。 */
val DEFAULT_ROOM_LIST_API_URL = DEFAULT_ROOM_LIST_API_URLS.substringBefore(';')

/** 默认模组同步服务器 base URL（不含路径）；多个镜像用 `;` 分隔。 */
const val DEFAULT_MOD_SYNC_API_URLS = BuildConfig.MOD_SYNC_API_URLS

/**
 * Multiplayer player preferences
 */
@Single
@Serializable
data class MultiplayerPreferences(
    var mapNameFilter: String = "",
    var creatorNameFilter: String = "",
    var playerLimitRangeFrom: Int = 0,
    var playerLimitRangeTo: Int = 100,
    var joinServerAddress: String = "",
    @Transient @Volatile var roomListApiUrls: String = DEFAULT_ROOM_LIST_API_URLS,
    @Transient @Volatile var modSyncApiUrls: String = DEFAULT_MOD_SYNC_API_URLS,
    var allServerConfig: MutableList<ServerConfig> = mutableListOf(),
    var roomLabelFilterSelection: List<String> = emptyList(),
    /** Last expansion accepted on this device for each open-platform URL and game ID. */
    var acceptedPortalExpansions: Map<String, ExpansionBinding> = emptyMap(),
) : Config
