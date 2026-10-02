/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod.heap

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

/**
 * 估算「游戏把这个模组加载完之后，单位定义会占多少 JVM/ART 堆」。
 *
 * 模组卡片上的 RAM 来自引擎 `b.G + b.H`（贴图像素宽×高×8，加上解码后的音频），
 * Android 上贴图在 native 内存，不占 largeHeap。真正把堆撑爆的是单位类型对象
 * `custom.l` 以及 `select` / `memory` / 比较式编译出来的逻辑节点；`copyFrom` 会把父单位的
 * 这些节点再复制进每个子单位。
 *
 * 字节数按桌面 `game-lib` 的类布局、64 位压缩指针估算（与 Android 同结构的混淆类）。
 * 它是加载完成后的保留堆，不是进程总占用，也不含原版自带单位。
 */
object ModHeapEstimator {
    /** Android `largeHeap` 配额，用来给报告一个参照。 */
    const val ANDROID_LARGE_HEAP_BYTES = 512L * 1024L * 1024L

    /**
     * 每个单位类型固定开销。
     *
     * `custom.l` 浅层 1248 字节；构造器另外 new 20 个空 `utility.m`、6 个空 `ArrayList`
     * （各 24 字节，空数组是共享的），以及 `VariableMapping`（24）和它内部的空列表（24）。
     */
    const val UNIT_FIXED_BYTES = 1248 + 20 * 24 + 6 * 24 + 48

    /** 带一个字段的逻辑节点（如 `IsOnTeam`）浅层大小。比较/and/or 包装是 16。 */
    private const val LOGIC_CALL_BYTES = 24
    private const val LOGIC_WRAPPER_BYTES = 16
    private const val LOGIC_LITERAL_BYTES = 16

    /** 每个 `memory.xxx` 在单位上的 `VariableDefinition`（24）加列表槽位。 */
    private const val MEMORY_DEF_BYTES = 32

    /**
     * `[decal_]` / `[action_]` 等小节配置对象的下限。逻辑表达式和贴图另计。
     */
    private const val CHILD_SECTION_BYTES = 64

    /** 与引擎一致：`copyFrom` 超过 10 层视为循环。 */
    private const val MAX_COPY_DEPTH = 10
    private const val MAX_INI_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_INI_BYTES = 128L * 1024 * 1024
    private const val IMAGE_HEADER_BYTES = 256 * 1024

    private val childSectionPrefixes = listOf(
        "decal_",
        "action_",
        "hiddenaction_",
        "turret_",
        "attachment_",
        "leg_",
        "arm_",
        "effect_",
        "animation_",
        "placementrule_",
    )

    fun estimate(file: File, onProgress: (String) -> Unit = {}): ModHeapEstimate {
        checkCancelled()
        require(file.exists()) { "找不到: ${file.path}" }
        val loaded = when {
            file.isDirectory -> loadDirectory(file, onProgress)
            file.extension.equals("rwmod", ignoreCase = true) ||
                file.extension.equals("zip", ignoreCase = true) -> loadZip(file, onProgress)
            file.extension.equals("ini", ignoreCase = true) -> LoadedMod(
                iniFiles = listOf(file.name to file.inputStream().use { readIni(it) }),
            )
            else -> throw IllegalArgumentException("无法识别的文件: ${file.name}。请传入 .rwmod、.zip、目录或 .ini")
        }
        onProgress("正在解析单位定义与继承关系…")
        return estimateSources(file.name, loaded.iniFiles, loaded.images, loaded.warnings, loaded.rootPath)
    }

