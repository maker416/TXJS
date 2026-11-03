/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import java.awt.image.BufferedImage
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Assume.assumeTrue

/**
 * 回归：同一进程内切换启用模组再重载，旧模组的贴图（ag.g）与音效（ag.h）缓存必须释放。
 * 原版缓存从不清理；每次重载后调用桌面客户端同一个 DesktopModResourceCache.sweep()。
 * 用真实桌面核心依次重载 baseline → A → B → A+B → none → A-again，每轮 GC 后记录堆与缓存。
 * 需要 rwpp.heap.integrationGameRoot（完整桌面游戏目录、OpenGL、音频设备）。
 */
class ModReloadRetentionIntegrationTest {
    @Test
    fun switchingEnabledModsReleasesCachedResourcesOfDisabledMods() {
        val path = System.getProperty("rwpp.heap.integrationGameRoot")
        assumeTrue("仅在指定 rwpp.heap.integrationGameRoot 时运行真实核心测试", !path.isNullOrBlank())
        val root = File(requireNotNull(path)).absoluteFile
        assertTrue(EngineHeapAnalyzer.isGameRoot(root), "需要完整桌面游戏目录：$root")

        val work = createTempDirectory("rwjs-reload-retention-").toFile()
        try {
            val mods = (0 until MOD_COUNT).map { index ->
                val tag = "${'A' + index}"
                writeFixtureMod(root, File(work, "rwjsRetentionMod$tag"), tag, seed = index + 1)
            }
            val stages = runWorker(root, File(work, "sandbox"), mods)
            val report = formatReport(stages)
            File("build/reports/mod-reload-retention.txt").apply { parentFile.mkdirs() }.writeText(report)
            println(report)

            val byName = stages.associateBy { it.name }
            assertEquals(ReloadRetentionWorker.scenario(MOD_COUNT).map { it.first }, stages.map { it.name })
            // 夹具自检：确认 key 归属方法有效、单位池按启用集合重建，否则下面的残留断言没有意义。
            val base = byName.getValue("baseline")
            val a = byName.getValue("A")
            val b = byName.getValue("B")
            assertEquals(UNITS_PER_MOD, a.unitPoolA, "A 阶段应解析 A 的全部单位")
            assertEquals(0, a.unitPoolB)
            assertTrue(a.imageEntriesA >= IMAGES_PER_MOD && a.soundEntriesA >= SOUNDS_PER_MOD,
                "A 阶段未在缓存中找到 A 的资源，key 归属方法失效：\n$report")
            assertEquals(0, b.unitPoolA, "单位池应在重载时清空，B 阶段不应再含 A 的单位")
            assertEquals(UNITS_PER_MOD, b.unitPoolB)
            assertEquals(0, byName.getValue("none").let { it.unitPoolA + it.unitPoolB })

            val none = byName.getValue("none")
            val again = byName.getValue("A-again")
            val leaks = buildList {
                if (b.imageEntriesA > 0 || b.soundEntriesA > 0)
                    add("切到 B 后 A 仍缓存 ${b.imageEntriesA} 张贴图、${b.soundEntriesA} 个音效")
                if (none.imageEntriesA + none.imageEntriesB + none.soundEntriesA + none.soundEntriesB > 0)
                    add("全部禁用后仍缓存 A/B 贴图 ${none.imageEntriesA}/${none.imageEntriesB}、" +
                        "音效 ${none.soundEntriesA}/${none.soundEntriesB}")
                if (stages.any { it.staleImageEntries > 0 })
                    add("清理后仍有本轮未使用的缓存贴图：${stages.filter { it.staleImageEntries > 0 }.map { it.name }}")
                if (stages.any { it.destroyedLiveImages > 0 })
                    add("销毁了仍被单位定义引用的纹理：${stages.filter { it.destroyedLiveImages > 0 }.map { it.name }}")
            }
            if (leaks.isNotEmpty()) fail("重载后未正确释放已禁用模组的资源缓存：\n${leaks.joinToString("\n")}\n\n$report")
            // 释放后重新启用必须重新解码，而非留下空缓存或半释放对象。
            assertEquals(UNITS_PER_MOD, again.unitPoolA)
            assertTrue(again.imageEntriesA >= IMAGES_PER_MOD && again.soundEntriesA >= SOUNDS_PER_MOD,
                "A-again 应重新加载 A 的资源：\n$report")
            assertEquals(none.imageCacheSize, base.imageCacheSize, "全部禁用后缓存应回到基线大小：\n$report")
            // 依次单独启用更多模组：进程持有的纹理数必须与只启用一个模组时相同。
            // 私有字节受 GL 驱动内存池影响（释放后不一定归还系统），只作报告参考，不作断言。
            val last = stages.last()
            assertTrue(a.liveSlickImages > base.liveSlickImages, "夹具未创建纹理：\n$report")
            assertEquals(a.liveSlickImages, last.liveSlickImages, "依次启用 $MOD_COUNT 个模组后纹理仍在累积：\n$report")
            assertEquals(base.liveSlickImages, none.liveSlickImages, "全部禁用后应只剩原版纹理：\n$report")
        } finally {
            work.deleteRecursively()
        }
    }

