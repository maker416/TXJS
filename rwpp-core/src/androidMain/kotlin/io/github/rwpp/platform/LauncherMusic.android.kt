/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import android.media.MediaPlayer
import io.github.rwpp.logger
import java.io.File

actual object LauncherMusic {
    actual val supportedMusicExtensions: List<String> =
        listOf("mp3", "ogg", "m4a", "aac", "wav", "flac")

    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    @Volatile
    private var volume: Float = 0.5f

    /**
     * App 退后台期间为 true。独立于播放器实例：[stop] 不清除，后台期间 [play] 只准备不开播，
     * 避免后台断线 / 主题切换触发 `LauncherMusicController.sync()` 时在后台重新出声。
     */
    @Volatile
    private var paused: Boolean = false

    private var prepared: Boolean = false

    @Synchronized
    actual fun play(file: File, volume: Float) {
        setVolume(volume)
        val existing = player
        if (currentFile == file && existing != null) {
            if (paused || !prepared) return
            runCatching { if (!existing.isPlaying) existing.start() }
            return
        }
        stop()
        currentFile = file
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                isLooping = true
                setVolume(volume, volume)
                setOnPreparedListener { p ->
                    synchronized(this@LauncherMusic) {
                        if (player == p) {
                            prepared = true
                            runCatching { if (!paused) p.start() }
                        }
                    }
                }
                setOnErrorListener { p, _, _ ->
                    synchronized(this@LauncherMusic) {
                        runCatching { p.release() }
                        if (player == p) {
                            player = null
                            currentFile = null
                            prepared = false
                        }
                    }
                    true
                }
                prepareAsync()
                player = this
            }
        }.onFailure {
            logger.warn("LauncherMusic: cannot play ${file.name}: ${it.message}")
            player = null
            currentFile = null
        }
    }

    @Synchronized
    actual fun stop() {
        runCatching {
            player?.let { if (it.isPlaying) it.stop(); it.release() }
        }
        player = null
        currentFile = null
        prepared = false
    }

    @Synchronized
    actual fun pause() {
        paused = true
        runCatching { player?.let { if (it.isPlaying) it.pause() } }
    }

    @Synchronized
    actual fun resume() {
        paused = false
        // 未准备完成时 start() 会进入错误态，交给 OnPreparedListener 开播
        if (!prepared) return
        runCatching { player?.let { if (!it.isPlaying) it.start() } }
    }

    @Synchronized
    actual fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        runCatching { player?.setVolume(this.volume, this.volume) }
    }
}