    fun estimateSources(
        sourceName: String,
        iniFiles: List<Pair<String, String>>,
        images: List<ImageExtent> = emptyList(),
        readWarnings: List<String> = emptyList(),
        rootPath: String = "",
    ): ModHeapEstimate {
        val warnings = readWarnings.toMutableList()
        val parsed = iniFiles.map { (name, text) ->
            checkCancelled()
            ParsedUnit(name, parseIni(text))
        }
        val index = UnitIndex(rootPath)
        for (unit in parsed) {
            index.add(unit)
        }
        index.duplicateWarning()?.let { warnings += it }
        val expand = ExpandState()
        val lines = parsed.filter { it.fileName.endsWith(".ini", true) }.mapNotNull { unit ->
            checkCancelled()
            // 原版在 copyFrom 之前检查当前文件的 dont_load，不继承模板的这个标志。
            if (unit.sections["core"]?.get("dont_load")?.equals("true", true) == true) {
                null
            } else {
                var expanded = expand.expand(unit, index, ArrayDeque())
                index.automaticTemplate(unit)?.let { template ->
                    expanded = merge(expand.expand(template, index, ArrayDeque()), expanded)
                }
                if (expanded["core"] == null) return@mapNotNull null
                costUnit(unitName(unit), unit.fileName, expanded)
            }
        }
        if (lines.isEmpty()) warnings += "未找到可加载的单位定义，不能据此判断模组占用为零。加固包或缺失模板可能无法静态分析。"
        if (parsed.any { unit -> unit.sections.values.any { keys -> keys.values.any { "\u0024{" in it } } }) {
            warnings += "包含变量替换表达式，静态工具未执行预处理，部分表达式开销可能被低估。"
        }
        if (parsed.any { unit -> unit.sections.values.any { "@copyfromsection" in it } }) {
            warnings += "包含 @copyFromSection，小节继承尚未模拟，相关开销可能被低估。"
        }
        warnings += expand.warnings()
        val imageBytes = images.sumOf { it.width.toLong() * it.height.toLong() * 8L }
        return ModHeapEstimate(
            sourceName = sourceName,
            unitCount = lines.size,
            logicNodes = lines.sumOf { it.logicNodes },
            childSections = lines.sumOf { it.childSections },
            memoryVariables = lines.sumOf { it.memoryVariables },
            heapBytes = lines.sumOf { it.heapBytes },
            imageAccountedBytes = imageBytes,
            imageCount = images.size,
            units = lines.sortedByDescending { it.heapBytes },
            warnings = warnings.distinct(),
        )
    }

    internal fun expressionCost(raw: String, depth: Int = 0): LogicCost {
        val text = stripOuterParentheses(raw.trim())
        if (text.isEmpty()) return LogicCost.EMPTY
        if (depth > 64) {
            return LogicCost(stringBytes(text), 0, memoryNamesIn(text))
        }
        val orParts = splitTopLevel(text, "or")
        if (orParts.size > 1) return join(orParts, depth)
        val andParts = splitTopLevel(text, "and")
        if (andParts.size > 1) return join(andParts, depth)
        if (text.length > 4 && text.startsWith("not ", ignoreCase = true)) {
            val inner = expressionCost(text.substring(4), depth + 1)
            return LogicCost(LOGIC_WRAPPER_BYTES + inner.bytes, 1 + inner.nodes, inner.memoryNames)
        }
        val compare = findCompare(text)
        if (compare != null) {
            val left = expressionCost(compare.first, depth + 1)
            val right = expressionCost(compare.second, depth + 1)
            return LogicCost(
                LOGIC_WRAPPER_BYTES + left.bytes + right.bytes,
                1 + left.nodes + right.nodes,
                left.memoryNames + right.memoryNames,
            )
        }
        val call = parseCall(text)
        if (call != null) {
            var bytes = LOGIC_CALL_BYTES
            var nodes = 1
            val names = mutableSetOf<String>()
            for (arg in call.second) {
                val eq = indexOfEquals(arg)
                val value = if (eq >= 0) arg.substring(eq + 1).trim() else arg.trim()
                if (eq >= 0 && !isLogicValue(value)) {
                    if (!isPrimitiveLiteral(value)) {
                        bytes += stringBytes(unquote(value))
                    }
                } else if (value.isNotEmpty()) {
                    val child = expressionCost(value, depth + 1)
                    bytes += child.bytes
                    nodes += child.nodes
                    names += child.memoryNames
                }
            }
            names += memoryNamesIn(text)
            return LogicCost(bytes, nodes, names)
        }
        if (isPrimitiveLiteral(text)) {
            return LogicCost(LOGIC_LITERAL_BYTES, 1, emptySet())
        }
        return LogicCost(LOGIC_CALL_BYTES + stringBytes(unquote(text)), 1, memoryNamesIn(text))
    }

