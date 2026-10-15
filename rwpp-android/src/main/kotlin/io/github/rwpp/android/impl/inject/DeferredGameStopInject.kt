/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl.inject

import com.corrodinggames.rts.game.i
import io.github.rwpp.android.BackgroundKeepAlive
import io.github.rwpp.android.impl.GameEngine
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClassByString
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.InterruptResult

/** 700ms 定时器执行时再次确认连接状态，覆盖“先排停线程，随后连接成功”的竞态。 */
@InjectClassByString("com.corrodinggames.rts.game.j")
object DeferredGameStopInject {
    private val cancelStop by lazy { i::class.java.getDeclaredMethod("ap").apply { isAccessible = true } }

    @Inject("run", InjectMode.InsertBefore, "()V")
    fun onDeferredStop(): Any {
        if (!BackgroundKeepAlive.shouldKeepGameThreadRunning()) return Unit
        // 原版 ap() 同步清除 ab 并取消/清空 Timer，后续真正离线时还能重新安排停线程。
        cancelStop.invoke(GameEngine.t())
        return InterruptResult.Unit
    }
}
