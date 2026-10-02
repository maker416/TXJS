/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineMeasurementFailureTest {
    @Test
    fun preservesExceptionTitleAheadOfVeryLongRecursiveStack() = withFile { file ->
        val error = StackOverflowError().apply {
            stackTrace = Array(5000) { StackTraceElement("Parser", "select", "Parser.java", it) }
        }
        EngineMeasurementFailure.write(file, error, "真实核心加载")
        val report = requireNotNull(EngineMeasurementFailure.read(file))
        assertContains(report, "异常：java.lang.StackOverflowError")
        assertContains(report, "线程栈溢出")
        assertContains(report, "已省略 4976 层栈")
        assertContains(report, "提高堆上限无法解决")
        assertTrue(file.length() < 32 * 1024)
    }

    @Test
    fun identifiesHeapFailureDuringDiagnosticPhaseAndDeepestCause() = withFile { file ->
        val cause = OutOfMemoryError("Java heap space")
        EngineMeasurementFailure.write(file, IllegalStateException("wrapped", cause), "单位对象图诊断")
        val report = requireNotNull(EngineMeasurementFailure.read(file))
        assertContains(report, "异常：java.lang.IllegalStateException: wrapped")
        assertContains(report, "最深原因：java.lang.OutOfMemoryError: Java heap space")
        assertContains(report, "核心已完成加载")
        assertFalse(report.contains("线程栈溢出"))
    }

    @Test
    fun stopsAtCauseCycles() = withFile { file ->
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second", first)
        first.initCause(second)
        EngineMeasurementFailure.write(file, first, "核心加载")
        val report = requireNotNull(EngineMeasurementFailure.read(file))
        assertContains(report, "最深原因：java.lang.IllegalArgumentException: second")
        assertContains(report, "原因链存在循环")
    }

    @Test
    fun logFallbackKeepsFinalFatalTitleRatherThanEarlierAudioWarning() = withFile { file ->
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("java.lang.RuntimeException: failed sound")
            writer.appendLine("\tat Audio.load(Audio.java:1)")
            writer.appendLine("Warning: Sound file found but failed to load")
            writer.appendLine("Found map: sandbox.tmx")
            writer.appendLine("java.lang.StackOverflowError")
            repeat(5000) { writer.appendLine("\tat Parser.select(Parser.java:$it)") }
        }
        val report = EngineMeasurementFailure.readLogFailure(file)
        assertContains(report, "引擎日志中的最后异常：java.lang.StackOverflowError")
        assertContains(report, "Parser.select(Parser.java:0)")
        assertContains(report, "Parser.select(Parser.java:4999)")
        assertFalse(report.contains("failed sound"))
        assertTrue(report.length < 10000)
    }

    @Test
    fun emptyReportFallsBackAndUnrecognizedLogKeepsTail() = withFile { file ->
        assertNull(EngineMeasurementFailure.read(file))
        file.writeText("Invalid initial heap size\nError: Could not create the Java Virtual Machine.\n")
        assertContains(EngineMeasurementFailure.readLogFailure(file), "Could not create")
    }

    @Test
    fun logFallbackRecognizesDeepCauseAfterTruncatedWrapperStack() = withFile { file ->
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("Exception in thread \"main\" java.lang.IllegalStateException: wrapped")
            repeat(100) { writer.appendLine("\tat Loader.load(Loader.java:$it)") }
            writer.appendLine("Caused by: java.lang.OutOfMemoryError: Java heap space")
            writer.appendLine("\tat Parser.allocate(Parser.java:1)")
        }
        val report = EngineMeasurementFailure.readLogFailure(file)
        assertContains(report, "引擎日志中的最后异常：Exception in thread \"main\"")
        assertContains(report, "最深原因：Caused by: java.lang.OutOfMemoryError: Java heap space")
        assertContains(report, "内存分配失败")
    }

    private fun withFile(block: (File) -> Unit) {
        val file = File.createTempFile("heap-failure-test-", ".txt")
        try { block(file) } finally { file.delete() }
    }
}