    internal fun isLogicValue(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        if (text.length >= 2 && ((text.first() == '"' && text.last() == '"') ||
                (text.first() == '\'' && text.last() == '\''))) return false
        if ('(' in text) return true
        if (text.contains("memory.", ignoreCase = true)) return true
        if (text.equals("self", ignoreCase = true) || text.contains("self.", ignoreCase = true)) return true
        if (splitTopLevel(text, "and").size > 1 || splitTopLevel(text, "or").size > 1) return true
        return findCompare(text) != null
    }

    internal fun stringBytes(text: String): Int {
        val payload = if (text.all { it.code < 128 }) text.length else text.length * 2
        return 24 + align8(16 + payload)
    }

    private fun costUnit(name: String, fileName: String, sections: IniSections): UnitHeap {
        var bytes = UNIT_FIXED_BYTES.toLong()
        var nodes = 0
        val memories = mutableSetOf<String>()
        var childSections = 0
        val storedName = sections["core"]?.get("name")?.trim().orEmpty()
        if (storedName.isEmpty()) {
            bytes += stringBytes(name)
        }
        for ((section, keys) in sections) {
            if (childSectionPrefixes.any { section.startsWith(it) }) {
                childSections++
                bytes += CHILD_SECTION_BYTES
            }
            for ((key, value) in keys) {
                if (isMetaKey(key)) continue
                if (section == "core" && key == "name") {
                    bytes += stringBytes(unquote(value.trim()))
                    continue
                }
                val cost = valueCost(value)
                bytes += cost.bytes
                nodes += cost.nodes
                memories += cost.memoryNames
            }
        }
        bytes += memories.size * MEMORY_DEF_BYTES
        return UnitHeap(
            name = name,
            fileName = fileName,
            heapBytes = bytes.toLong(),
            logicNodes = nodes,
            childSections = childSections,
            memoryVariables = memories.size,
        )
    }

    private fun valueCost(value: String): LogicCost {
        val text = value.trim()
        if (text.isEmpty() || isPrimitiveLiteral(text)) return LogicCost.EMPTY
        if (isLogicValue(text)) return expressionCost(text)
        return LogicCost(stringBytes(unquote(text)), 0, emptySet())
    }

    private fun join(parts: List<String>, depth: Int): LogicCost {
        val children = parts.map { expressionCost(it, depth + 1) }
        val array = align8(16 + 4 * children.size)
        return LogicCost(
            LOGIC_WRAPPER_BYTES + array + children.sumOf { it.bytes },
            1 + children.sumOf { it.nodes },
            children.flatMap { it.memoryNames }.toSet(),
        )
    }

    private class UnitIndex(private val rootPath: String) {
        private val byKey = linkedMapOf<String, ParsedUnit>()
        private val byPath = linkedMapOf<String, ParsedUnit>()
        private val ambiguousKeys = mutableSetOf<String>()
        private val duplicateNames = linkedSetOf<String>()

        fun add(unit: ParsedUnit) {
            byPath[normalizePath(unit.fileName).lowercase(Locale.ROOT)] = unit
            val name = unitName(unit)
            put(name, unit, allowReplace = true)
            val file = File(unit.fileName.replace('\\', '/')).name
            put(file, unit, allowReplace = false)
            val stem = file.substringBeforeLast('.')
            if (!stem.equals(file, ignoreCase = true)) {
                put(stem, unit, allowReplace = false)
            }
        }

        fun duplicateWarning(): String? {
            if (duplicateNames.isEmpty()) return null
            return "单位名重复 ${duplicateNames.size} 个（copyFrom 优先按相对路径解析，歧义名称不猜测）: " +
                duplicateNames.take(8).joinToString("、")
        }

        fun find(target: String, from: ParsedUnit): ParsedUnit? {
            val requested = target.replace('\\', '/')
            // 原版明确拒绝含 .. 的 copyFrom，不能把非法路径当作正常继承。
            if (".." in requested) return null
            val path = if (requested.startsWith("ROOT:", true)) {
                "$rootPath/${requested.substringAfter(':')}"
            } else {
                "${from.fileName.replace('\\', '/').substringBeforeLast('/', "")}/$requested"
            }
            byPath[normalizePath(path).lowercase(Locale.ROOT)]?.let { return it }
            if (requested.contains('/') || requested.contains(':')) return null
            val key = requested.lowercase(Locale.ROOT)
            return if (key in ambiguousKeys) null else byKey[key]
        }

