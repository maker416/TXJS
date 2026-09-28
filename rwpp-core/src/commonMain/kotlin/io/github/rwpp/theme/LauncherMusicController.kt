/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.theme

import io.github.rwpp.appKoin
import io.github.rwpp.config.Settings
import io.github.rwpp.event.GlobalEventChannel
import io.github.rwpp.event.events.ReturnMainMenuEvent
import io.github.rwpp.event.events.StartGameEvent
import io.github.rwpp.platform.LauncherMusic
import kotlinx.coroutines.Dispatchers

/**
 * 背景音乐编排：主题切换、音量调整、进/出对局都会汇聚到 [sync] 做最终决策。
 *
 * 播放条件：当前有启用主题包 + 包内有当前平台支持的音乐文件 + 音量 > 0 + 不在对局中。
 */
object LauncherMusicController {

    @Volatile
    private var inGame = false

    private var subscribed = false

    /** 进程内一次：订阅对局进出事件。由 App 根组合调用。 */
    fun init() {
        if (subscribed) return
        subscribed = true
        GlobalEventChannel.filter(StartGameEvent::class).subscribeAlways(Dispatchers.Main.immediate) {
            inGame = true
            sync()
        }
        GlobalEventChannel.filter(ReturnMainMenuEvent::class).subscribeAlways(Dispatchers.Main.immediate) {
            inGame = false
            sync()
        }
    }

    /** 任何相关状态变化后调用：启用/停用主题包、调音量、进/出对局。 */
    fun sync() {
        val volume = appKoin.get<Settings>().launcherMusicVolume.coerceIn(0f, 1f)
        val file = ArtThemeController.activeTheme?.musicFile
        if (file == null || inGame || volume <= 0f) {
            LauncherMusic.stop()
        } else {
            LauncherMusic.play(file, volume)
        }
    }
}
