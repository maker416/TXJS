/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import io.github.rwpp.tools.heap.agent.HeapAgent
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeapGraphMeterTest {
    private class UnitDefinition(var content: Any? = null, var other: Any? = null)
    private class Branch(var child: Any? = null)
    private data class EqualLeaf(val number: Int)

    private fun roots(vararg units: UnitDefinition) = units.mapIndexed { index, value ->
        HeapUnitRoot("unit$index", "unit$index.ini", value)
    }

    @Test
    fun cyclesAndEqualObjectsUseIdentityRatherThanEquality() {
        val first = EqualLeaf(1)
        val equalButSeparate = EqualLeaf(1)
        val unit = UnitDefinition(arrayOf(first, equalButSeparate, first))
        unit.other = unit
        val graph = HeapGraphMeter { 10L }.measure(roots(unit), UnitDefinition::class.java)
        assertEquals(40L, graph.totalBytes)
        assertEquals(4L, graph.objectCount)
        assertEquals(0L, graph.sharedBytes)
        assertEquals(40L, graph.units.single().exclusiveBytes)
    }

    @Test
    fun sharedOwnershipPropagatesThroughCyclesWithoutDoubleChargingUnits() {
        val payload = ByteArray(20)
        val branch = Branch(payload)
        val cycle = Branch(branch)
        branch.child = arrayOf(payload, cycle)
        val first = UnitDefinition(branch)
        // The second root reaches the shared cycle only after it was traversed for the first.
        val second = UnitDefinition(Branch(Branch(Branch(branch))))
        val sizingCalls = IdentityHashMap<Any, Int>()
        val graph = HeapGraphMeter { value ->
            sizingCalls[value] = (sizingCalls[value] ?: 0) + 1
            10L
        }.measure(roots(first, second), UnitDefinition::class.java)
        assertEquals(90L, graph.totalBytes)
        assertEquals(40L, graph.sharedBytes)
        assertEquals(4L, graph.sharedObjectCount)
        assertEquals(listOf(40L, 10L), graph.units.map { it.exclusiveBytes })
        assertTrue(sizingCalls.values.all { it == 1 }, "一个对象的浅尺寸只能计量一次")
        assertEquals(graph.totalBytes, graph.sharedBytes + graph.units.sumOf { it.exclusiveBytes })
    }

    @Test
    fun crossUnitReferencesDoNotMakeOneUnitOwnAnotherUnitsGraph() {
        val outside = UnitDefinition(ByteArray(200))
        val first = UnitDefinition(ByteArray(20))
        val second = UnitDefinition(ByteArray(40), outside)
        first.other = second
        val graph = HeapGraphMeter { 10L }.measure(roots(first, second), UnitDefinition::class.java)
        assertEquals(40L, graph.totalBytes)
        assertEquals(0L, graph.sharedBytes)
        assertEquals(listOf(20L, 20L), graph.units.map { it.exclusiveBytes })
        assertEquals(1L, graph.boundaryObjectCount)
    }

    @Test
    fun weakReferencesAndRuntimeObjectsStopAtExplicitBoundaries() {
        val unit = UnitDefinition(arrayOf(
            WeakReference(ByteArray(1_000)),
            UnitDefinition::class.java,
            UnitDefinition::class.java.classLoader,
            Thread.currentThread(),
        ))
        val graph = HeapGraphMeter { 10L }.measure(roots(unit), UnitDefinition::class.java)
        assertEquals(20L, graph.totalBytes)
        assertEquals(4L, graph.boundaryObjectCount)
    }

    @Test
    fun directBufferCapacityIsNotAddedToJavaHeap() {
        val direct = ByteBuffer.allocateDirect(1_024)
        val graph = HeapGraphMeter { 10L }.measure(roots(UnitDefinition(direct)), UnitDefinition::class.java)
        assertEquals(20L, graph.totalBytes)
        assertEquals(2L, graph.objectCount)
    }

    @Test
    fun startupAgentReportsActualVmSizesAndTraversesTransientBackingArrays() {
        val output = runProbe("actual", openJdkPackages = true)
        assertTrue(output.contains("actual JVM graph verified"), output)
    }

    @Test
    fun inaccessibleJdkFieldsFailWithRequiredModuleOpening() {
        val output = runProbe("inaccessible", openJdkPackages = false)
        assertTrue(output.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"), output)
        assertTrue(output.contains("measurement refused"), output)
    }

    @Test
    fun startupAgentCanExplicitlyOpenInternalLocaleAndZipFieldsWithoutJvmFlags() {
        val output = runProbe("open-packages", openJdkPackages = false)
        assertTrue(output.contains("agent module opening verified"), output)
    }

    private fun runProbe(mode: String, openJdkPackages: Boolean): String {
        val directory = createTempDirectory("heap-agent-test").toFile()
        try {
            val agent = File(directory, "agent.jar")
            val manifest = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes.putValue("Premain-Class", HeapAgent::class.java.name)
            }
            JarOutputStream(agent.outputStream(), manifest).use { output ->
                val entry = HeapAgent::class.java.name.replace('.', '/') + ".class"
                output.putNextEntry(JarEntry(entry))
                HeapAgent::class.java.getResourceAsStream("/" + entry)!!.use { it.copyTo(output) }
                output.closeEntry()
            }
            val classPath = listOf(
                HeapGraphMeter::class.java,
                HeapAgent::class.java,
                HeapGraphMeterProbe::class.java,
                kotlin.Unit::class.java,
            ).map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
                .distinct().joinToString(File.pathSeparator)
            val executable = File(System.getProperty("java.home"), "bin/java" + if (File.separatorChar == '\\') ".exe" else "")
            val arguments = mutableListOf(executable.absolutePath, "-javaagent:${agent.absolutePath}")
            if (openJdkPackages) {
                arguments += "--add-opens=java.base/java.lang=ALL-UNNAMED"
                arguments += "--add-opens=java.base/java.util=ALL-UNNAMED"
            }
            arguments += listOf("-cp", classPath, HeapGraphMeterProbe::class.java.name, mode)
            val result = File(directory, "output.txt")
            val process = ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(result).start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("测量探针超时")
            }
            val output = result.readText()
            assertEquals(0, process.exitValue(), output)
            return output
        } finally {
            directory.deleteRecursively()
        }
    }
}

