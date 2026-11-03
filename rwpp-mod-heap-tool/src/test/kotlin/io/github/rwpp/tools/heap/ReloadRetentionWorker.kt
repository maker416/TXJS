/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import com.corrodinggames.rts.game.units.custom.ag as UnitLoader
import com.corrodinggames.rts.game.units.custom.l as UnitDefinition
import com.corrodinggames.rts.gameFramework.l as GameEngine
import java.io.File
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.util.Properties
import kotlin.system.exitProcess

/** 单个重载阶段结束、GC 后的快照。缓存条目按 key 是否包含模组目录名归属到 A/B。 */
internal data class RetentionStage(
    val name: String,
    val heapBytes: Long,
    val directBytes: Long,
    /** Windows 进程私有字节（含驱动为 GL 纹理保留的系统内存）；非 Windows 为 -1。 */
    val privateBytes: Long,
    val imageCacheSize: Int,
    val soundCacheSize: Int,
    val imageEntriesA: Int,
    val imageEntriesB: Int,
    val soundEntriesA: Int,
    val soundEntriesB: Int,
    /** 已缓存但本轮未被任何单位使用（v=false）的贴图，按引擎 u() 记账字节求和。 */
    val staleImageEntries: Int,
    val staleImageAccountedBytes: Long,
    val unitPoolA: Int,
    val unitPoolB: Int,
    /** 当前单位定义直接引用、却已被销毁的 Slick 纹理数；必须为 0。 */
    val destroyedLiveImages: Int,
    /** 引擎静态列表 `java.s.y` 中未销毁的 Slick 纹理数，即进程实际持有的纹理。 */
    val liveSlickImages: Int,
) {
    fun writeTo(properties: Properties, index: Int) {
        val values = listOf(name, heapBytes, directBytes, privateBytes, imageCacheSize, soundCacheSize, imageEntriesA, imageEntriesB,
            soundEntriesA, soundEntriesB, staleImageEntries, staleImageAccountedBytes, unitPoolA, unitPoolB, destroyedLiveImages,
            liveSlickImages)
        properties.setProperty("stage.$index", values.joinToString("|"))
    }

    companion object {
        fun readAll(file: File): List<RetentionStage> {
            val properties = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
            return generateSequence(0) { it + 1 }
                .map { properties.getProperty("stage.$it") }
                .takeWhile { it != null }
                .map { line ->
                    val v = line!!.split("|")
                    RetentionStage(v[0], v[1].toLong(), v[2].toLong(), v[3].toLong(), v[4].toInt(), v[5].toInt(),
                        v[6].toInt(), v[7].toInt(), v[8].toInt(), v[9].toInt(), v[10].toInt(), v[11].toLong(),
                        v[12].toInt(), v[13].toInt(), v[14].toInt(), v[15].toInt())
                }
                .toList()
        }
    }
}

/**
 * 在独立测量 JVM 内依次切换启用集合并走原版 `bZ.a(false, false)` 重载（与桌面
 * ModManager 的 reloadUnitsWithSelection 同一入口），记录每轮 GC 后堆与 ag.g/ag.h 缓存。
 * 参数：游戏目录、模组 A 目录、模组 B 目录、输出 properties。
 */
internal object ReloadRetentionWorker {
    /** 前两个模组为 A/B；其余模组（C、D…）在末尾依次单独启用，用于观察进程内存是否随模组数持续增长。 */
    fun scenario(modCount: Int): List<Pair<String, Set<Int>>> = listOf(
        "baseline" to emptySet(),
        "A" to setOf(0),
        "B" to setOf(1),
        "A+B" to setOf(0, 1),
        "none" to emptySet(),
        "A-again" to setOf(0),
    ) + (2 until modCount).map { "${'A' + it}" to setOf(it) }

