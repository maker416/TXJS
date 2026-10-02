/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.units.logic

/** A half-open interval in the original expression; nested branches keep sharing that expression. */
data class TextSpan(val start: Int, val end: Int) {
    fun text(source: String): String = source.substring(start, end)
}

/**
 * Indexes brackets once, then skips whole nested expressions when locating select arguments.
 * Only syntax for which the desktop and Android core splitters agree is accepted. In particular,
 * their argument splitters do not understand quotes, so punctuation inside strings must fall back
 * to the original parser rather than acquiring different semantics through this optimization.
 */
class SelectArgumentScanner(val source: String) {
    val fullSpan: TextSpan = TextSpan(0, source.length)
    private val matchingBracket = IntArray(source.length) { -1 }
    val isSafe: Boolean = indexBrackets()

    /** Returns the three trimmed argument intervals, or null to request the original parser. */
    fun arguments(span: TextSpan): List<TextSpan>? {
        if (!isSafe || !inBounds(span)) return null
        val result = ArrayList<TextSpan>(3)
        var argumentStart = span.start
        var index = span.start
        while (index < span.end) {
            when (source[index]) {
                '(', '[' -> {
                    val close = matchingBracket[index]
                    if (close !in index + 1 until span.end) return null
                    index = close
                }
                ')', ']' -> return null
                ',' -> {
                    if (result.size == 2) return null
                    val argument = trim(TextSpan(argumentStart, index))
                    if (argument.start == argument.end) return null
                    result.add(argument)
                    argumentStart = index + 1
                }
            }
            index++
        }
        if (result.size != 2) return null
        val last = trim(TextSpan(argumentStart, span.end))
        if (last.start == last.end) return null
        result.add(last)
        return result
    }

    /** Recognizes a whole, optionally parenthesized select call, without a suffix or operator. */
    fun selectArguments(span: TextSpan): TextSpan? {
        if (!isSafe || !inBounds(span)) return null
        var expression = trim(span)
        while (expression.start < expression.end && source[expression.start] == '(' &&
            matchingBracket[expression.start] == expression.end - 1
        ) {
            expression = trim(TextSpan(expression.start + 1, expression.end - 1))
        }
        if (expression.end - expression.start < 8) return null
        for (offset in SELECT.indices) {
            val character = source[expression.start + offset]
            if (character != SELECT[offset] && character != SELECT[offset].uppercaseChar()) return null
        }
        var open = expression.start + SELECT.length
        while (open < expression.end && source[open] <= ' ') open++
        if (open >= expression.end || source[open] != '(' ||
            matchingBracket[open] != expression.end - 1
        ) return null
        return TextSpan(open + 1, expression.end - 1)
    }

    /** Matches java.lang.String.trim(), as used by the original parser. */
    fun trim(span: TextSpan): TextSpan {
        require(inBounds(span)) { "Expression interval is outside the source" }
        var start = span.start
        var end = span.end
        while (start < end && source[start] <= ' ') start++
        while (start < end && source[end - 1] <= ' ') end--
        return if (start == span.start && end == span.end) span else TextSpan(start, end)
    }

    private fun inBounds(span: TextSpan): Boolean =
        span.start >= 0 && span.start <= span.end && span.end <= source.length

    private fun indexBrackets(): Boolean {
        var stack = IntArray(32)
        var depth = 0
        var quote = '\u0000'
        var escaped = false
        for (index in source.indices) {
            val character = source[index]
            if (quote != '\u0000') {
                // The core's splitters treat these as syntax even inside a quoted literal.
                if (character == ',' || character == '(' || character == ')' ||
                    character == '[' || character == ']'
                ) return false
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == quote) {
                    quote = '\u0000'
                }
                continue
            }
            when (character) {
                '\'', '"' -> quote = character
                '(', '[' -> {
                    if (depth == stack.size) stack = stack.copyOf(stack.size * 2)
                    stack[depth++] = index
                }
                ')', ']' -> {
                    if (depth == 0) return false
                    val open = stack[--depth]
                    if (source[open] != if (character == ')') '(' else '[') return false
                    matchingBracket[open] = index
                }
            }
        }
        return depth == 0 && quote == '\u0000'
    }

    private companion object {
        const val SELECT = "select"
    }
}
