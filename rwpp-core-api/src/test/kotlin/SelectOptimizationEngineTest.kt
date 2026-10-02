/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.units.logic

import io.github.rwpp.inject.runtime.SelectOptimizationBytecode
import javassist.ClassPool
import javassist.LoaderClassPath
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compares both shipped core jars in separate loaders; no native graphics or game assets are needed. */
class SelectOptimizationEngineTest {
    @Test
    fun nullLeafDebugReasonsKeepStringBuilderNullSemantics() {
        for (platform in listOf("desktop", "android")) {
            IsolatedSelectCore(platform, false, nullLeafDebug = true).use { original ->
                IsolatedSelectCore(platform, true, nullLeafDebug = true).use { optimized ->
                    val expression = "select(true, 1, select(false, 2, 3))"
                    val expected = original.debug(expression)
                    assertTrue(expected.contains("then:(null)"), "The real leaf override must return null")
                    assertEquals(expected, optimized.debug(expression), "$platform null debug leaf")
                }
            }
        }
    }

    @Test
    fun unselectedBranchesRemainLazyInBothCores() {
        for (platform in listOf("desktop", "android")) {
            IsolatedSelectCore(platform, false).use { original ->
                IsolatedSelectCore(platform, true).use { optimized ->
                    for (expression in listOf("select(true, 5, self.hp)", "select(false, self.hp, 5)")) {
                        // Reading self.hp with a null unit would throw if either branch became eager.
                        assertEquals("number:5.0", original.evaluate(expression))
                        assertEquals(original.evaluate(expression), optimized.evaluate(expression))
                    }
                }
            }
        }
    }

    @Test
    fun resultsAndDebugTextMatchTheOriginalDesktopAndAndroidCores() {
        val expressions = listOf(
            "select(true, 1, 2)",
            "select(false, 1.25, -2.5)",
            "SeLeCt(false, select(true, 2, 3), ((select(false, 4, 5))))",
            "select(select(false, true, false), 8, select(true, 9, 10))",
            "select(true, select(false, 'left', 'selected'), 'right')",
            "select(false, \"a'quote\", '汉字')",
            "select(false, 1 + 2 * 3, select(true, 4 / 2, 0))",
            "select(true, true, false)",
            "select(false, select(true, false, true), true)",
            "select(true, null, null)",
        )
        for (platform in listOf("desktop", "android")) {
            IsolatedSelectCore(platform, false).use { original ->
                IsolatedSelectCore(platform, true).use { optimized ->
                    for (expression in expressions) {
                        val expected = original.evaluate(expression)
                        assertEquals(expected, optimized.evaluate(expression), "$platform: $expression")
                        assertEquals(original.astSummary(expression), optimized.astSummary(expression), "$platform AST: $expression")
                        // Android unit debug formatting initializes graphics even for null; its readUnit is still compared.
                        if (!expected.startsWith("unit:")) {
                            assertEquals(original.debug(expression), optimized.debug(expression), "$platform debug: $expression")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun malformedAndWrongTypeExpressionsKeepTheOriginalFailures() {
        val expressions = listOf(
            "select()",
            "select(true, 1)",
            "select(true, 1, 2, 3)",
            "select(true, 1, 'wrong type')",
            "select(select(true, 1, 2), 3, 4)",
            "select(((select(true, 1, 2))), 3, 4)",
            "select(false, select(true, 'a', 1), 'b')",
            "select(true, 'a,b', 'c')",
            "select(true, 'a(b', 'c')",
            "select(true, unknownFunction(), 1)",
        )
        for (platform in listOf("desktop", "android")) {
            IsolatedSelectCore(platform, false).use { original ->
                IsolatedSelectCore(platform, true).use { optimized ->
                    for (expression in expressions) {
                        val failure = original.failure(expression)
                        assertTrue(failure != null, "The original must reject $expression")
                        assertEquals(failure, optimized.failure(expression), "$platform: $expression")
                    }
                }
            }
        }
    }

    @Test
    fun parsesAndEvaluates1539BranchesWithinOneMiBStackAnd256MiBHeap() {
        val classpath = listOf(
            SelectOptimizationEngineProbe::class.java,
            IterativeSelectSupport::class.java,
            ClassPool::class.java,
            kotlin.Unit::class.java,
        ).map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }.distinct().joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/java${if (File.separatorChar == '\\') ".exe" else ""}")
        val output = File.createTempFile("select-core-low-memory-", ".log")
        try {
            val process = ProcessBuilder(
                java.absolutePath, "-Xss1m", "-Xmx256m", "-cp", classpath,
                SelectOptimizationEngineProbe::class.java.name,
                File("../lib").canonicalPath,
            ).redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("The isolated deep-select probe timed out: ${output.readText()}")
            }
            val log = output.readText()
            assertEquals(0, process.exitValue(), log)
            assertTrue(log.contains("deep-select-ok:desktop"), log)
            assertTrue(log.contains("deep-select-ok:android"), log)
        } finally {
            output.delete()
        }
    }
}

/** Runs outside the Gradle JVM so stack and heap limits are deterministic. */
object SelectOptimizationEngineProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val depth = 1539
        for (platform in listOf("desktop", "android")) {
            IsolatedSelectCore(platform, true, File(arguments.single())).use { core ->
                for ((leaf, unselected, expected) in listOf(
                    Triple("7", "0", "number:7.0"),
                    Triple("'done'", "'unselected'", "string:done"),
                    Triple("true", "false", "bool:true"),
                    Triple("null", "null", "unit:null"),
                )) {
                    val expression = buildString {
                        repeat(depth) { append("select(false, ").append(unselected).append(", ") }
                        append(leaf)
                        repeat(depth) { append(')') }
                    }
                    check(core.evaluate(expression) == expected) { "$platform did not evaluate the last branch of $depth selects" }
                    if (!expected.startsWith("unit:")) {
                        check(core.debug(expression).length > depth) { "Deep debug output did not complete" }
                    }
                }
                val selectorExpression = buildString {
                    repeat(depth) { append("select(") }
                    append("false")
                    repeat(depth) { append(", true, false)") }
                }
                check(core.evaluate(selectorExpression) == "bool:false") { "$platform did not evaluate a deeply nested selector" }
                check(core.debug(selectorExpression).length > depth)
                println("deep-select-ok:$platform")
            }
        }
    }
}

private class IsolatedSelectCore(
    platform: String,
    optimized: Boolean,
    libraries: File = File("../lib").canonicalFile,
    nullLeafDebug: Boolean = false,
) : AutoCloseable {
    private val packageName = "com.corrodinggames.rts.game.units.custom.logicBooleans."
    private val nativeJar = File(libraries, if (platform == "android") "android-game-lib.jar" else "game-lib.jar")
    private val pool = ClassPool(true).apply {
        insertClassPath(nativeJar.absolutePath)
        appendClassPath(File(libraries, "game-lib.jar").absolutePath)
        appendClassPath(File(libraries, "android.jar").absolutePath)
        appendClassPath(LoaderClassPath(ReflectiveSelectSupport::class.java.classLoader))
    }
    private val patchedBytes = mutableMapOf<String, ByteArray>()
    private val loader: URLClassLoader
    private val logic: Class<*>
    private val parser: Method
    private val unit: Class<*>

    init {
        check(nativeJar.isFile) { "Missing real core: $nativeJar" }
        // Core registration otherwise creates teams/paint/native resources before a parser test.
        // This unrelated bootstrap method is adapted identically in original and optimized loaders.
        val teamName = packageName + "LogicBooleanGameFunctions\$IsOnTeam"
        val team = pool.get(teamName)
        team.getDeclaredMethod("team").setBody("{ this.teamId = \$1; }")
        patchedBytes[teamName] = team.toBytecode()
        if (nullLeafDebug) {
            // Exercise the legal Java null return from a real engine leaf, rather than a mock adapter.
            val number = pool.get(packageName + "LogicBoolean\$StaticValueBoolean")
            number.getDeclaredMethod("getMatchFailReasonForPlayer").setBody("{ return null; }")
            patchedBytes[number.name] = number.toBytecode()
        }
        if (optimized) {
            val select = pool.get(SelectOptimizationBytecode.SELECT_CLASS)
            SelectOptimizationBytecode.patch(select)
            patchedBytes[select.name] = select.toBytecode()
        }
        val jarNames = listOf(nativeJar.name, "game-lib.jar", "slick.jar", "lwjgl.jar", "lwjgl_util.jar", "android.jar").distinct()
        loader = object : URLClassLoader(jarNames.map { File(libraries, it).toURI().toURL() }.toTypedArray(), ReflectiveSelectSupport::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith("com.corrodinggames.") && !name.startsWith("android.")) return super.loadClass(name, resolve)
                synchronized(getClassLoadingLock(name)) {
                    val loaded = findLoadedClass(name) ?: findClass(name)
                    if (resolve) resolveClass(loaded)
                    return loaded
                }
            }

            override fun findClass(name: String): Class<*> {
                val bytes = patchedBytes[name]
                return if (bytes == null) super.findClass(name) else defineClass(name, bytes, 0, bytes.size)
            }
        }
        logic = loader.loadClass(packageName + "LogicBoolean")
        unit = loader.loadClass(if (platform == "android") "com.corrodinggames.rts.game.units.bp" else "com.corrodinggames.rts.game.units.y")
        val meta = loader.loadClass("com.corrodinggames.rts.game.units.custom.l")
        parser = loader.loadClass(packageName + "LogicBooleanLoader").getMethod("parseBooleanBlock", meta, String::class.java, Boolean::class.javaPrimitiveType)
    }

