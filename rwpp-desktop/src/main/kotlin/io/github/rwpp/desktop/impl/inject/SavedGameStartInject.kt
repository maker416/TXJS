/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop.impl.inject

import com.corrodinggames.rts.appFramework.n
import com.corrodinggames.rts.gameFramework.j.k
import io.github.rwpp.desktop.GameEngine
import io.github.rwpp.inject.InjectClass
import io.github.rwpp.inject.RedirectMethod

/** 原版房间启动丢弃读档 boolean；失败必须抛回既有开局恢复流程。 */
@InjectClass(n::class)
object SavedGameStartInject {
    @RedirectMethod("r", "()V", "com.corrodinggames.rts.gameFramework.y", "c")
    fun loadHostedSave(name: String, network: Boolean): Boolean {
        check(GameEngine.B().ca.c(name, network)) { "Saved game loading failed" }
        return true
    }

    @RedirectMethod("r", "()V", "com.corrodinggames.rts.gameFramework.y", "a")
    fun loadReceivedSave(stream: k, network: Boolean, resync: Boolean, preserveSelection: Boolean): Boolean {
        check(GameEngine.B().ca.a(stream, network, resync, preserveSelection)) { "Received saved game loading failed" }
        return true
    }
}
