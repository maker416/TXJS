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
const val DEFAULT_ROOM_LIST_API_URLS = "http://210.16.166.71:11450"

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
    var allServerConfig: MutableList<ServerConfig> = mutableListOf(),
    var roomLabelFilterSelection: List<String> = emptyList(),
) : Config
