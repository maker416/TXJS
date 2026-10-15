/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.android.impl.inject

import android.media.MediaPlayer
import io.github.rwpp.android.BackgroundKeepAlive
import io.github.rwpp.inject.Inject
import io.github.rwpp.inject.InjectClassByString
import io.github.rwpp.inject.InjectMode
import io.github.rwpp.inject.InterruptResult

/** 引擎 A() 还用于后台退房后的菜单刷新；在播放器恢复入口门控，保留菜单刷新。 */
object EngineMusicInject {
    fun onResume(): Any = if (BackgroundKeepAlive.isAppInForeground) Unit else InterruptResult.Unit
}

/** prepareAsync 可能在退后台后才完成；该回调也是原版音乐启动的独立入口。 */
@InjectClassByString("com.corrodinggames.rts.gameFramework.be")
object EngineMusicPreparedInject {
    @Inject("onPrepared", InjectMode.InsertBefore, "(Landroid/media/MediaPlayer;)V")
    fun onPrepared(player: MediaPlayer?): Any =
        if (BackgroundKeepAlive.isAppInForeground) Unit else InterruptResult.Unit
}