/** Runs in an ordinary child JVM, so Gradle's own --add-opens cannot mask access failures. */
object HeapGraphMeterProbe {
    private class Definition(val content: Any?)

    @JvmStatic
    fun main(args: Array<String>) {
        check(HeapAgent.isAvailable()) { "premain did not install Instrumentation" }
        when (args.single()) {
            "actual" -> {
                val shared = String(charArrayOf('a', 'b', 'c'))
                val backing = String::class.java.getDeclaredField("value").apply { isAccessible = true }.get(shared)
                val list = ArrayList<Any>(32).apply { add(shared) }
                val elements = ArrayList::class.java.getDeclaredField("elementData").apply { isAccessible = true }.get(list)
                val first = Definition(list)
                val second = Definition(shared)
                val graph = HeapGraphMeter().measure(listOf(
                    HeapUnitRoot("first", "first.ini", first), HeapUnitRoot("second", "second.ini", second),
                ), Definition::class.java)
                val expected = listOf(first, second, list, elements, shared, backing).sumOf { HeapAgent.objectSize(it) }
                check(graph.totalBytes == expected) { "expected $expected, measured ${graph.totalBytes}" }
                check(graph.objectCount == 6L) { "transient collection or String backing omitted" }
                check(graph.sharedBytes == HeapAgent.objectSize(shared) + HeapAgent.objectSize(backing))
                check(graph.totalBytes == graph.sharedBytes + graph.units.sumOf { it.exclusiveBytes })
                println("actual JVM graph verified: ${graph.totalBytes}")
            }
            "inaccessible" -> {
                try {
                    HeapGraphMeter().measure(listOf(HeapUnitRoot("unit", "unit.ini", Definition("private JDK fields"))))
                    error("inaccessible field was silently ignored")
                } catch (failure: HeapGraphAccessException) {
                    check(failure.message.orEmpty().contains("--add-opens=java.base/java.lang=ALL-UNNAMED"))
                    println("measurement refused: ${failure.message}")
                }
            }
            "open-packages" -> {
                HeapAgent.openHeapPackages()
                val archive = File.createTempFile("heap-module-probe", ".zip")
                try {
                    ZipOutputStream(archive.outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry("unit.ini"))
                        zip.write("[core]\nname=probe".toByteArray())
                        zip.closeEntry()
                    }
                    ZipFile(archive).use { zip ->
                        val visitedClasses = HashSet<String>()
                        val root = Definition(arrayOf("JDK module fields", Locale.forLanguageTag("zh-Hans-CN"), zip))
                        val graph = HeapGraphMeter { value ->
                            visitedClasses += value.javaClass.name
                            HeapAgent.objectSize(value)
                        }.measure(listOf(HeapUnitRoot("probe", "probe.ini", root)), Definition::class.java)
                        check(graph.totalBytes > HeapAgent.objectSize(root))
                        check("java.lang.String" in visitedClasses)
                        check("sun.util.locale.BaseLocale" in visitedClasses) { "internal locale graph was skipped" }
                        check(visitedClasses.any { it.startsWith("java.util.zip.ZipFile\$") }) { "zip internals were skipped" }
                        println("agent module opening verified: ${graph.objectCount} actual objects")
                    }
                } finally {
                    check(archive.delete()) { "temporary zip could not be deleted" }
                }
            }
            else -> error("unknown probe")
        }
    }
}