        fun automaticTemplate(unit: ParsedUnit): ParsedUnit? {
            var directory = normalizePath(unit.fileName).substringBeforeLast('/', "")
            while (true) {
                val key = normalizePath("$directory/all-units.template").lowercase(Locale.ROOT)
                byPath[key]?.let { return it }
                if (directory.isEmpty() || directory.equals(rootPath, true)) return null
                directory = directory.substringBeforeLast('/', "")
            }
        }

        private fun put(key: String, unit: ParsedUnit, allowReplace: Boolean) {
            if (key.isBlank()) return
            val normalized = key.lowercase(Locale.ROOT)
            val existing = byKey[normalized]
            if (existing == null) {
                byKey[normalized] = unit
            } else if (allowReplace && existing !== unit) {
                duplicateNames += key
                ambiguousKeys += normalized
            } else if (existing !== unit) {
                ambiguousKeys += normalized
            }
        }
    }

    private class ExpandState {
        private val cache = mutableMapOf<ParsedUnit, IniSections>()
        private val missing = linkedSetOf<String>()
        private val rootTemplates = linkedSetOf<String>()
        private val truncated = linkedSetOf<String>()

        fun expand(unit: ParsedUnit, index: UnitIndex, stack: ArrayDeque<ParsedUnit>): IniSections {
            cache[unit]?.let { return it }
            if (stack.size > MAX_COPY_DEPTH || unit in stack) {
                truncated += unitName(unit)
                return unit.sections
            }
            stack.addLast(unit)
            var merged: IniSections = emptyMap()
            for (target in copyFromTargets(unit)) {
                if (target.equals("NONE", ignoreCase = true)) continue
                if (target.equals("ROOT", ignoreCase = true) || target.startsWith("CORE:", ignoreCase = true) ||
                    target.startsWith("COMMON:", ignoreCase = true)) {
                    rootTemplates += target
                    continue
                }
                val parent = index.find(target, unit)
                if (parent == null) {
                    missing += target
                    continue
                }
                merged = merge(merged, expand(parent, index, stack))
            }
            stack.removeLast()
            val resolved = merge(merged, unit.sections)
            cache[unit] = resolved
            return resolved
        }

        fun warnings(): List<String> {
            val lines = mutableListOf<String>()
            if (truncated.isNotEmpty()) {
                lines += "copyFrom 层级过深或成环，已截断: ${truncated.take(8).joinToString("、")}"
            }
            if (rootTemplates.isNotEmpty()) {
                lines += "有 ${rootTemplates.size} 个 copyFrom 指向原版模板，模板不在模组包里，未计入堆: " +
                    rootTemplates.take(6).joinToString("、")
            }
            if (missing.isNotEmpty()) {
                lines += "找不到 ${missing.size} 个 copyFrom 目标: ${missing.take(8).joinToString("、")}"
            }
            return lines
        }