    private fun parse(expression: String): Any = invoke(parser, null, null, expression, false)!!

    fun evaluate(expression: String): String {
        val root = parse(expression)
        val type = (invoke(logic.getMethod("getReturnType"), root) as Enum<*>).name
        val methodName = when (type) {
            "number" -> "readNumber"
            "string" -> "readString"
            "unit" -> "readUnit"
            "bool" -> "read"
            else -> error("Unsupported test result type: $type")
        }
        return "$type:${invoke(logic.getMethod(methodName, unit), root, null)}"
    }

    fun debug(expression: String): String =
        invoke(logic.getMethod("getMatchFailReasonForPlayer", unit), parse(expression), null) as String

    /** Ordered node classes and return types also detect branch replacement or constant folding. */
    fun astSummary(expression: String): List<String> {
        val selectClass = loader.loadClass(SelectOptimizationBytecode.SELECT_CLASS)
        val childFields = listOf("selector", "childA", "childB").map { field ->
            selectClass.getDeclaredField(field).apply { isAccessible = true }
        }
        val pending = ArrayDeque<Any>()
        val result = mutableListOf<String>()
        pending.addLast(parse(expression))
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val type = (invoke(logic.getMethod("getReturnType"), node) as Enum<*>).name
            result.add("${node.javaClass.name}:$type")
            if (node.javaClass == selectClass) childFields.forEach { pending.addLast(it.get(node)) }
        }
        return result
    }

    fun failure(expression: String): String? = try {
        parse(expression)
        null
    } catch (failure: Throwable) {
        "${failure.javaClass.name}:${failure.message}"
    }

    override fun close() = loader.close()

    private fun invoke(method: Method, receiver: Any?, vararg arguments: Any?): Any? = try {
        method.invoke(receiver, *arguments)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
