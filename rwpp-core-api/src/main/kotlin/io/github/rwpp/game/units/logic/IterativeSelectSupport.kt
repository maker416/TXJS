/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.units.logic

/** 保留原版 Select 节点、校验顺序和惰性分支，只替换递归遍历与尾部字符串复制。 */
object IterativeSelectSupport {
    interface Adapter<N : Any> {
        fun parseLeaf(text: String, booleanExpected: Boolean): N?
        fun newSelect(): N
        fun setSelector(node: N, value: N?)
        fun setChildA(node: N, value: N?)
        fun setChildB(node: N, value: N?)
        fun finish(node: N)
        fun requireBoolean(node: N, expression: () -> String)
        fun isSelect(node: N): Boolean
        fun selector(node: N): N
        fun childA(node: N): N
        fun childB(node: N): N
        fun readBoolean(node: N): Boolean
    }

    /** 不改变不明确语法的行为；返回 false 时调用方继续执行原版 setArgumentsRaw。 */
    fun <N : Any> parseInto(root: N, arguments: String, adapter: Adapter<N>): Boolean {
        val scanner = SelectArgumentScanner(arguments)
        val rootArguments = scanner.arguments(scanner.fullSpan) ?: return false
        val pending = ArrayDeque<ParseFrame<N>>()
        pending.addLast(ParseFrame(root, scanner.fullSpan, rootArguments, false))
        while (pending.isNotEmpty()) {
            val frame = pending.last()
            if (frame.nextArgument < 3) {
                val index = frame.nextArgument++
                val span = frame.arguments[index]
                val nested = scanner.selectArguments(span)?.let(scanner::arguments)
                if (nested != null) {
                    pending.addLast(ParseFrame(adapter.newSelect(), span, nested, index == 0))
                } else {
                    assign(adapter, frame.node, index, adapter.parseLeaf(span.text(arguments), index == 0))
                }
            } else {
                adapter.finish(frame.node)
                if (frame.booleanExpected) adapter.requireBoolean(frame.node) { frame.expression.text(arguments) }
                pending.removeLast()
                if (pending.isNotEmpty()) {
                    val parent = pending.last()
                    assign(adapter, parent.node, parent.nextArgument - 1, frame.node)
                }
            }
        }
        return true
    }

    private fun <N : Any> assign(adapter: Adapter<N>, node: N, index: Int, value: N?) {
        when (index) {
            0 -> adapter.setSelector(node, value)
            1 -> adapter.setChildA(node, value)
            2 -> adapter.setChildB(node, value)
        }
    }

    /**
     * root 是正在执行原版 Select.read* 的接收者；子类自定义的 read* 仍由引擎动态分派。
     * 只有条件本身又是 Select 时才分配显式栈，常见的长 else 链仅需循环。
     */
    fun <N : Any> selectedLeaf(root: N, adapter: Adapter<N>): N {
        var current = root
        var rootStep = true
        var conditions: ArrayDeque<N>? = null
        while (true) {
            if (rootStep || adapter.isSelect(current)) {
                rootStep = false
                val condition = adapter.selector(current)
                if (adapter.isSelect(condition)) {
                    val stack = conditions ?: ArrayDeque<N>().also { conditions = it }
                    stack.addLast(current)
                    current = condition
                } else {
                    current = if (adapter.readBoolean(condition)) adapter.childA(current) else adapter.childB(current)
                }
            } else {
                val stack = conditions
                if (stack == null || stack.isEmpty()) return current
                val parent = stack.removeLast()
                current = if (adapter.readBoolean(current)) adapter.childA(parent) else adapter.childB(parent)
            }
        }
    }

    /** 原调试字符串按相同顺序展开，不在深层错误诊断时再次溢出线程栈。 */
    fun <N : Any> debugReason(root: N, adapter: Adapter<N>, leafReason: (N) -> String?): String {
        val output = StringBuilder()
        val pending = ArrayDeque<DebugTask<N>>()
        pending.addLast(NodeTask(root, true))
        while (pending.isNotEmpty()) {
            when (val task = pending.removeLast()) {
                is TextTask -> output.append(task.text)
                is NodeTask -> {
                    if (!task.root && !adapter.isSelect(task.node)) {
                        output.append(leafReason(task.node))
                    } else {
                        pending.addLast(TextTask(") )"))
                        pending.addLast(NodeTask(adapter.childB(task.node)))
                        pending.addLast(TextTask(") ) else:("))
                        pending.addLast(NodeTask(adapter.childA(task.node)))
                        pending.addLast(TextTask(") then:("))
                        pending.addLast(NodeTask(adapter.selector(task.node)))
                        pending.addLast(TextTask("(selector if:("))
                    }
                }
            }
        }
        return output.toString()
    }

    private class ParseFrame<N : Any>(
        val node: N,
        val expression: TextSpan,
        val arguments: List<TextSpan>,
        val booleanExpected: Boolean,
        var nextArgument: Int = 0,
    )

    private sealed interface DebugTask<out N>
    private class TextTask(val text: String) : DebugTask<Nothing>
    private class NodeTask<N>(val node: N, val root: Boolean = false) : DebugTask<N>
}
