/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** 手动启用的真实核心测试，需要完整桌面资源、OpenGL 和可用音频设备。 */
class EngineHeapAnalyzerIntegrationTest {
    @Test
    fun realCoreLoadsInheritanceVariablesAndActionsAndMeasuresActualObjects() {
        val root = integrationGameRoot()
        withFixture(root) { mod ->
            val result = EngineHeapAnalyzer.measure(mod, root)
            assertEquals(2, result.unitCount, "必须报告真实核心实际注册的两个单位")
            assertTrue(result.units.all { it.name.isNotBlank() && it.fileName.isNotBlank() })
            assertTrue(result.definitionHeapBytes > 0)
            assertEquals(result.definitionHeapBytes,
                result.units.sumOf { it.exclusiveBytes } + result.sharedDefinitionHeapBytes,
                "每个实际对象必须只进入一个单位的独占堆或共享堆")
            assertTrue(result.baselineHeapBytes > 0)
            assertTrue(result.loadedHeapBytes > 0)
            assertTrue(result.sampledPeakHeapBytes >= result.loadedHeapBytes)
            assertTrue(result.runtimeDescription.contains(System.getProperty("java.version")))
            assertTrue(result.formatReport().contains("测量环境：${result.runtimeDescription}"))
        }
    }

    @Test
    fun brokenUnitAlongsideValidUnitsFailsInsteadOfReportingIncompleteSuccess() {
        val root = integrationGameRoot()
        withFixture(root) { mod ->
            File(mod, "broken.ini").writeText("""
                [core]
                name: rwjsHeapProbeBroken
                copyFrom: scout.ini
                maxHp: not_a_number
            """.trimIndent())
            val failure = assertFailsWith<IllegalStateException> { EngineHeapAnalyzer.measure(mod, root) }
            assertTrue(failure.message.orEmpty().contains("真实核心测量失败"))
        }
    }

