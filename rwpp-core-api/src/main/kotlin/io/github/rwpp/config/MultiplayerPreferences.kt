/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import kotlinx.serialization.Serializable
import org.koin.core.annotation.Single

/** Default RWList service base URLs (no path); multiple mirrors separated by `;`. */
const val DEFAULT_ROOM_LIST_API_URLS = "http://list.xn--rhqr8xvr4ahqsgka.com:11450"

/** 默认模组同步服务器 base URL（不含路径）；多个镜像用 `;` 分隔。 */
const val DEFAULT_MOD_SYNC_API_URLS = "http://modsync.铁壳锈世纪.com:11453"

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
    var roomListApiUrls: String = DEFAULT_ROOM_LIST_API_URLS,
    var modSyncApiUrls: String = DEFAULT_MOD_SYNC_API_URLS,
    var allServerConfig: MutableList<ServerConfig> = mutableListOf(),
    var roomLabelFilterSelection: List<String> = emptyList(),
) : Config