    /** -Drwpp.retention.sweep=false 关闭清理，复现原版行为作对照（此时测试按设计失败）。 */
    private val sweepEnabled = System.getProperty("rwpp.retention.sweep", "true").toBoolean()

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(args.size >= 4) { "参数：<gameRoot> <output> <modA> <modB> [更多模组…]" }
            val mods = args.drop(2).map { File(it).canonicalFile }
            val stages = mutableListOf<RetentionStage>()
            EngineModLoader(File(args[0])).use { loader ->
                loader.loadBaseline()
                val engine = GameEngine.B()
                val infos = mods.map { register(engine, it) }
                for ((stage, selection) in scenario(mods.size)) {
                    infos.forEachIndexed { index, info -> info.f = index !in selection }
                    engine.bZ.a(false, false)
                    // 与桌面 ModManagerImpl 相同：成功重载后在同一 GL 线程清理未使用缓存。
                    if (sweepEnabled) io.github.rwpp.desktop.impl.DesktopModResourceCache.sweep()
                    infos.forEachIndexed { index, info ->
                        check(registeredMods(engine).contains(info)) { "$stage: 重载后引擎列表丢失 ${info.c}" }
                        check(info.f == (index !in selection)) { "$stage: ${info.c} 启用状态被引擎改写" }
                        val errors = listOfNotNull(info.R) + info.V.map { it.toString() }
                        check(errors.isEmpty()) { "$stage: ${info.c} 加载失败：${errors.joinToString("；")}" }
                    }
                    stages += snapshot(stage, mods[0].name, mods[1].name, infos[0], infos[1])
                    System.err.println("[RETENTION] ${stages.last()}")
                }
            }
            Properties().apply { stages.forEachIndexed { i, s -> s.writeTo(this, i) } }
                .also { p -> File(args[1]).writer(Charsets.UTF_8).use { p.store(it, null) } }
            exitProcess(0) // 原版音频、Looper 等服务线程由进程退出统一结束。
        } catch (error: Throwable) {
            error.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    private fun register(engine: GameEngine, mod: File): com.corrodinggames.rts.gameFramework.i.b {
        val info = engine.bZ.a(mod.name, mod.name, mod.absolutePath, "rwjs-reload-retention-${mod.name}",
            true, false, false, 0)
        // m 让 bZ.k() 的重扫保留这条手动登记项。
        info.m = true
        info.f = true
        return info
    }

    /** JDK 不提供进程 native 内存；Windows 上用 PowerShell 读本进程 PrivateMemorySize64。 */
    private fun processPrivateBytes(): Long {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return -1
        return runCatching {
            val pid = ProcessHandle.current().pid()
            val process = ProcessBuilder("powershell", "-NoProfile", "-Command", "(Get-Process -Id $pid).PrivateMemorySize64")
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().readText().trim().lines().last().trim().toLong()
                .also { process.waitFor() }
        }.getOrDefault(-1)
    }

    /** 直接读 bZ.e；再调 bZ.k() 会触发一次额外重扫。 */
    private fun registeredMods(engine: GameEngine): List<*> =
        com.corrodinggames.rts.gameFramework.i.a::class.java.getDeclaredField("e").run {
            isAccessible = true
            get(engine.bZ) as List<*>
        }

    private fun snapshot(
        stage: String, dirA: String, dirB: String,
        infoA: com.corrodinggames.rts.gameFramework.i.b, infoB: com.corrodinggames.rts.gameFramework.i.b,
    ): RetentionStage {
        repeat(2) { System.gc(); Thread.sleep(150) }
        val images = (UnitLoader.g as Map<*, *>).entries
            .map { it.key.toString() to it.value as com.corrodinggames.rts.gameFramework.m.e }
        val sounds = (UnitLoader.h as Map<*, *>).keys.map { it.toString() }
        val stale = images.filter { !it.second.v }
        val pool = synchronized(UnitDefinition.c) { UnitDefinition.c.filterIsInstance<UnitDefinition>() }
        val direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
            .firstOrNull { it.name == "direct" }?.memoryUsed ?: -1
        return RetentionStage(
            name = stage,
            heapBytes = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used,
            directBytes = direct,
            privateBytes = processPrivateBytes(),
            imageCacheSize = images.size,
            soundCacheSize = sounds.size,
            imageEntriesA = images.count { it.first.contains(dirA) },
            imageEntriesB = images.count { it.first.contains(dirB) },
            soundEntriesA = sounds.count { it.contains(dirA) },
            soundEntriesB = sounds.count { it.contains(dirB) },
            staleImageEntries = stale.size,
            staleImageAccountedBytes = stale.sumOf { it.second.u().toLong() },
            unitPoolA = pool.count { it.J === infoA },
            unitPoolB = pool.count { it.J === infoB },
            destroyedLiveImages = destroyedLiveImages(pool),
            liveSlickImages = com.corrodinggames.rts.java.s.y.orEmpty()
                .count { (it as com.corrodinggames.rts.java.s).C()?.isDestroyed == false },
        )
    }

    /** 清理不得销毁任何仍被单位定义（含其队伍着色缓存）直接引用的纹理。 */
    private fun destroyedLiveImages(pool: List<UnitDefinition>): Int {
        val live = io.github.rwpp.game.mod.ReferenceFieldScanner(com.corrodinggames.rts.gameFramework.m.e::class.java)
            .collect(pool)
        val withTeams = live.flatMap { image -> listOf(image) + listOf(image.a, image.b, image.c).flatMap { it.orEmpty().toList() } }
        return withTeams.filterIsInstance<com.corrodinggames.rts.java.s>()
            .distinctBy { System.identityHashCode(it) }
            .count { it.C()?.isDestroyed == true }
    }
}
