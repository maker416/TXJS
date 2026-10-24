/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Field

/** Narrow optimization for long select false-branches; all AST objects remain engine objects. */
object SelectChainParser {
    private const val MIN_CHAIN = 32
    data class Span(val start: Int, val end: Int)
    data class Branch(val condition: Span, val yes: Span, val no: Span, val arguments: Span, val expression: Span?)

    /** Null means the original parser must handle this syntax, including its errors. */
    fun scan(source: String, minimum: Int = MIN_CHAIN): List<Branch>? {
        val pairs = IntArray(source.length) { -1 }
        val stack = IntArray(source.length)
        var depth = 0
        var quote = '\u0000'
        for (i in source.indices) {
            val c = source[i]
            if (quote != '\u0000') {
                // The engine split helper does not understand quotes. Do not reinterpret these cases.
                if (c == '\\' || c in "()[],") return null
                if (c == quote) quote = '\u0000'
                continue
            }
            if (c == '\'' || c == '"') { quote = c; continue }
            if (c == '(' || c == '[') stack[depth++] = i
            if (c == ')' || c == ']') {
                if (depth == 0) return null
                val open = stack[--depth]
                if ((source[open] == '(') != (c == ')')) return null
                pairs[open] = i
            }
        }
        if (depth != 0 || quote != '\u0000') return null
        fun trim(span: Span): Span {
            var a = span.start; var b = span.end
            while (a < b && source[a] <= ' ') a++
            while (a < b && source[b - 1] <= ' ') b--
            return Span(a, b)
        }
        val result = ArrayList<Branch>()
        var args = Span(0, source.length)
        var expression: Span? = null
        while (true) {
            val commas = IntArray(2)
            var count = 0
            var i = args.start
            while (i < args.end) {
                val c = source[i]
                if (c == '(' || c == '[') i = pairs[i]
                else if (c == ',') {
                    if (count == 2) return null
                    commas[count++] = i
                }
                i++
            }
            if (count != 2) return null
            val condition = trim(Span(args.start, commas[0]))
            val yes = trim(Span(commas[0] + 1, commas[1]))
            val no = trim(Span(commas[1] + 1, args.end))
            if (condition.start == condition.end || yes.start == yes.end || no.start == no.end) return null
            result.add(Branch(condition, yes, no, args, expression))
            var next = no
            while (source[next.start] == '(' && pairs[next.start] == next.end - 1) {
                next = trim(Span(next.start + 1, next.end - 1))
                if (next.start == next.end) return null
            }
            if (next.end - next.start < 8 || !source.regionMatches(next.start, "select", 0, 6, ignoreCase = true)) break
            var open = next.start + 6
            while (open < next.end && source[open] <= ' ') open++
            if (open >= next.end || source[open] != '(' || pairs[open] != next.end - 1) break
            expression = next
            args = trim(Span(open + 1, next.end - 1))
        }
        return result.takeIf { it.size >= minimum }
    }

    private class Access(private val type: Class<*>) {
        val constructor = type.getConstructor()
        fun field(name: String): Field = type.getDeclaredField(name).apply { isAccessible = true }
        val selector = field("selector")
        val yes = field("childA")
        val no = field("childB")
        val commonType = field("commonType")
        val loader = Class.forName("com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBooleanLoader", true, type.classLoader)
        val parse = loader.methods.single { it.name == "parseBooleanBlock" && it.parameterCount == 3 }
        val returnType = selector.type.getMethod("getReturnType")
        val validate = selector.type.methods.single { it.name == "validateAndOptimize" && it.parameterCount == 5 }
        val context = loader.getDeclaredField("defaultContextReader").apply { isAccessible = true }.get(null)
        val parseError = Class.forName("com.corrodinggames.rts.game.units.custom.logicBooleans.BooleanParseException", true, type.classLoader).getConstructor(String::class.java)
    }
    // There is one active engine loader per process. Avoid retaining old engines through a global map.
    @Volatile private var cached: Pair<Class<*>, Access>? = null
    private fun access(type: Class<*>): Access {
        cached?.let { if (it.first === type) return it.second }
        return synchronized(this) {
            cached?.takeIf { it.first === type }?.second ?: Access(type).also { cached = type to it }
        }
    }

    /** Returns false without touching the receiver when syntax is outside the optimized subset. */
    fun apply(receiver: Any, source: String?, unit: Any?): Boolean {
        if (source == null || source.length < 512) return false
        if (receiver.javaClass.name != "com.corrodinggames.rts.game.units.custom.logicBooleans.LogicString\$Select") return false
        ModLoadSafety.checkpoint()
        val branches = scan(source) ?: return false
        val api = access(receiver.javaClass)
        fun text(span: Span) = source.substring(span.start, span.end)
        fun callParse(span: Span, boolean: Boolean): Any {
            ModLoadSafety.checkpoint()
            return api.parse.invoke(null, unit, text(span), boolean)
                ?: throw (api.parseError.newInstance("Expected non-null argument") as Throwable)
        }
        try {
            val selectors = ArrayList<Any>(branches.size)
            val yesValues = ArrayList<Any>(branches.size)
            // Preserve the original depth-first parsing/error order.
            for (branch in branches) {
                selectors.add(callParse(branch.condition, true))
                yesValues.add(callParse(branch.yes, false))
            }
            var tail = callParse(branches.last().no, false)
            for (i in branches.indices.reversed()) {
                ModLoadSafety.checkpoint()
                val node = if (i == 0) receiver else api.constructor.newInstance()
                val yesType = api.returnType.invoke(yesValues[i])
                val noType = api.returnType.invoke(tail)
                if (yesType != noType) throw (api.parseError.newInstance(
                    "Select() expected 2 and 3 argument to be the same type, got: ${(yesType as Enum<*>).name} and ${(noType as Enum<*>).name}"
                ) as Throwable)
                api.selector.set(node, selectors[i]); api.yes.set(node, yesValues[i]); api.no.set(node, tail)
                api.commonType.set(node, yesType)
                // The outer node is validated by its existing engine caller, exactly once.
                tail = if (i == 0) node else api.validate.invoke(node, "select", text(branches[i].arguments),
                    text(branches[i].expression!!), api.context, false)
            }
            return true
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }
}

