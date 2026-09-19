/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.runtime.Composable

/**
 * Debug 宿主与 Compose 预览共用的假数据账号/好友界面树。
 *
 * 导航仍只用 [UI.showAccountView] / [UI.showFriendChatView]，
 * 不另起页面栈。调用方需自行提供 Koin、主题与 [io.github.rwpp.LocalWindowManager]。
 */
@Composable
fun AccountUiHostContent() {
    when {
        UI.showFriendChatView -> {
            FriendChatView(onExit = {
                UI.showFriendChatView = false
                FakeFriendsSession.closeChat()
            })
        }
        UI.showAccountView -> {
            AccountView(onExit = {
                UI.showFriendChatView = false
                FakeFriendsSession.closeChat()
                UI.showAccountView = false
            })
        }
        else -> {
            UI.UiProvider.MainMenu(
                multiplayer = {},
                singlePlayer = {},
                settings = {},
                mods = {},
                extension = {},
                resourceBrowser = {},
                openSourceInfo = {},
                account = { UI.showAccountView = true },
            )
        }
    }
}
