/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import io.github.rwpp.game.mod.heap.formatBytes
import java.io.File
import java.util.Properties

internal data class MeasuredUnitHeap(val name: String, val fileName: String, val exclusiveBytes: Long)

internal data class MeasuredModHeap(
    val sourceName: String,
    val baselineHeapBytes: Long,
    val loadedHeapBytes: Long,
    val sampledPeakHeapBytes: Long,
    val heapLimitBytes: Long,
    val definitionHeapBytes: Long,
    val sharedDefinitionHeapBytes: Long,
    val textureAccountedBytes: Long,
    val soundAccountedBytes: Long,
    val runtimeDescription: String,
    val units: List<MeasuredUnitHeap>,
    val warnings: List<String> = emptyList(),
) {
    val unitCount get() = units.size
    val incrementalHeapBytes get() = loadedHeapBytes - baselineHeapBytes

    fun formatReport(): String = buildString {
        appendLine("模组：$sourceName")
        appendLine("测量环境：$runtimeDescription")
        appendLine("真实桌面核心加载；每个模组使用独立 JVM。")
        appendLine()
        appendLine("GC 后堆净增：${formatBytes(incrementalHeapBytes)} ($incrementalHeapBytes 字节)")
        appendLine("原版基线堆：${formatBytes(baselineHeapBytes)}")
        appendLine("加载完成后堆：${formatBytes(loadedHeapBytes)}")
        appendLine("加载期间采样堆峰值（10 ms）：${formatBytes(sampledPeakHeapBytes)}")
        appendLine("测量进程最大堆：${formatBytes(heapLimitBytes)}")
        appendLine("实际加载单位：$unitCount")
        appendLine("单位对象图去重堆：${formatBytes(definitionHeapBytes)} ($definitionHeapBytes 字节)")
        appendLine("其中多单位共享对象：${formatBytes(sharedDefinitionHeapBytes)}")
        appendLine("引擎贴图记账：${formatBytes(textureAccountedBytes)}")
        appendLine("引擎音频记账：${formatBytes(soundAccountedBytes)}")
        appendLine()
        appendLine("计量口径：")
        appendLine("堆净增来自 MemoryMXBean，在完整加载前后执行 GC；包含单位、解析缓存和其他加载开销，可能受 GC 和共享缓存影响。")
        appendLine("单位对象图使用 Java Instrumentation 获取实际对象大小并按对象身份去重；排行只列独占对象，共享对象单列。引擎、渲染器、音频工厂、上下文和单位实例作为遍历边界。对象图是可达堆，不是堆快照 dominator 分析得到的保留堆。")
        appendLine("贴图和音频数字是引擎 G/H 记账，不能与 Java 堆相加得到进程总内存；显存、native/direct 内存及运行中的单位实例不在堆净增口径中。")
        appendLine("结果只对应当前桌面 JVM；不换算为 Android ART 堆。采样峰值可能漏掉两次采样之间的瞬时峰值。")
        appendLine()
        appendLine("单位独占对象堆排行：")
        units.sortedByDescending { it.exclusiveBytes }.forEach {
            appendLine("${formatBytes(it.exclusiveBytes)}\t${it.name}\t${it.fileName}")
        }
        if (warnings.isNotEmpty()) {
            appendLine()
            appendLine("加载提示：")
            warnings.forEach { appendLine("- $it") }
        }
    }

    fun unitCsv(): String = buildString {
        append("单位名称,定义文件,实测独占堆字节\r\n")
        units.forEach { unit ->
            fun cell(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
            append("${cell(unit.name)},${cell(unit.fileName)},${unit.exclusiveBytes}\r\n")
        }
    }

    fun writeTo(file: File) {
        val p = Properties()
        p.setProperty("version", "1")
        p.setProperty("source", sourceName)
        p.setProperty("runtime", runtimeDescription)
        listOf(
            "baseline" to baselineHeapBytes, "loaded" to loadedHeapBytes,
            "peak" to sampledPeakHeapBytes, "limit" to heapLimitBytes,
            "definitions" to definitionHeapBytes, "shared" to sharedDefinitionHeapBytes,
            "textures" to textureAccountedBytes, "sounds" to soundAccountedBytes,
        ).forEach { (key, value) -> p.setProperty(key, value.toString()) }
        p.setProperty("units", units.size.toString())
        units.forEachIndexed { i, unit ->
            p.setProperty("unit.$i.name", unit.name)
            p.setProperty("unit.$i.file", unit.fileName)
            p.setProperty("unit.$i.bytes", unit.exclusiveBytes.toString())
        }
        p.setProperty("warnings", warnings.size.toString())
        warnings.forEachIndexed { i, warning -> p.setProperty("warning.$i", warning) }
        file.outputStream().use { p.store(it, "RWJS real engine heap measurement") }
    }

    companion object {
        fun readFrom(file: File): MeasuredModHeap {
            val p = Properties().apply { file.inputStream().use(::load) }
            check(p.getProperty("version") == "1") { "测量结果格式不支持" }
            fun value(key: String) = requireNotNull(p.getProperty(key)) { "测量结果缺少 $key" }
            fun bytes(key: String) = value(key).toLong()
            return MeasuredModHeap(
                value("source"), bytes("baseline"), bytes("loaded"), bytes("peak"), bytes("limit"),
                bytes("definitions"), bytes("shared"), bytes("textures"), bytes("sounds"), value("runtime"),
                List(value("units").toInt()) { i ->
                    MeasuredUnitHeap(value("unit.$i.name"), value("unit.$i.file"), bytes("unit.$i.bytes"))
                },
                List(value("warnings").toInt()) { value("warning.$it") },
            )
        }
    }
}
