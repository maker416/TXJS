/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * RWJS 自有账号的进程内假会话。
 *
 * 只服务登录/注册/主页 UI，**不**读写 [io.github.rwpp.config.CoreData.loginCookie] / `userId`，
 * **不**调用 `Net.loginInBBS`，也**不**发任何网络请求。进程重启后回到未登录。
 *
 * 真 API 到达后，用同一套对话框与主页替换本对象即可。
 *
 * 切换方式：
 * - 默认：[loggedIn] 为 `false`（未登录空态）。
 * - 登录/注册框提交非空账号+密码（注册还须两次密码一致）会调用 [signIn]，显示名等于账号。
 *   例如账号填「修玉」即可在主页看到「修玉」。
 * - 主页确认退出后调用 [signOut]，留在主页空态；[lastIdentifier] 仍用于登录框预填。
 * - 开发/截图也可直接 [applyLoggedIn] / [applyLoggedOut]。
 */
object FakeAccountSession {
    var loggedIn: Boolean by mutableStateOf(false)
        private set

    /** 登录/注册时输入的账号标识。 */
    var identifier: String by mutableStateOf("")
        private set

    /** 首期等于 [identifier]，不编造独立昵称或 userId。 */
    var displayName: String by mutableStateOf("")
        private set

    /** 上次成功登录的账号，退出后仍保留，供登录框预填；密码从不回填。 */
    var lastIdentifier: String by mutableStateOf("")
        private set

    fun signIn(account: String) {
        val id = account.trim()
        identifier = id
        displayName = id
        lastIdentifier = id
        loggedIn = true
    }

    fun signOut() {
        loggedIn = false
        identifier = ""
        displayName = ""
    }

    /** 开发/截图：切到已登录假资料。 */
    fun applyLoggedIn(account: String) {
        signIn(account)
    }

    /** 开发/截图：切回未登录。保留 [lastIdentifier]。 */
    fun applyLoggedOut() {
        signOut()
    }
}