    private fun runWorker(root: File, sandbox: File, mods: List<File>): List<RetentionStage> {
        listOf("assets", "res", "font").forEach { name ->
            File(root, name).takeIf { it.isDirectory }?.copyRecursively(File(sandbox, name))
        }
        File(sandbox, "mods/units").mkdirs()
        val output = File(sandbox, "retention.properties")
        val log = File(sandbox, "engine.log")
        val classpath = requireNotNull(System.getProperty("rwpp.heap.testWorkerClasspath")) {
            "缺少 rwpp.heap.testWorkerClasspath，请通过 Gradle test 任务运行"
        }.split(File.pathSeparator).joinToString(File.pathSeparator) { File(it).absolutePath }
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(
            listOf(java, "-Xmx2048m", "-Xss16m", "-Dfile.encoding=UTF-8", "-XX:-DisableExplicitGC",
                "-Djava.library.path=${root.absolutePath}", "-Dorg.lwjgl.librarypath=${root.absolutePath}",
                "-Drwpp.retention.sweep=${System.getProperty("rwpp.retention.sweep", "true")}",
                "-cp", classpath, ReloadRetentionWorker::class.java.name,
                root.absolutePath, output.absolutePath) + mods.map { it.absolutePath },
        ).directory(sandbox).redirectErrorStream(true).redirectOutput(log).start()
        try {
            if (!process.waitFor(8, TimeUnit.MINUTES)) fail("重载复现子进程超时：\n${log.readText().takeLast(4000)}")
            if (process.exitValue() != 0 || !output.isFile) {
                fail("重载复现子进程失败（退出码 ${process.exitValue()}）：\n${log.readText().takeLast(6000)}")
            }
            return RetentionStage.readAll(output)
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }

    /** 以原版 scout 为模板生成单位；贴图/音效为随机噪声，体积足以让堆差异高于 GC 噪声。 */
    private fun writeFixtureMod(root: File, directory: File, tag: String, seed: Int): File {
        directory.mkdirs()
        val source = File(root, "assets/units/scout")
        val template = File(source, "scout.ini").readText()
        File(source, "base_dead.png").copyTo(File(directory, "wreck.png"))
        val random = Random(seed)
        repeat(IMAGES_PER_MOD) { writeNoisePng(File(directory, "body$it.png"), random) }
        repeat(SOUNDS_PER_MOD) { writeNoiseWav(File(directory, "fire$it.wav"), random) }
        repeat(UNITS_PER_MOD) { index ->
            val ini = template
                .replaceFirst(Regex("(?m)^name\\s*[:=][^\\r\\n]*"), "name: rwjsRetention${tag}$index")
                .replaceFirst(Regex("(?m)^image\\s*:[^\\r\\n]*"), "image: body${index % IMAGES_PER_MOD}.png")
                .replaceFirst(Regex("(?m)^image_wreak\\s*:[^\\r\\n]*"), "image_wreak: wreck.png")
                .replaceFirst(Regex("(?m)^shoot_sound\\s*:[^\\r\\n]*"), "shoot_sound: fire${index % SOUNDS_PER_MOD}.wav")
            check(ini.contains("rwjsRetention$tag$index") && ini.contains("fire${index % SOUNDS_PER_MOD}.wav")) {
                "scout.ini 模板结构变化，夹具替换失败"
            }
            File(directory, "unit$index.ini").writeText(ini)
        }
        return directory
    }

    private fun writeNoisePng(file: File, random: Random) {
        val image = BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until IMAGE_SIZE) for (x in 0 until IMAGE_SIZE) image.setRGB(x, y, random.nextInt() or (0xFF shl 24))
        check(ImageIO.write(image, "png", file))
    }

    private fun writeNoiseWav(file: File, random: Random) {
        val sampleRate = 22050
        val samples = sampleRate * 2
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun le32(v: Int) = out.writeInt(Integer.reverseBytes(v))
            fun le16(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            out.writeBytes("RIFF"); le32(36 + samples * 2); out.writeBytes("WAVEfmt ")
            le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
            out.writeBytes("data"); le32(samples * 2)
            repeat(samples) { le16(random.nextInt(-8000, 8000)) }
        }
    }

    private fun formatReport(stages: List<RetentionStage>): String = buildString {
        val base = stages.first()
        appendLine("阶段        GC后堆(MiB) 较基线  direct(MiB) 私有字节(MiB) 较基线  贴图缓存 A/B   音效缓存 A/B  未用贴图(记账MiB) 单位池A/B  存活纹理")
        stages.forEach { s ->
            appendLine("%-10s %10.1f %+7.1f %11.1f %13.1f %+7.1f %5d %3d/%-3d %5d %3d/%-3d %5d (%6.1f) %5d/%d  %6d  误删:%d".format(
                s.name, mib(s.heapBytes), mib(s.heapBytes - base.heapBytes), mib(s.directBytes),
                mib(s.privateBytes), mib(s.privateBytes - base.privateBytes),
                s.imageCacheSize, s.imageEntriesA, s.imageEntriesB, s.soundCacheSize, s.soundEntriesA, s.soundEntriesB,
                s.staleImageEntries, mib(s.staleImageAccountedBytes), s.unitPoolA, s.unitPoolB, s.liveSlickImages,
                s.destroyedLiveImages))
        }
        appendLine("A/B 列只统计 key 含模组目录名的条目（自动阴影等 key 可能不含）；未用贴图 = 本轮无单位引用的缓存贴图。")
    }

    private fun mib(bytes: Long) = bytes / 1024.0 / 1024.0

    private companion object {
        /** A、B 用于切换场景；C 起依次单独启用，验证进程内存不随模组数线性累积。 */
        const val MOD_COUNT = 6
        const val UNITS_PER_MOD = 6
        const val IMAGES_PER_MOD = 6
        const val SOUNDS_PER_MOD = 3
        const val IMAGE_SIZE = 1024
    }
}
