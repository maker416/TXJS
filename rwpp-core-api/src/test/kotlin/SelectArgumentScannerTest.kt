/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.units.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SelectArgumentScannerTest {
    @Test
    fun argumentsSkipNestedFunctionsAndArrayIndices() {
        val scanner = SelectArgumentScanner(" self.test(1, 2), memory.items[other.index(3, 4)], select(false, 5, 6) ")
        assertEquals(
            listOf("self.test(1, 2)", "memory.items[other.index(3, 4)]", "select(false, 5, 6)"),
            scanner.arguments(scanner.fullSpan)?.map { it.text(scanner.source) }
        )
    }

    @Test
    fun quotesAndEscapedQuotesWithoutSplitterPunctuationRemainSafe() {
        val source = "true, '汉字\\\'编号', \"双引号\\\"文字\""
        val scanner = SelectArgumentScanner(source)
        assertTrue(scanner.isSafe)
        assertEquals(
            listOf("true", "'汉字\\\'编号'", "\"双引号\\\"文字\""),
            scanner.arguments(scanner.fullSpan)?.map { it.text(source) }
        )
    }

    @Test
    fun quotedPunctuationFallsBackToPreserveOriginalSplitterSemantics() {
        for (literal in listOf("'a,b'", "'(a)'", "'[a]'", "\"a,b\"", "'a\\,b'")) {
            val scanner = SelectArgumentScanner("true, $literal, 'fallback'")
            assertFalse(scanner.isSafe, literal)
            assertNull(scanner.arguments(scanner.fullSpan), literal)
        }
    }

    @Test
    fun onlyWholeSelectCallsAreRecognized() {
        val scanner = SelectArgumentScanner(" \t(( SELECT (false, 1, 2) ))\r\n")
        assertEquals("false, 1, 2", scanner.selectArguments(scanner.fullSpan)?.text(scanner.source))
        for (expression in listOf("select(false, 1, 2) + 3", "select(false, 1, 2).length", "self.select(false, 1, 2)", "selected(false, 1, 2)")) {
            val candidate = SelectArgumentScanner(expression)
            assertNull(candidate.selectArguments(candidate.fullSpan), expression)
        }
    }

    @Test
    fun malformedSyntaxFallsBack() {
        for (expression in listOf("true, f([1, 2)], 3", "true, f(1, 2, 3", "true, ], 3", "true, 'unterminated, 3")) {
            val scanner = SelectArgumentScanner(expression)
            assertFalse(scanner.isSafe, expression)
            assertNull(scanner.arguments(scanner.fullSpan), expression)
            assertNull(scanner.selectArguments(scanner.fullSpan), expression)
        }
    }

    @Test
    fun invalidArgumentCountsAndEmptyArgumentsFallBack() {
        for (expression in listOf("", "true", "true, 1", "true, 1, 2, 3", "true, , 3", ", 2, 3", "true, 2, ")) {
            val scanner = SelectArgumentScanner(expression)
            assertNull(scanner.arguments(scanner.fullSpan), expression)
        }
    }

    @Test
    fun trimUsesOriginalJavaWhitespaceRules() {
        val source = " \u0000\tfoo\r\n "
        val scanner = SelectArgumentScanner(source)
        assertEquals("foo", scanner.trim(scanner.fullSpan).text(source))
        val unicodeSpace = SelectArgumentScanner("\u2003foo\u2003")
        assertSame(unicodeSpace.fullSpan, unicodeSpace.trim(unicodeSpace.fullSpan))
    }

    @Test
    fun outOfBoundsOrPartialNestedIntervalsFallBack() {
        val scanner = SelectArgumentScanner("true, f(1, 2), 3")
        assertNull(scanner.arguments(TextSpan(-1, 10)))
        assertNull(scanner.arguments(TextSpan(0, scanner.source.length + 1)))
        assertNull(scanner.arguments(TextSpan(0, 12)))
        assertNull(scanner.selectArguments(TextSpan(3, 2)))
    }

    @Test
    fun deepSelectChainSharesOneSourceAndNeedsNoRecursiveScanning() {
        val depth = 10_000
        val source = buildString {
            repeat(depth) { append("select(false, '字', ") }
            append("'终点'")
            repeat(depth) { append(')') }
        }
        val scanner = SelectArgumentScanner(source)
        assertTrue(scanner.isSafe)
        var branch = scanner.fullSpan
        repeat(depth) {
            val arguments = assertNotNull(scanner.selectArguments(branch))
            val spans = assertNotNull(scanner.arguments(arguments))
            assertEquals("false", spans[0].text(source))
            assertEquals("'字'", spans[1].text(source))
            branch = spans[2]
        }
        assertNull(scanner.selectArguments(branch))
        assertEquals("'终点'", branch.text(source))
    }
}