        private fun copyFromTargets(unit: ParsedUnit): List<String> {
            val raw = unit.sections["core"]?.get("copyfrom")?.trim().orEmpty()
            if (raw.isEmpty()) return emptyList()
            return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private fun merge(parent: IniSections, child: IniSections): IniSections {
        val out = linkedMapOf<String, MutableMap<String, String>>()
        for ((section, keys) in parent) {
            out[section] = linkedMapOf<String, String>().apply { putAll(keys) }
        }
        for ((section, keys) in child) {
            if (sectionSkipped(keys)) {
                out[section] = linkedMapOf<String, String>().apply { putAll(keys) }
            } else {
                val dest = out.getOrPut(section) { linkedMapOf() }
                dest.putAll(keys)
            }
        }
        return out
    }

    private fun sectionSkipped(keys: Map<String, String>?): Boolean {
        if (keys == null) return false
        val value = keys["copyfrom_skipthissection"] ?: keys["@copyfrom_skipthissection"] ?: return false
        return value.equals("true", ignoreCase = true) || value == "1"
    }

    private fun isMetaKey(key: String): Boolean {
        val bare = key.removePrefix("@")
        return bare == "copyfrom" || bare == "copyfrom_skipthissection" || bare == "dont_load" || key.startsWith("@")
    }

    private fun unitName(unit: ParsedUnit): String {
        val named = unit.sections["core"]?.get("name")?.trim().orEmpty()
        if (named.isNotEmpty()) return named
        return File(unit.fileName).nameWithoutExtension
    }

    private fun loadDirectory(root: File, onProgress: (String) -> Unit): LoadedMod {
        val ini = mutableListOf<Pair<String, String>>()
        val images = mutableListOf<ImageExtent>()
        val warnings = mutableListOf<String>()
        var totalBytes = 0L
        root.walkTopDown().onFail { file, error -> warnings += "无法读取 ${file.name}: ${error.message}" }
            .filter { it.isFile }.forEach { file ->
            checkCancelled()
            val name = file.relativeTo(root).invariantSeparatorsPath
            if (!isIniName(name) && !isImageName(name)) return@forEach
            onProgress("读取 $name")
            try {
                if (isIniName(name)) {
                    val text = file.inputStream().use { readIni(it) }
                    totalBytes += text.toByteArray(Charsets.UTF_8).size
                    require(totalBytes <= MAX_TOTAL_INI_BYTES) { "单位配置总量超过 128 MiB，请分批分析" }
                    ini += name to text
                } else {
                    file.inputStream().use { readImage(name, it.readBounded(IMAGE_HEADER_BYTES), images, warnings) }
                }
            } catch (e: java.io.IOException) {
                warnings += "读不出 $name: ${e.message}"
            }
        }
        return LoadedMod(ini, images, warnings)
    }

    private fun loadZip(file: File, onProgress: (String) -> Unit): LoadedMod {
        val ini = mutableListOf<Pair<String, String>>()
        val images = mutableListOf<ImageExtent>()
        val warnings = mutableListOf<String>()
        var totalBytes = 0L
        var rootPath = ""
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    checkCancelled()
                    val entry = entries.nextElement()
                    val name = entry.name.replace('\\', '/').trimEnd('/')
                    val base = name.substringAfterLast('/')
                    if (entry.isDirectory || base.isEmpty()) continue
                    if (base.equals("mod-info.txt", true)) {
                        rootPath = name.substringBeforeLast('/', "")
                        continue
                    }
                    // 音频等无关内容完全不解压；贴图只读文件头，不解码像素。
                    if (!isIniName(name) && !isImageName(name)) continue
                    onProgress("读取 $name")
                    try {
                        zip.getInputStream(entry).use { input ->
                            if (isIniName(name)) {
                                val text = readIni(input)
                                totalBytes += text.toByteArray(Charsets.UTF_8).size
                                require(totalBytes <= MAX_TOTAL_INI_BYTES) { "单位配置总量超过 128 MiB，请分批分析" }
                                ini += name to text
                            } else {
                                readImage(name, input.readBounded(IMAGE_HEADER_BYTES), images, warnings)
                            }
                        }
                    } catch (e: java.io.IOException) {
                        warnings += "读不出 ${entry.name}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("无法打开压缩包（损坏或加固的 rwmod 可能不支持）: ${e.message}", e)
        }
        return LoadedMod(ini, images, warnings, rootPath)
    }

    private fun isIniName(name: String): Boolean = name.endsWith(".ini", true) || name.endsWith(".template", true)

    private fun readIni(input: InputStream): String {
        val bytes = input.readBounded(MAX_INI_BYTES + 1)
        require(bytes.size <= MAX_INI_BYTES) { "单份单位配置超过 8 MiB，已停止分析" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readImage(
        name: String,
        bytes: ByteArray,
        images: MutableList<ImageExtent>,
        warnings: MutableList<String>,
    ) {
        val extent = imageExtent(name, bytes)
        if (extent == null) {
            warnings += "读不出贴图尺寸: $name"
        } else {
            images += extent
        }
    }

    private fun isImageName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "")
        return ext.equals("png", true) || ext.equals("jpg", true) ||
            ext.equals("jpeg", true) || ext.equals("gif", true)
    }
}

internal data class LogicCost(
    val bytes: Int,
    val nodes: Int,
    val memoryNames: Set<String>,
) {
    companion object {
        val EMPTY = LogicCost(0, 0, emptySet())
    }
}

// 继承缓存使用对象身份；避免对整份配置 Map 反复计算 data class 的 hashCode。
private class ParsedUnit(
    val fileName: String,
    val sections: IniSections,
)

private data class LoadedMod(
    val iniFiles: List<Pair<String, String>>,
    val images: List<ImageExtent> = emptyList(),
    val warnings: List<String> = emptyList(),
    val rootPath: String = "",
)

private typealias IniSections = Map<String, Map<String, String>>

data class ImageExtent(val width: Int, val height: Int)

data class UnitHeap(
    val name: String,
    val fileName: String,
    val heapBytes: Long,
    val logicNodes: Int,
    val childSections: Int,
    val memoryVariables: Int,
)

data class ModHeapEstimate(
    val sourceName: String,
    val unitCount: Int = 0,
    val logicNodes: Int = 0,
    val childSections: Int = 0,
    val memoryVariables: Int = 0,
    val heapBytes: Long = 0,
    val imageAccountedBytes: Long = 0,
    val imageCount: Int = 0,
    val units: List<UnitHeap> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    fun formatReport(): String = buildString {
        appendLine("模组: $sourceName")
        if (unitCount == 0) appendLine("未找到可加载的单位，本次堆占用无法确认。")
        appendLine("单位定义: $unitCount")
        appendLine("逻辑节点: $logicNodes")
        if (memoryVariables > 0) appendLine("memory 变量: $memoryVariables")
        if (childSections > 0) appendLine("小节对象: $childSections")
        appendLine("单位定义保留堆（静态估算）: ${formatBytes(heapBytes)}（$heapBytes 字节）")
        val limit = ModHeapEstimator.ANDROID_LARGE_HEAP_BYTES
        val percent = heapBytes * 100.0 / limit
        appendLine("约占 512 MiB 参考预算的 ${"%.1f".format(Locale.ROOT, percent)}%（设备实际堆上限可能不同）")
        appendLine(
            "新旧同规模单位表并存参考: ${formatBytes(heapBytes * 2)}（不代表实测重载峰值）"
        )
        appendLine("不含原版单位、运行时单位实例、解析临时对象、音频及进程其他开销；不能据此保证不会 OOM。")
        if (imageCount > 0) {
            val engineMb = imageAccountedBytes / 1_000_000.0
            appendLine(
                "贴图 $imageCount 张，引擎按宽×高×8 计入 ${"%.2f".format(Locale.ROOT, engineMb)} MB" +
                    "（$imageAccountedBytes 字节）。Android 上属于 native 参考值，不在上面的堆里；包括包内未使用的贴图"
            )
        }
        appendLine("静态模型尚未用实测堆快照校准；变量替换、小节继承及部分表达式语法可能造成偏差。")
        if (units.isNotEmpty()) {
            appendLine("堆占用最高的单位:")
            units.take(10).forEach { unit ->
                appendLine(
                    "  ${unit.name}  ${formatBytes(unit.heapBytes)}  节点 ${unit.logicNodes}"
                )
            }
        }
        if (warnings.isNotEmpty()) {
            appendLine("注意:")
            warnings.distinct().forEach { appendLine("  $it") }
        }
    }.trimEnd()
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KiB".format(Locale.ROOT, kb)
    return "%.2f MiB".format(Locale.ROOT, kb / 1024.0)
}

private fun checkCancelled() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("分析已取消")
}

// 保持 Android minSdk 26 兼容，同时让较大文件读取可响应线程取消。
private fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    while (output.size() < limit) {
        checkCancelled()
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun normalizePath(path: String): String {
    val parts = ArrayDeque<String>()
    for (part in path.replace('\\', '/').split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeLast() else parts.addLast("..")
            else -> parts.addLast(part)
        }
    }
    return parts.joinToString("/")
}

private fun stripOuterParentheses(raw: String): String {
    var text = raw
    while (text.startsWith('(') && text.endsWith(')')) {
        var depth = 0
        var quote = '\u0000'
        var wraps = true
        for (i in text.indices) {
            val c = text[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
                continue
            }
            when (c) {
                '\'', '"' -> quote = c
                '(' -> depth++
                ')' -> depth--
            }
            if (depth == 0 && i != text.lastIndex) { wraps = false; break }
        }
        if (!wraps || depth != 0) break
        text = text.substring(1, text.lastIndex).trim()
    }
    return text
}

internal fun align8(value: Int): Int = (value + 7) and -8

private fun isPrimitiveLiteral(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.equals("true", ignoreCase = true) ||
        trimmed.equals("false", ignoreCase = true) ||
        trimmed.toDoubleOrNull() != null
}

private fun unquote(text: String): String {
    val trimmed = text.trim()
    if (trimmed.length >= 2 &&
        ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'")))
    ) {
        return trimmed.substring(1, trimmed.length - 1)
    }
    return trimmed
}

private fun memoryNamesIn(text: String): Set<String> =
    MEMORY_PATTERN.findAll(text).map { it.groupValues[1].lowercase() }.toSet()

private val MEMORY_PATTERN = Regex("""(?i)(?<![A-Za-z0-9_])memory\.([A-Za-z_][\w]*)""")

private fun indexOfEquals(arg: String): Int {
    var depth = 0
    var quote = '\u0000'
    for (i in arg.indices) {
        val c = arg[i]
        if (quote != '\u0000') {
            if (c == quote) quote = '\u0000'
            continue
        }
        when (c) {
            '"', '\'' -> quote = c
            '(' -> depth++
            ')' -> if (depth > 0) depth--
            '=' -> if (depth == 0 && arg.getOrNull(i + 1) != '=' &&
                arg.getOrNull(i - 1) !in listOf('=', '!', '<', '>')) return i
        }
    }
    return -1
}

internal fun splitTopLevel(source: String, keyword: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var quote = '\u0000'
    var start = 0
    var i = 0
    val lower = source.lowercase()
    val key = keyword.lowercase()
    while (i < source.length) {
        val c = source[i]
        if (quote != '\u0000') {
            if (c == quote) quote = '\u0000'
            i++
            continue
        }
        if (c == '"' || c == '\'') {
            quote = c
            i++
            continue
        }
        if (c == '(') depth++
        else if (c == ')' && depth > 0) depth--
        if (depth == 0 && lower.startsWith(key, i) && keywordBounded(source, i, key.length)) {
            parts += source.substring(start, i).trim()
            i += key.length
            start = i
            continue
        }
        i++
    }
    parts += source.substring(start).trim()
    return parts.filter { it.isNotEmpty() }
}

private fun keywordBounded(source: String, index: Int, length: Int): Boolean {
    val before = if (index == 0) ' ' else source[index - 1]
    val afterIndex = index + length
    val after = if (afterIndex >= source.length) ' ' else source[afterIndex]
    return before.isWhitespace() && after.isWhitespace()
}

private fun findCompare(source: String): Pair<String, String>? {
    var depth = 0
    var quote = '\u0000'
    var i = 0
    while (i < source.length) {
        val c = source[i]
        if (quote != '\u0000') {
            if (c == quote) quote = '\u0000'
            i++
            continue
        }
        if (c == '"' || c == '\'') {
            quote = c
            i++
            continue
        }
        if (c == '(') {
            depth++
            i++
            continue
        }
        if (c == ')' && depth > 0) {
            depth--
            i++
            continue
        }
        if (depth == 0) {
            val op = compareOperatorAt(source, i)
            if (op != null) {
                val left = source.substring(0, i).trim()
                val right = source.substring(i + op.length).trim()
                if (left.isNotEmpty() && right.isNotEmpty()) return left to right
            }
        }
        i++
    }
    return null
}

private fun compareOperatorAt(source: String, index: Int): String? {
    val two = if (index + 1 < source.length) source.substring(index, index + 2) else ""
    if (two == "==" || two == "!=" || two == "<=" || two == ">=") return two
    val one = source[index].toString()
    if (one == "<" || one == ">") return one
    return null
}

private fun parseCall(text: String): Pair<String, List<String>>? {
    val open = text.indexOf('(')
    if (open <= 0 || !text.endsWith(')')) return null
    val name = text.substring(0, open).trim()
    if (!name.matches(Regex("""[A-Za-z_][\w.]*"""))) return null
    var depth = 0
    for (i in open until text.length) {
        when (text[i]) {
            '(' -> depth++
            ')' -> depth--
        }
        if (depth == 0 && i != text.lastIndex) return null
    }
    if (depth != 0) return null
    val inner = text.substring(open + 1, text.length - 1)
    return name to splitArgs(inner)
}

private fun splitArgs(source: String): List<String> {
    if (source.isBlank()) return emptyList()
    val parts = mutableListOf<String>()
    var depth = 0
    var quote = '\u0000'
    var start = 0
    for (i in source.indices) {
        val c = source[i]
        if (quote != '\u0000') {
            if (c == quote) quote = '\u0000'
            continue
        }
        when (c) {
            '"', '\'' -> quote = c
            '(' -> depth++
            ')' -> if (depth > 0) depth--
            ',' -> if (depth == 0) {
                parts += source.substring(start, i).trim()
                start = i + 1
            }
        }
    }
    parts += source.substring(start).trim()
    return parts.filter { it.isNotEmpty() }
}

private fun parseIni(content: String): IniSections {
    val text = content.removePrefix("\uFEFF")
    val sections = linkedMapOf<String, MutableMap<String, String>>()
    var section = ""
    val lines = text.lineSequence().iterator()
    while (lines.hasNext()) {
        var raw = lines.next()
        if (raw.startsWith("?")) raw = raw.substring(1)
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            section = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
            sections.getOrPut(section) { linkedMapOf() }
            continue
        }
        val sep = trimmed.indexOfFirst { it == '=' || it == ':' }
        if (sep < 0) continue
        val key = trimmed.substring(0, sep).trim().lowercase()
        if (key.isEmpty()) continue
        var value = trimmed.substring(sep + 1).trim()
        if (value.startsWith("\"\"\"")) {
            value = readTripleQuoted(value.removePrefix("\"\"\""), lines)
        }
        value = value.replace("\\n", "\n")
        sections.getOrPut(section) { linkedMapOf() }[key] = value
    }
    return sections
}

