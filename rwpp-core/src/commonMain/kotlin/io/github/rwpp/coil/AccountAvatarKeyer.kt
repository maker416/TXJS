/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.coil

import coil3.key.Keyer
import coil3.request.Options

class AccountAvatarKeyer : Keyer<AccountAvatar> {
    override fun key(data: AccountAvatar, options: Options): String {
        return "account-avatar:${data.userId}:${data.hasAvatar}:${data.version}"
    }
}
