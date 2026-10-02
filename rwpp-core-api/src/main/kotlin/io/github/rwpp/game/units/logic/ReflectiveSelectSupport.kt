/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.units.logic

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Engine-independent bridge used by the measurement agent and isolated real-core tests. */
object ReflectiveSelectSupport {
    private const val LOGIC_PACKAGE = "com.corrodinggames.rts.game.units.custom.logicBooleans."
    private val bindings = ConcurrentHashMap<Class<*>, Binding>()

    @JvmStatic
    fun parseInto(select: Any, arguments: String?, meta: Any?, context: String?): Boolean {
        if (arguments == null) return false
        val binding = binding(select)
        if (select.javaClass != binding.selectClass) return false
        return IterativeSelectSupport.parseInto(select, arguments, Adapter(binding, meta, context, null))
    }

    @JvmStatic
    fun selectedLeaf(select: Any, unit: Any?): Any =
        IterativeSelectSupport.selectedLeaf(select, Adapter(binding(select), null, null, unit))

    @JvmStatic
    fun debugReason(select: Any, unit: Any?): String {
        val binding = binding(select)
        return IterativeSelectSupport.debugReason(
            select,
            Adapter(binding, null, null, unit),
        ) { leaf -> (binding.reason.invokeCore(leaf, unit) as String?) ?: "null" }
    }

    private fun binding(select: Any): Binding {
        val nativeSelect = select.javaClass.classLoader.loadClass(LOGIC_PACKAGE + "LogicString\$Select")
        return bindings.getOrPut(nativeSelect) { Binding(nativeSelect) }
    }

    private class Binding(val selectClass: Class<*>) {
        private val loader = selectClass.classLoader
        private val logicClass = loader.loadClass(LOGIC_PACKAGE + "LogicBoolean")
        private val metaClass = loader.loadClass("com.corrodinggames.rts.game.units.custom.l")
        private val parserClass = loader.loadClass(LOGIC_PACKAGE + "LogicBooleanLoader")
        private val exceptionClass = loader.loadClass(LOGIC_PACKAGE + "BooleanParseException")
        val selector = field("selector")
        val childA = field("childA")
        val childB = field("childB")
        val commonType = field("commonType")
        val constructor = selectClass.getConstructor()
        val parser = parserClass.getMethod("parseBooleanBlock", metaClass, String::class.java, Boolean::class.javaPrimitiveType)
        val stripOuterBrackets = parserClass.getMethod("breakOuterLayerBrackets", String::class.java)
        val returnType = logicClass.getMethod("getReturnType")
        val read = logicClass.methods.single { it.name == "read" && it.parameterCount == 1 }
        val reason = logicClass.methods.single { it.name == "getMatchFailReasonForPlayer" && it.parameterCount == 1 }
        val throwVoid = logicClass.getMethod("throwVoidReturnError", String::class.java)

        private fun field(name: String): Field = selectClass.getDeclaredField(name).apply { isAccessible = true }

        fun error(message: String): Nothing {
            val error = exceptionClass.getConstructor(String::class.java).newInstance(message) as Throwable
            throw error
        }
    }

    private class Adapter(
        private val binding: Binding,
        private val meta: Any?,
        @Suppress("unused") private val context: String?,
        private val unit: Any?,
    ) : IterativeSelectSupport.Adapter<Any> {
        override fun parseLeaf(text: String, booleanExpected: Boolean): Any? =
            binding.parser.invokeCore(null, meta, text, booleanExpected)

        override fun newSelect(): Any = binding.constructor.newInstance()
        override fun setSelector(node: Any, value: Any?) =
            binding.selector.set(node, value ?: binding.error("Expected non-null argument"))
        override fun setChildA(node: Any, value: Any?) =
            binding.childA.set(node, value ?: binding.error("Expected non-null argument"))
        override fun setChildB(node: Any, value: Any?) =
            binding.childB.set(node, value ?: binding.error("Expected non-null argument"))

        override fun finish(node: Any) {
            if (binding.selector.get(node) == null || binding.childA.get(node) == null || binding.childB.get(node) == null) {
                binding.error("Expected non-null argument")
            }
            val aType = binding.returnType.invokeCore(binding.childA.get(node))
            val bType = binding.returnType.invokeCore(binding.childB.get(node))
            binding.commonType.set(node, aType)
            if (aType != bType) {
                binding.error("Select() expected 2 and 3 argument to be the same type, got: ${(aType as Enum<*>).name} and ${(bType as Enum<*>).name}")
            }
        }

        override fun requireBoolean(node: Any, expression: () -> String) {
            val type = binding.returnType.invokeCore(node) as Enum<*>
            if (type.name == "bool") return
            val text = binding.stripOuterBrackets.invokeCore(null, expression().trim()) as String
            if (type.name == "voidReturn") binding.throwVoid.invokeCore(node, text)
            binding.error("Function:'$text' is expected to return a boolean type but it returns type: $type")
        }

        override fun isSelect(node: Any): Boolean = node.javaClass == binding.selectClass
        override fun selector(node: Any): Any = binding.selector.get(node)
        override fun childA(node: Any): Any = binding.childA.get(node)
        override fun childB(node: Any): Any = binding.childB.get(node)
        override fun readBoolean(node: Any): Boolean = binding.read.invokeCore(node, unit) as Boolean
    }

    private fun Method.invokeCore(receiver: Any?, vararg arguments: Any?): Any? = try {
        invoke(receiver, *arguments)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
