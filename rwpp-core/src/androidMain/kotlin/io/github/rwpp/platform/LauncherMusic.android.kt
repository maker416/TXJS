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

    @Volatile
    private var paused: Boolean = false

    @Synchronized
    actual fun play(file: File, volume: Float) {
        setVolume(volume)
        val existing = player
        if (currentFile == file && existing != null) {
            if (paused) return
            runCatching { if (!existing.isPlaying) existing.start() }
            return
        }
        stop()
        paused = false
        currentFile = file
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                isLooping = true
                setVolume(volume, volume)
                setOnPreparedListener { p ->
                    runCatching { if (!paused) p.start() }
                }
                setOnErrorListener { p, _, _ ->
                    runCatching { p.release() }
                    if (player == p) {
                        player = null
                        currentFile = null
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
        paused = false
    }

    actual fun pause() {
        paused = true
        runCatching { player?.let { if (it.isPlaying) it.pause() } }
    }

    actual fun resume() {
        paused = false
        runCatching { player?.let { if (!it.isPlaying) it.start() } }
    }

    actual fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        runCatching { player?.setVolume(this.volume, this.volume) }
    }
}
