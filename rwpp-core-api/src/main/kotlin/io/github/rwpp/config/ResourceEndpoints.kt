/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.config

import io.github.rwpp.rwpp_core_api.BuildConfig

/** 资源中心的默认入口，由所选客户端 API 配置提供。 */
object ResourceEndpoints {
    const val ESPORTS_SITE_URL = BuildConfig.ESPORTS_SITE_URL
    const val RTS_BOX_SITE_URL = BuildConfig.RTS_BOX_SITE_URL
    const val RTS_BOX_SEARCH_API_URL = BuildConfig.RTS_BOX_SEARCH_API_URL
    const val RTS_BOX_HOT_LIST_URL = BuildConfig.RTS_BOX_HOT_LIST_URL
}
