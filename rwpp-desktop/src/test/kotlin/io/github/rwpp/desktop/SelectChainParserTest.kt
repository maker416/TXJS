/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBoolean
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBooleanLoader
import com.corrodinggames.rts.game.units.custom.logicBooleans.`LogicString$Select`
import com.corrodinggames.rts.game.units.custom.logicBooleans.`LogicBoolean$ReturnType`
import io.github.rwpp.game.mod.SelectChainParser
import kotlin.test.*

class SelectChainParserTest {
    private var previousEngine: Any? = null
    private var installedEngine: Any? = null
    private val engineSingleton get() = Class.forName("com.corrodinggames.rts.gameFramework.l")
        .getDeclaredField("al").apply { isAccessible = true }

    @BeforeTest fun provideParserEngine() {
        // Type registration constructs neutral units and reads the engine frame counter.
        // Preserve any real engine supplied by another test or test harness.
        val singleton = engineSingleton
        previousEngine = singleton.get(null)
        if (previousEngine != null) return
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as sun.misc.Unsafe
        installedEngine = unsafe.allocateInstance(Class.forName("com.corrodinggames.rts.game.i"))
        singleton.set(null, installedEngine)
    }

    @AfterTest fun restoreParserEngine() {
        val installed = installedEngine ?: return
        val singleton = engineSingleton
        // Do not overwrite an engine installed by another owner while the test was running.
        if (singleton.get(null) === installed) singleton.set(null, previousEngine)
        installedEngine = null
    }

    private fun chain(depth: Int, yes: String, tail: String, condition: String = "false"): String =
        buildString { repeat(depth) { append("select($condition,$yes,") }; append(tail); repeat(depth) { append(')') } }
    private fun original(expression: String) = LogicBooleanLoader.parseBooleanBlock(null, expression, false)
    private fun optimized(expression: String): LogicBoolean {
        val node = `LogicString$Select`()
        val args = expression.substringAfter('(').dropLast(1)
        if (!SelectChainParser.apply(node, args, null)) return original(expression)
        return node.validateAndOptimize("select", args, expression, null, false)
    }
    private fun signature(node: LogicBoolean): List<String> {
        val result = ArrayList<String>()
        var current = node
        val fields = listOf("selector", "childA", "childB").map { `LogicString$Select`::class.java.getDeclaredField(it).apply { isAccessible = true } }
        while (current is `LogicString$Select`) {
            result.add(current.javaClass.name + ":" + current.returnType)
            for (field in fields.take(2)) {
                val child = field.get(current) as LogicBoolean
                result.add(child.javaClass.name + ":" + child.returnType)
            }
            current = fields[2].get(current) as LogicBoolean
        }
        result.add(current.javaClass.name + ":" + current.returnType)
        return result
    }
    @Test fun realEngineDifferentialValuesAndAstTypes() {
        for ((yes, tail) in listOf("7" to "42", "'hello'" to "'world'", "true" to "false", "nullUnit" to "nullUnit")) {
            for (condition in listOf("false", "true")) {
                val expression = chain(40, yes, tail, condition)
                val a = original(expression); val b = optimized(expression)
                assertEquals(signature(a), signature(b))
                assertEquals(a.returnType, b.returnType)
                when (a.returnType) {
                    `LogicBoolean$ReturnType`.number -> assertEquals(a.readNumber(null), b.readNumber(null))
                    `LogicBoolean$ReturnType`.string -> assertEquals(a.readString(null), b.readString(null))
                    `LogicBoolean$ReturnType`.bool -> assertEquals(a.read(null), b.read(null))
                    `LogicBoolean$ReturnType`.unit -> assertEquals(a.readUnit(null), b.readUnit(null))
                    else -> error("Unexpected type")
                }
            }
        }
    }
    @Test fun deep1539LayerChainKeepsOriginalAstAndResult() {
        val expression = chain(1539, "1", "123")
        val b = optimized(expression)
        assertEquals(1539 * 3 + 1, signature(b).size)
        assertEquals(123f, b.readNumber(null))
        var baseline: Result<LogicBoolean>? = null
        // The unmodified parser uses several Java frames per select. Give the reference its engine-sized stack.
        val worker = Thread(null, { baseline = runCatching { original(expression) } }, "select-reference", 32L shl 20)
        worker.start(); worker.join()
        val a = baseline!!.getOrThrow()
        assertEquals(signature(a), signature(b))
        assertEquals(a.readNumber(null), b.readNumber(null))
    }
    @Test fun errorsAndConservativeFallbackAgreeWithEngine() {
        for (expression in listOf(
            chain(40, "1", "'wrong type'"), chain(40, "1", "1", "42"),
            chain(40, "select(true, 2, 3)", "4"),
            chain(40, "'a,b'", "'c'"), chain(40, "'a(b)'", "'c'"),
            chain(40, "1", "select(true,1)"), chain(40, "1", "select(true,1,2,3)"),
            chain(40, "1", "memory.missing[0]"),
            chain(40, "1", "(select(true, 2, 3))"),
            chain(40, "1", "select(true, 2, 3) + 4"),
            chain(40, "1", "2").replace("false", " false "), chain(40, "1", "2").replace("select", "SeLeCt ")
        )) {
            val a = runCatching { original(expression) }; val b = runCatching { optimized(expression) }
            assertEquals(a.isSuccess, b.isSuccess, expression.take(100))
            if (a.isFailure) {
                assertEquals(a.exceptionOrNull()!!::class, b.exceptionOrNull()!!::class)
                assertEquals(a.exceptionOrNull()!!.message, b.exceptionOrNull()!!.message)
            } else assertEquals(a.getOrThrow().readNumber(null), b.getOrThrow().readNumber(null))
        }
    }
    @Test fun unusedBranchesRemainShortCircuited() {
        // A unit property would dereference the null unit if the unselected branch were evaluated.
        for (expression in listOf(chain(40, "self.hp", "123"), chain(40, "7", "self.hp", "true"))) {
            assertEquals(original(expression).readNumber(null), optimized(expression).readNumber(null))
        }
    }
    @Test fun escapedStringsFallBackWithoutReinterpretation() {
        val expression = chain(40, "'a" + "\\" + "'b'", "'c'")
        assertNull(SelectChainParser.scan(expression.substringAfter('(').dropLast(1)))
        val a = runCatching { original(expression).readString(null) }
        val b = runCatching { optimized(expression).readString(null) }
        assertEquals(a.getOrNull(), b.getOrNull())
        assertEquals(a.exceptionOrNull()?.javaClass, b.exceptionOrNull()?.javaClass)
        assertEquals(a.exceptionOrNull()?.message, b.exceptionOrNull()?.message)
    }
    @Test fun scannerDoesNotChangeUnsupportedSyntax() {
        assertNull(SelectChainParser.scan("false,1,2"))
        assertNull(SelectChainParser.scan(chain(40,"'a,b'","'c'").substringAfter('(').dropLast(1)))
        assertNull(SelectChainParser.scan("false,1,select(true,1,2,3)", 1))
        val expression = chain(40, "select(true, 1, 2)", "3").replace("select", " SELECT ")
        assertEquals(40, SelectChainParser.scan(expression.substringAfter('(').dropLast(1))!!.size)
    }
}
