/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import com.jcraft.jogg.Packet
import com.jcraft.jogg.Page
import com.jcraft.jogg.StreamState
import com.jcraft.jogg.SyncState
import com.jcraft.jorbis.Block
import com.jcraft.jorbis.Comment
import com.jcraft.jorbis.DspState
import com.jcraft.jorbis.Info
import io.github.rwpp.logger
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

/**
 * 桌面端背景音乐：WAV 走 `javax.sound.sampled` 原生支持，
 * OGG 用仓库 `lib/` 内置的 jorbis 解码为 PCM 后写 `SourceDataLine`。
 * 播放线程为 daemon；[stop]/切换经 [generation] 代际号打断，全部操作静默失败。
 */
actual object LauncherMusic {
    actual val supportedMusicExtensions: List<String> = listOf("ogg", "wav")

    @Volatile
    private var generation = 0

    @Volatile
    private var volume = 0.5f

    @Volatile
    private var paused = false

    private var thread: Thread? = null
    private var line: SourceDataLine? = null
    private var currentFile: File? = null

    @Synchronized
    actual fun play(file: File, volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        if (currentFile == file && thread?.isAlive == true) {
            line?.let { applyVolume(it) }
            return
        }
        stop()
        currentFile = file
        paused = false
        val gen = ++generation
        thread = Thread({
            while (generation == gen && !Thread.currentThread().isInterrupted) {
                val completed = runCatching {
                    when (file.extension.lowercase()) {
                        "ogg" -> playOggOnce(file, gen)
                        else -> playWavOnce(file, gen)
                    }
                }.onFailure {
                    logger.warn("LauncherMusic: play ${file.name} failed: ${it.message}")
                }.getOrDefault(false)
                if (!completed) break
            }
        }, "launcher-music").apply { isDaemon = true; start() }
    }

    @Synchronized
    actual fun stop() {
        generation++
        currentFile = null
        paused = false
        runCatching {
            line?.stop()
            line?.close()
        }
        line = null
        thread = null
    }

    actual fun pause() {
        paused = true
        runCatching { line?.stop() }
    }

    actual fun resume() {
        paused = false
        runCatching { line?.start() }
    }

    actual fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        line?.let { applyVolume(it) }
    }

    private fun applyVolume(l: SourceDataLine) {
        runCatching {
            val gain = l.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            gain.value = if (volume <= 0f) {
                gain.minimum
            } else {
                (20f * log10(volume)).coerceIn(gain.minimum, gain.maximum)
            }
        }
    }

    private fun openLine(format: AudioFormat): SourceDataLine? {
        return runCatching {
            val l = AudioSystem.getSourceDataLine(format)
            l.open(format)
            applyVolume(l)
            l.start()
            line = l
            l
        }.onFailure { logger.warn("LauncherMusic: no audio line: ${it.message}") }.getOrNull()
    }

    /** 播一遍 WAV；完整播完返回 true（外层循环实现循环播放），被打断/失败返回 false。 */
    private fun playWavOnce(file: File, gen: Int): Boolean {
        AudioSystem.getAudioInputStream(file).use { raw ->
            val base = raw.format
            val pcmFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                base.sampleRate, 16, base.channels, base.channels * 2, base.sampleRate, false
            )
            AudioSystem.getAudioInputStream(pcmFormat, raw).use { pcm ->
                val l = openLine(pcmFormat) ?: return false
                val buf = ByteArray(8192)
                while (generation == gen) {
                    val n = pcm.read(buf)
                    if (n <= 0) break
                    l.write(buf, 0, n)
                }
                runCatching {
                    if (generation == gen) l.drain()
                    l.close()
                }
                line = null
            }
        }
        return generation == gen
    }

    /** 播一遍 OGG（jorbis 手动解码 → 16bit 小端 PCM 交错写入声卡）。 */
    private fun playOggOnce(file: File, gen: Int): Boolean {
        file.inputStream().buffered().use { input ->
            val oy = SyncState()
            val os = StreamState()
            val og = Page()
            val op = Packet()
            val vi = Info()
            val vc = Comment()
            val vd = DspState()
            val vb = Block(vd)
            val bufsize = 4096
            oy.init()

            fun readChunk(): Boolean {
                val index = oy.buffer(bufsize)
                val n = input.read(oy.data, index, bufsize)
                if (n <= 0) return false
                oy.wrote(n)
                return true
            }

            // --- 三个 vorbis 头包 ---
            if (!readChunk() || oy.pageout(og) != 1) return false
            os.init(og.serialno())
            vi.init()
            vc.init()
            if (os.pagein(og) < 0 || os.packetout(op) != 1) return false
            if (vi.synthesis_headerin(vc, op) < 0) return false

            var headers = 0
            while (headers < 2) {
                when (oy.pageout(og)) {
                    0 -> if (!readChunk()) return false
                    1 -> {
                        os.pagein(og)
                        while (headers < 2) {
                            val result = os.packetout(op)
                            if (result == 0) break
                            if (result < 0) return false
                            vi.synthesis_headerin(vc, op)
                            headers++
                        }
                    }
                    else -> return false
                }
            }

            vd.synthesis_init(vi)
            vb.init(vd)

            val channels = vi.channels
            val format = AudioFormat(vi.rate.toFloat(), 16, channels, true, false)
            val l = openLine(format) ?: return false
            val convBuf = ByteArray(bufsize * 2)
            val pcm = Array(1) { emptyArray<FloatArray>() }
            val pcmIndex = IntArray(channels)

            var finished = false
            while (generation == gen && !finished) {
                var result = oy.pageout(og)
                if (result == 0) {
                    val index = oy.buffer(bufsize)
                    val n = input.read(oy.data, index, bufsize)
                    if (n <= 0) {
                        // 文件读完：通知 sync 层后排空剩余页，仍无页则播完
                        oy.wrote(0)
                        result = oy.pageout(og)
                        if (result == 0) break
                    } else {
                        oy.wrote(n)
                        continue
                    }
                }
                if (result != 1) continue // 坏页跳过
                os.pagein(og)
                while (true) {
                    result = os.packetout(op)
                    if (result == 0) break
                    if (result < 0) continue // 坏包跳过
                    if (vb.synthesis(op) == 0) vd.synthesis_blockin(vb)
                    var samples = vd.synthesis_pcmout(pcm, pcmIndex)
                    while (samples > 0) {
                        val capacity = convBuf.size / (2 * channels)
                        val frames = if (samples < capacity) samples else capacity
                        var ptr = 0
                        for (i in 0 until frames) {
                            for (c in 0 until channels) {
                                var v = (pcm[0][c][pcmIndex[c] + i] * 32767f).toInt()
                                if (v > 32767) v = 32767
                                if (v < -32768) v = -32768
                                convBuf[ptr++] = (v and 0xff).toByte()
                                convBuf[ptr++] = (v ushr 8).toByte()
                            }
                        }
                        l.write(convBuf, 0, frames * 2 * channels)
                        vd.synthesis_read(frames)
                        samples = vd.synthesis_pcmout(pcm, pcmIndex)
                    }
                }
                if (og.eos() != 0) finished = true
            }
            runCatching {
                vd.clear()
                vb.clear()
                os.clear()
                oy.clear()
                if (generation == gen) l.drain()
                l.close()
            }
            line = null
            return finished && generation == gen
        }
    }
}
