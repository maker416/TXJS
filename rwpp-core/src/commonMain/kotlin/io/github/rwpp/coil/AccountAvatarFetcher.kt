/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.coil

import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import io.github.rwpp.account.AccountSession
import okio.FileSystem
import okio.buffer
import okio.source
import java.io.ByteArrayInputStream

/**
 * 从统一账号服务拉取头像 JPEG（`GET /users/{id}/avatar`，带 AppKey + Bearer）。
 * 任何失败（无头像 / 未登录 / 网络错误）都返回 null，由调用方回退到首字母占位。
 */
class AccountAvatarFetcher(
    private val data: AccountAvatar,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        if (!data.hasAvatar) return null
        if (!AccountSession.networkEnabled || !AccountSession.loggedIn) return null
        val bytes = runCatching {
            AccountSession.client().getAvatar(AccountSession.requireToken(), data.userId)
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return SourceFetchResult(
            source = ImageSource(
                ByteArrayInputStream(bytes).source().buffer(),
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = "image/jpeg",
            dataSource = DataSource.NETWORK,
        )
    }
}
