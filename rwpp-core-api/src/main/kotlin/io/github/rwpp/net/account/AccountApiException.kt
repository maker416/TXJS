/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.net.account

import java.io.IOException

/**
 * 统一账号 API 失败。
 *
 * [code] 为服务端机器可读错误码；网络层失败时为 [NETWORK]。
 * [statusCode] 为 HTTP 状态码，连接失败时为 null。
 */
class AccountApiException(
    val code: String,
    message: String,
    val statusCode: Int?,
    val retryAfterSeconds: Int? = null,
) : IOException(message) {
    companion object {
        const val NETWORK = "network"
    }
}
