/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * 模组"堆占用权重"静态扫描器。
 *
 * Android 端 ART 堆上限（largeHeap，典型 512MB）决定了一个模组能否加载成功，
 * 而堆占用与模组**文件体积**几乎无关——图片/音频解码后落在 native 堆，
 * 真正吃托管堆的是 ini 解析出的 Java 对象：逻辑表达式 AST、`[decal_]` 节、
 * `@memory` 声明、`copyFrom` 合并等。
 *
 * 实测标定样本：
 * - 安绒宁静（27MB，重载必 OOM）：ini 文本 597KB / select( 3718 / memory. 6402 / decal 960 → score ≈ 2100
 * - 铁锈酒馆（27MB，加载正常）：ini 文本 3105KB / select( 0 / memory. 46 / decal 17 → score ≈ 41
 *
 * 阈值 [HEAVY_SCORE_THRESHOLD] 保守取低：误报的代价只是多一次重启加载，漏报的代价是 OOM。
 *
 * 仅做文本计数，不解析语义；单个条目超过 [MAX_ENTRY_BYTES] 时只读前 4MB（防巨型文件撑爆内存）。
 */
object ModHeavinessScanner {

    /** 评分达到该值即判定为高内存风险模组。 */
    const val HEAVY_SCORE_THRESHOLD = 300.0

    /** 单个 ini/template 条目最多读取的字节数。 */
    private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024

    private const val INI_TEXT_WEIGHT = 1.0 / 500.0   // 每 KB ini 文本
    private const val SELECT_WEIGHT = 1.0 / 50.0      // 每个 select( 调用
    private const val MEMORY_REF_WEIGHT = 1.0 / 100.0 // 每个 memory. 引用
    private const val DECAL_WEIGHT = 2.0              // 每个 [decal_ 节
    private const val COPY_FROM_WEIGHT = 1.0 / 20.0   // 每个 copyFrom 引用

    /** 扫描结果。各字段为原始计数，[score] 为加权评分。 */
    data class Heaviness(
        val iniTextBytes: Long,
        val selects: Int,
        val memoryRefs: Int,
        val decals: Int,
        val copyFroms: Int,
    ) {
        val score: Double
            get() = iniTextBytes / 1024.0 * INI_TEXT_WEIGHT +
                selects * SELECT_WEIGHT +
                memoryRefs * MEMORY_REF_WEIGHT +
                decals * DECAL_WEIGHT +
                copyFroms * COPY_FROM_WEIGHT
    }

    fun isHeavy(score: Double): Boolean = score >= HEAVY_SCORE_THRESHOLD

    fun isHeavy(heaviness: Heaviness): Boolean = isHeavy(heaviness.score)

    private data class CacheEntry(
        val stamp: Long,
        val length: Long,
        val result: Heaviness,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * 扫描模组文件（.rwmod zip / 文件夹 / 单个 .ini）并计算堆占用权重。
     * 结果按路径 + 修改时间 + 大小缓存，模组未变化时重复调用零开销。
     */
    fun scan(file: File): Heaviness {
        if (!file.exists()) return Heaviness(0, 0, 0, 0, 0)
        val stamp = file.lastModified()
        val length = file.length()
        val key = file.absolutePath
        cache[key]?.let { if (it.stamp == stamp && it.length == length) return it.result }

        val result = runCatching { doScan(file) }.getOrDefault(Heaviness(0, 0, 0, 0, 0))
        cache[key] = CacheEntry(stamp, length, result)
        return result
    }

    /** 仅供测试观察缓存规模。 */
    internal fun cacheSize(): Int = cache.size

    private fun doScan(file: File): Heaviness {
        var iniTextBytes = 0L
        var selects = 0
        var memoryRefs = 0
        var decals = 0
        var copyFroms = 0

        fun consume(text: String) {
            iniTextBytes += text.length.toLong()
            val lower = text.lowercase()
            selects += lower.countOccurrences("select(")
            memoryRefs += lower.countOccurrences("memory.")
            decals += lower.countOccurrences("[decal_")
            copyFroms += lower.countOccurrences("copyfrom")
        }

        when {
            file.isDirectory -> {
                file.walkTopDown()
                    .filter { it.isFile && isLogicFile(it.name) }
                    .forEach { consume(readCapped(it)) }
            }
            file.extension.equals("rwmod", ignoreCase = true) -> {
                ZipFile(file).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && isLogicFile(it.name) }
                        .forEach { entry ->
                            zip.getInputStream(entry).use { consume(readCapped(it)) }
                        }
                }
            }
            isLogicFile(file.name) -> consume(readCapped(file))
        }

        return Heaviness(iniTextBytes, selects, memoryRefs, decals, copyFroms)
    }

    private fun isLogicFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".ini") || lower.endsWith(".template")
    }

    private fun readCapped(file: File): String =
        file.inputStream().use { readCapped(it) }

    private fun readCapped(input: InputStream): String {
        val buffer = ByteArray(8192)
        val out = StringBuilder()
        var remaining = MAX_ENTRY_BYTES
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            out.append(String(buffer, 0, read, Charsets.UTF_8))
            remaining -= read
        }
        return out.toString()
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = indexOf(needle, index)
            if (index < 0) return count
            count++
            index += needle.length
        }
    }
}