    @Test
    fun invalidArchiveFailsInsteadOfReturningAnEmptyMeasurement() {
        val root = integrationGameRoot()
        val directory = createTempDirectory("heap-core-invalid-archive-").toFile()
        try {
            val initialSandboxes = measurementSandboxes().map { it.absolutePath }.toSet()
            val archive = File(directory, "broken.rwmod").apply { writeText("this is not a ZIP archive") }
            assertFailsWith<IllegalStateException> { EngineHeapAnalyzer.measure(archive, root) }
            val leftovers = measurementSandboxes().filter { it.absolutePath !in initialSandboxes }
            assertTrue(leftovers.isEmpty(), "失败后仍残留测量隔离目录：$leftovers")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun zipAndUppercaseRwmodUseTheRealCoreToLoadBothUnits() {
        val root = integrationGameRoot()
        withFixture(root) { mod ->
            // 归档放在 fixture 目录外，防止扫描目录时将生成的归档打入自身。
            val directory = createTempDirectory("heap-core-archives-").toFile()
            try {
                for (name in listOf("sample.zip", "sample.RWMOD")) {
                    val archive = File(directory, name)
                    ZipOutputStream(archive.outputStream()).use { zip ->
                        mod.walkTopDown().filter { it.isFile }.forEach { source ->
                            zip.putNextEntry(ZipEntry(source.relativeTo(mod).invariantSeparatorsPath))
                            source.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    val result = EngineHeapAnalyzer.measure(archive, root)
                    assertEquals(2, result.unitCount, "$name 必须通过真实核心加载全部单位")
                    assertTrue(result.definitionHeapBytes > 0, "$name 必须返回实际单位对象堆")
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun cancellingMeasurementTerminatesItsWorkerAndDeletesItsSandbox() {
        val root = integrationGameRoot()
        withFixture(root) { mod ->
            val workerStarted = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val initialPids = ProcessHandle.current().descendants().use { children ->
                children.toList().map { it.pid() }.toSet()
            }
            val initialSandboxes = measurementSandboxes().map { it.absolutePath }.toSet()
            val observedWorkers = AtomicReference<List<ProcessHandle>>(emptyList())
            val observedSandboxes = AtomicReference<List<File>>(emptyList())
            val failure = AtomicReference<Throwable?>()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val task = executor.submit {
                    try {
                        EngineHeapAnalyzer.measure(mod, root) { message ->
                            if (message.startsWith("初始化真实桌面引擎")) {
                                val workers = ProcessHandle.current().descendants().use { children ->
                                    children.toList().filter { child ->
                                        val command = child.info().command().orElse("")
                                        child.pid() !in initialPids && (command.isEmpty() ||
                                            File(command).name.lowercase() in setOf("java", "java.exe", "javaw", "javaw.exe"))
                                    }
                                }
                                val sandboxes = measurementSandboxes().filter { sandbox ->
                                    sandbox.absolutePath !in initialSandboxes &&
                                        (File(sandbox, "progress.txt").isFile || File(sandbox, "measurement.properties").isFile)
                                }
                                if (workers.isNotEmpty() && sandboxes.isNotEmpty()) {
                                    observedWorkers.set(workers)
                                    observedSandboxes.set(sandboxes)
                                    workerStarted.countDown()
                                }
                            }
                        }
                    } catch (error: Throwable) {
                        failure.set(error)
                    } finally {
                        finished.countDown()
                    }
                }
                assertTrue(workerStarted.await(45, TimeUnit.SECONDS), "没有观察到真实核心测量子进程：${failure.get()}")
                assertEquals(1, observedWorkers.get().size, "此测试必须独占一个测量子进程")
                assertEquals(1, observedSandboxes.get().size, "此测试必须独占一个测量隔离目录")
                assertTrue(task.cancel(true))
                assertTrue(finished.await(15, TimeUnit.SECONDS), "取消后测量线程未完成清理")
                assertTrue(failure.get() is CancellationException, "取消必须传播取消状态：${failure.get()}")
                observedWorkers.get().forEach { worker ->
                    assertFalse(worker.isAlive, "取消后测量子进程仍存活：${worker.pid()}")
                }
                observedSandboxes.get().forEach { sandbox ->
                    assertFalse(sandbox.exists(), "取消后仍残留隔离资源目录：$sandbox")
                }
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(15, TimeUnit.SECONDS)
            }
        }
    }

    private fun measurementSandboxes(): List<File> =
        File(System.getProperty("java.io.tmpdir")).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("rwjs-real-heap-") }.orEmpty()

    private fun integrationGameRoot(): File {
        val path = System.getProperty("rwpp.heap.integrationGameRoot")
        assumeTrue("仅在指定 rwpp.heap.integrationGameRoot 时运行真实核心测试", !path.isNullOrBlank())
        return File(requireNotNull(path)).absoluteFile.also {
            assertTrue(EngineHeapAnalyzer.isGameRoot(it), "需要完整桌面游戏目录：$it")
        }
    }

    private fun withFixture(root: File, block: (File) -> Unit) {
        val directory = createTempDirectory("heap-core-fixture-").toFile()
        try {
            val source = File(root, "assets/units/scout")
            val original = File(source, "scout.ini").readText()
            val name = Regex("(?m)^name\\s*[:=][^\\r\\n]*")
            assertTrue(name.containsMatchIn(original), "原版 scout.ini 缺少单位名称")
            File(directory, "scout.ini").writeText(original.replaceFirst(name, "name: rwjsHeapProbeBase"))
            listOf("base.png", "base_dead.png").forEach { image ->
                File(source, image).copyTo(File(directory, image))
            }
            // copyFrom 与 @define/${...} 使用原版 modular_spider 的合法配置语法。
            // hiddenAction 同样使用原版 setUnitStats 的逻辑表达式路径，不自行模拟解析。
            File(directory, "inherited.ini").writeText("""
                [core]
                name: rwjsHeapProbeInherited
                copyFrom: scout.ini
                @define heapProbeHp: 400
                maxHp: ${'$'}{heapProbeHp + 25}

                [hiddenAction_heapProbe]
                @define newMoveSpeed: 0.4 + select( self.hp > 100, 0.3, 0 )
                autoTriggerOnEvent: created
                setUnitStats: moveSpeed= ${'$'}{newMoveSpeed}
            """.trimIndent())
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
