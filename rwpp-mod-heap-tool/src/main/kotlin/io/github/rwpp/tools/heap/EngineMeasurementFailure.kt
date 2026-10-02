/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/** 独立于完整引擎日志保存失败原因，避免递归栈淹没异常标题。 */
internal object EngineMeasurementFailure {
    private const val MAX_REPORT_BYTES = 32 * 1024
    private const val MAX_LINE_LENGTH = 1200
    private const val SUMMARY_FRAMES = 24
    private const val TAIL_LINES = 12
    private val exceptionTitle = Regex(
        "^(?:Exception in thread \\\"[^\\\"]+\\\"\\s+)?[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+(?::.*)?$",
    )

    fun write(file: File, error: Throwable, phase: String) {
        val causes = causeChain(error)
        val root = causes.last()
        val summary = buildString {
            appendLine("失败阶段：${phase.take(500)}")
            appendLine("异常：${describe(error)}")
            if (root !== error) appendLine("最深原因：${describe(root)}")
            appendLine(explanation(causes, phase))
            appendLine("异常栈摘要：")
            appendStack(error)
            if (root !== error) {
                appendLine("最深原因栈摘要：")
                appendStack(root)
            }
            if (root.cause != null) appendLine("原因链存在循环，已停止追踪。")
        }
        // UTF-8 每个 UTF-16 字符最多占三个字节；连异常消息与第三方栈元素也有界。
        file.writeText(summary.take(MAX_REPORT_BYTES / 3), Charsets.UTF_8)
    }

    fun read(file: File): String? {
        if (!file.isFile) return null
        return file.inputStream().use { input ->
            val bytes = ByteArray(MAX_REPORT_BYTES)
            var length = 0
            while (length < bytes.size) {
                val count = input.read(bytes, length, bytes.size - length)
                if (count < 0) break
                length += count
            }
            String(bytes, 0, length, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        }
    }

    /** 兼容未生成独立摘要的 JVM 启动失败；只保留最后一个带栈的异常及少量日志尾。 */
    fun readLogFailure(file: File): String {
        if (!file.isFile) return "未生成引擎日志"
        val tail = ArrayDeque<String>()
        var pendingTitle: String? = null
        var lastTitle: String? = null
        var lastCause: String? = null
        var lastStack = mutableListOf<String>()
        var inStack = false
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { raw ->
                val line = raw.take(MAX_LINE_LENGTH)
                tail.addLast(line)
                if (tail.size > TAIL_LINES) tail.removeFirst()
                if (exceptionTitle.matches(line)) {
                    pendingTitle = line
                    inStack = false
                } else if (pendingTitle != null && line.trimStart().startsWith("at ")) {
                    lastTitle = pendingTitle
                    lastCause = null
                    pendingTitle = null
                    lastStack = mutableListOf(line)
                    inStack = true
                } else {
                    pendingTitle = null
                    if (inStack && (line.trimStart().startsWith("at ") ||
                            line.startsWith("Caused by:") || line.trimStart().startsWith("... "))) {
                        if (line.startsWith("Caused by:")) lastCause = line
                        if (lastStack.size < SUMMARY_FRAMES) lastStack += line
                    } else inStack = false
                }
            }
        }
        return buildString {
            if (lastTitle != null) {
                appendLine("引擎日志中的最后异常：$lastTitle")
                lastCause?.let { appendLine("最深原因：$it") }
                val reason = "$lastTitle $lastCause"
                when {
                    reason.contains("StackOverflowError") -> appendLine(stackExplanation)
                    reason.contains("OutOfMemoryError") -> appendLine(heapExplanation)
                }
                lastStack.forEach(::appendLine)
            }
            appendLine("日志末尾：")
            tail.forEach(::appendLine)
        }.trimEnd()
    }

    private fun causeChain(error: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val causes = mutableListOf<Throwable>()
        var next: Throwable? = error
        while (next != null && seen.add(next)) {
            causes += next
            next = next.cause
        }
        return causes
    }

    private fun describe(error: Throwable): String = error.javaClass.name +
        (error.message?.take(1800)?.replace('\n', ' ')?.replace('\r', ' ')
            ?.let { ": $it" } ?: "")

    private fun StringBuilder.appendStack(error: Throwable) {
        val stack = error.stackTrace
        stack.take(SUMMARY_FRAMES).forEach { appendLine("\tat $it".take(MAX_LINE_LENGTH)) }
        if (stack.size > SUMMARY_FRAMES) appendLine("\t… 已省略 ${stack.size - SUMMARY_FRAMES} 层栈")
    }

    private fun explanation(causes: List<Throwable>, phase: String): String = when {
        causes.any { it is StackOverflowError } -> stackExplanation
        causes.any { it is OutOfMemoryError } -> if (phase.contains("对象图") || phase.contains("诊断")) {
            "对象图诊断阶段内存不足：核心已完成加载，此次失败发生在附加诊断中。" + heapExplanation
        } else heapExplanation
        else -> "核心或测量流程抛出异常，请结合失败阶段和异常栈检查。"
    }

    private const val stackExplanation =
        "线程栈溢出（StackOverflowError）：通常由表达式递归过深引起；Java 堆上限（-Xmx）与线程栈（-Xss）独立，提高堆上限无法解决。"
    private const val heapExplanation =
        "内存分配失败（OutOfMemoryError）：请结合异常消息检查 Java 堆、原生内存或线程资源；Java heap space 可通过提高测量进程的堆上限排查。"
}
