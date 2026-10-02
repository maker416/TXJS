/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.system.exitProcess

internal object EngineHeapAnalyzer {
    fun findGameRoot(): File? {
        val candidates = mutableListOf<File>()
        System.getProperty("rwpp.heap.gameRoot")?.takeIf { it.isNotBlank() }?.let { candidates += File(it) }
        val locations = listOf(File(System.getProperty("user.dir")),
            File(EngineHeapAnalyzer::class.java.protectionDomain.codeSource.location.toURI()).parentFile)
        locations.forEach { location ->
            generateSequence(location) { it.parentFile }.take(5).forEach { directory ->
                candidates += directory
                val hint = File(directory, "packaging/game-root.local.txt")
                if (hint.isFile) hint.readLines().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
                    ?.trim()?.let { candidates += File(it) }
            }
        }
        return candidates.firstOrNull(::isGameRoot)?.absoluteFile
    }

    fun isGameRoot(directory: File): Boolean = directory.isDirectory &&
        File(directory, "assets/units").isDirectory && File(directory, "res").isDirectory

    fun measure(file: File, gameRoot: File, onProgress: (String) -> Unit = {}): MeasuredModHeap {
        require(file.exists()) { "找不到模组：${file.absolutePath}" }
        require(isGameRoot(gameRoot)) { "请选择包含 assets/units 和 res 的完整桌面游戏目录" }
        checkCancelled()
        val sandbox = Files.createTempDirectory("rwjs-real-heap-").toFile()
        var process: Process? = null
        try {
            onProgress("准备独立测量进程与原版资源…")
            listOf("assets", "res", "font").forEach { name ->
                val source = File(gameRoot, name)
                if (source.isDirectory) copyResources(source.toPath(), File(sandbox, name).toPath())
            }
            File(sandbox, "mods/units").mkdirs()
            val agent = File(sandbox, "heap-agent.jar")
            EngineHeapAnalyzer::class.java.getResourceAsStream("/heap-agent/RWJS-HeapAgent.jar")
                .let { requireNotNull(it) { "缺少内存测量 agent，请重新构建 packageTool" } }
                .use { input -> agent.outputStream().use { input.copyTo(it) } }
            val output = File(sandbox, "measurement.properties")
            val status = File(sandbox, "progress.txt")
            val log = File(sandbox, "engine.log")
            val failure = File(sandbox, "failure.txt")
            val maxHeapMiB = System.getProperty("rwpp.heap.maxHeapMiB", "4096").toInt()
            require(maxHeapMiB in 128..65536) { "rwpp.heap.maxHeapMiB 必须在 128..65536 范围内" }
            val stackMiB = System.getProperty("rwpp.heap.stackMiB", "16").toInt()
            require(stackMiB in 1..256) { "rwpp.heap.stackMiB 必须在 1..256 范围内" }
            val java = File(System.getProperty("java.home"), "bin/java${if (isWindows()) ".exe" else ""}")
            val command = mutableListOf(
                // 原版逻辑表达式解析会递归；线程栈独立于 Java 堆，重度嵌套 select 需要更大的栈。
                java.absolutePath, "-Xmx${maxHeapMiB}m", "-Xss${stackMiB}m", "-Drwpp.heap.stackMiB=$stackMiB",
                "-Dfile.encoding=UTF-8", "-XX:-DisableExplicitGC",
                "-Drwpp.heap.selectOptimization=${System.getProperty("rwpp.heap.selectOptimization", "false")}",
                "-javaagent:${agent.absolutePath}", "-Djava.library.path=${gameRoot.absolutePath}",
                "-Dorg.lwjgl.librarypath=${gameRoot.absolutePath}",
            )
            // Gradle run 的 classpath 含相对路径；新 JVM 的工作目录必须完全隔离。
            val classpath = System.getProperty("rwpp.heap.workerClasspath", System.getProperty("java.class.path"))
                .split(File.pathSeparator)
                .joinToString(File.pathSeparator) { File(it).absolutePath }
            command += listOf("-cp", classpath, "io.github.rwpp.tools.heap.ModHeapDesktopKt", "--measure-worker",
                gameRoot.absolutePath, file.absolutePath, output.absolutePath, status.absolutePath)
            process = ProcessBuilder(command).directory(sandbox).redirectErrorStream(true).redirectOutput(log).start()
            var lastStatus = ""
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                checkCancelled()
                if (status.isFile) {
                    val message = status.readText(Charsets.UTF_8)
                    if (message.isNotBlank() && message != lastStatus) { onProgress(message); lastStatus = message }
                }
            }
            checkCancelled()
            check(process.exitValue() == 0 && output.isFile) {
                val detail = EngineMeasurementFailure.read(failure) ?: EngineMeasurementFailure.readLogFailure(log)
                "真实核心测量失败（退出码 ${process.exitValue()}）：\n$detail"
            }
            return MeasuredModHeap.readFrom(output)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("已取消真实核心测量")
        } finally {
            process?.let(::stopProcess)
            deleteSandbox(sandbox.toPath())
        }
    }

    fun runWorker(args: List<String>) {
        require(args.size == 4) { "测量子进程参数错误" }
        val output = File(args[2])
        val status = File(args[3])
        fun progress(message: String) { status.writeText(message, Charsets.UTF_8) }
        var phase = "初始化真实桌面核心与原版单位"
        val optimized = java.lang.Boolean.getBoolean("rwpp.heap.selectOptimization")
        try {
            if (optimized) EngineSelectOptimization.install()
            // 仅独立诊断 JVM 显式打开其模块，覆盖 Locale/ZIP/图像等实现的私有字段。
            // Meter 仍严格拒绝任何不可访问字段，不会静默漏算。
            io.github.rwpp.tools.heap.agent.HeapAgent.openHeapPackages()
            progress("初始化真实桌面引擎与原版单位…")
            EngineModLoader(File(args[0])).use { loader ->
                loader.loadBaseline()
                if (optimized) EngineSelectOptimization.verifyApplied()
                val memory = ManagementFactory.getMemoryMXBean()
                val baseline = gcHeapBytes()
                phase = "真实核心加载模组"
                progress("真实核心正在加载 ${File(args[1]).name}…")
                val sampling = AtomicBoolean(true)
                val peak = AtomicLong(baseline)
                val sampler = thread(name = "heap-peak-sampler", isDaemon = true) {
                    while (sampling.get()) {
                        peak.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf)
                        Thread.sleep(10)
                    }
                }
                val loaded: EngineLoadResult
                val after: Long
                try {
                    loaded = loader.loadMod(File(args[1]))
                    peak.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf)
                    after = gcHeapBytes()
                } finally {
                    sampling.set(false)
                    sampler.join()
                }
                progress("测量实际单位对象并去重共享对象…")
                phase = "单位对象图诊断"
                val graph = HeapGraphMeter().measure(loaded.units.map { HeapUnitRoot(it.name, it.fileName, it.root) },
                    com.corrodinggames.rts.game.units.custom.l::class.java) { value ->
                    // 音效持有音频工厂、unit type 持有实例缓存；这些反向引用不能算成单个定义的对象图。
                    value is com.corrodinggames.rts.gameFramework.l ||
                        value is com.corrodinggames.rts.gameFramework.a.h ||
                        value is com.corrodinggames.rts.java.audio.lwjgl.OpenALAudio ||
                        value is com.corrodinggames.rts.game.units.am ||
                        value is android.content.Context ||
                        value is com.corrodinggames.rts.gameFramework.m.y
                }
                val warnings = loaded.warnings.toMutableList()
                if (after < baseline) warnings += "GC 后堆净增为负值，说明基线中部分缓存被释放；请结合单位对象图大小理解结果。"
                MeasuredModHeap(
                    File(args[1]).name, baseline, after, peak.get(), memory.heapMemoryUsage.max,
                    graph.totalBytes, graph.sharedBytes, loaded.textureAccountedBytes, loaded.soundAccountedBytes,
                    "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")} / ${System.getProperty("os.name")} ${System.getProperty("os.arch")}" +
                        if (optimized) " / RWJS Select 迭代解析与求值" else " / 原版 Select 解析与求值",
                    graph.units.map { MeasuredUnitHeap(it.name, it.fileName, it.exclusiveBytes) }
                        .sortedByDescending { it.exclusiveBytes }, warnings,
                    System.getProperty("rwpp.heap.stackMiB")?.toLong()?.times(1024L * 1024) ?: 0,
                ).writeTo(output)
            }
            exitProcess(0) // 原版音频、Looper 等服务线程由独立测量进程统一结束。
        } catch (error: Throwable) {
            runCatching { EngineMeasurementFailure.write(File(output.parentFile, "failure.txt"), error, phase) }
                .onFailure { System.err.println("测量异常摘要写入失败：${it.javaClass.name}: ${it.message}") }
            error.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    private fun gcHeapBytes(): Long {
        repeat(2) { System.gc(); Thread.sleep(100) }
        return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("已取消真实核心测量")
    }

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows", true)

    private fun copyResources(source: Path, destination: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                Files.createDirectories(destination.resolve(source.relativize(dir)))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                require(!attrs.isSymbolicLink) { "游戏资源中含符号链接：$file，请选择完整原版资源目录" }
                Files.copy(file, destination.resolve(source.relativize(file)))
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun stopProcess(process: Process) {
        if (process.isAlive) {
            process.toHandle().descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            // 取消线程保留中断状态，但仍需等 native context 退出后才能删临时资源。
            val interrupted = Thread.interrupted()
            try { process.waitFor(5, TimeUnit.SECONDS) } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }

    private fun deleteSandbox(path: Path) {
        // path 是本方法创建的独立临时目录；不跟随链接，也不包含用户的模组目录。
        val interrupted = Thread.interrupted()
        try {
            repeat(3) { attempt ->
                if (!Files.exists(path)) return
                val result = runCatching {
                    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            Files.deleteIfExists(file)
                            return FileVisitResult.CONTINUE
                        }
                        override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                            if (error != null) throw error
                            Files.deleteIfExists(dir)
                            return FileVisitResult.CONTINUE
                        }
                    })
                }
                if (result.isSuccess) return
                // Windows 退出时文件句柄/扫描器可能稍晚释放；清理失败必须可见。
                if (attempt < 2) Thread.sleep(100) else {
                    System.err.println("测量临时目录未能清理：$path：${result.exceptionOrNull()?.message}")
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

}