private fun readTripleQuoted(afterOpen: String, lines: Iterator<String>): String {
    val closeOnSameLine = afterOpen.indexOf("\"\"\"")
    if (closeOnSameLine >= 0) return afterOpen.substring(0, closeOnSameLine)
    val buffer = StringBuilder()
    if (afterOpen.isNotEmpty()) buffer.append(afterOpen)
    while (lines.hasNext()) {
        val next = lines.next()
        val closeIdx = next.indexOf("\"\"\"")
        if (closeIdx >= 0) {
            val beforeClose = next.substring(0, closeIdx)
            if (beforeClose.isNotEmpty()) {
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(beforeClose)
            }
            break
        }
        if (buffer.isNotEmpty()) buffer.append('\n')
        buffer.append(next)
    }
    return buffer.toString()
}

internal fun imageExtent(name: String, bytes: ByteArray): ImageExtent? {
    val ext = name.substringAfterLast('.', "")
    val size = when {
        ext.equals("png", true) -> pngSize(bytes)
        ext.equals("jpg", true) || ext.equals("jpeg", true) -> jpegSize(bytes)
        ext.equals("gif", true) -> gifSize(bytes)
        else -> null
    } ?: return null
    if (size.first <= 0 || size.second <= 0) return null
    return ImageExtent(size.first, size.second)
}

private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 24 || bytes[0] != 0x89.toByte() || bytes[1] != 'P'.code.toByte()) return null
    if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte()) return null
    return readBeInt(bytes, 16) to readBeInt(bytes, 20)
}

private fun gifSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 10 || bytes[0] != 'G'.code.toByte()) return null
    val width = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
    val height = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
    return width to height
}

private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
    var i = 2
    while (i + 8 < bytes.size) {
        if (bytes[i] != 0xFF.toByte()) {
            i++
            continue
        }
        val marker = bytes[i + 1].toInt() and 0xFF
        if (marker == 0xD8 || marker == 0xD9 || marker == 0x01 || marker in 0xD0..0xD7) {
            i += 2
            continue
        }
        if (i + 3 >= bytes.size) return null
        val segmentLength = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
        if (segmentLength < 2) return null
        val isStartOfFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
        if (isStartOfFrame) {
            if (i + 8 >= bytes.size) return null
            val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
            val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
            return width to height
        }
        i += 2 + segmentLength
    }
    return null
}

private fun readBeInt(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}
