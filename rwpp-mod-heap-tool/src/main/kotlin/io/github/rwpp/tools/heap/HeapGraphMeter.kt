/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.tools.heap

import io.github.rwpp.tools.heap.agent.HeapAgent
import java.lang.ref.Reference
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.Buffer
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** An actual unit definition returned by the game loader. */
data class HeapUnitRoot(val name: String, val fileName: String, val value: Any)

/** Shared objects are reported once at the graph level, rather than credited to every unit. */
data class HeapUnitMeasurement(
    val name: String,
    val fileName: String,
    val exclusiveBytes: Long,
    val exclusiveObjectCount: Long,
)

/**
 * Measured shallow sizes of the identity-deduplicated, bounded reachable Java object graph.
 * This is not a GC-root/dominator retained-size calculation, an Android ART measurement,
 * or the size of native buffers, decoded native media, or GPU textures.
 */
data class HeapGraphMeasurement(
    val totalBytes: Long,
    val sharedBytes: Long,
    val objectCount: Long,
    val sharedObjectCount: Long,
    val boundaryObjectCount: Long,
    val units: List<HeapUnitMeasurement>,
    val jvmDescription: String,
) {
    val limitations: List<String>
        get() = listOf(
            "字节数由当前 JVM 的 Instrumentation.getObjectSize 测量；不能换算为 Android ART 堆。",
            "结果为单位根的去重可达 Java 对象图，不是基于 GC 根和支配关系的保留堆。",
            "单位引用在单位定义边界截止；共享对象只在共享区计一次，单位排行仅显示独占对象。",
            "不包含独立全局缓存、单位实例、加载临时对象、native 内存或显存；direct buffer 仅计 Java 包装对象。",
            "Class、ClassLoader、Thread、ThreadGroup、Reference 和调用方指定的对象作为遍历边界。",
        )
}

class HeapGraphAccessException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Traversal has to run while the loader and measured objects are quiescent. The worker starts
 * with a Java agent and opens the JDK packages it needs; inaccessible fields fail the whole
 * measurement instead of silently producing a smaller value.
 */
class HeapGraphMeter internal constructor(private val shallowSize: (Any) -> Long) {
    constructor() : this({ HeapAgent.objectSize(it) })

    fun measure(
        roots: List<HeapUnitRoot>,
        unitDefinitionClass: Class<*>? = null,
        excludeObject: (Any) -> Boolean = { false },
    ): HeapGraphMeasurement {
        val rootObjects = IdentityHashMap<Any, Int>()
        for ((index, root) in roots.withIndex()) {
            require(rootObjects.put(root.value, index) == null) {
                "同一个单位定义对象不能登记为多个测量根: ${root.name}"
            }
            require(!isRuntimeBoundary(root.value) && !excludeObject(root.value)) {
                "测量根本身位于排除边界: ${root.name}"
            }
        }

        val nodes = IdentityHashMap<Any, GraphNode>()
        val boundaries = IdentityHashMap<Any, Boolean>()
        val fieldsByClass = HashMap<Class<*>, List<Field>>()
        val pending = ArrayDeque<GraphNode>()

        fun reach(value: Any, owner: Int) {
            val existing = nodes[value]
            if (existing == null) {
                val node = GraphNode(value, shallowSize(value), owner)
                require(node.bytes > 0) { "JVM 返回了无效对象大小: ${value.javaClass.name}: ${node.bytes}" }
                nodes[value] = node
                pending.addLast(node)
            } else if (existing.owner != owner && existing.owner != SHARED) {
                existing.owner = SHARED
                pending.addLast(existing)
            }
        }

        roots.forEachIndexed { index, root -> reach(root.value, index) }

        while (pending.isNotEmpty()) {
            checkCancelled()
            val node = pending.removeFirst()
            if (node.propagatedOwner == node.owner) continue
            val owner = node.owner
            node.propagatedOwner = owner
            fun visit(child: Any?) {
                if (child == null) return
                // Includes self/cross-unit back-references: the separate root owns its definition.
                if (rootObjects.containsKey(child)) return
                if (unitDefinitionClass?.isInstance(child) == true ||
                    isRuntimeBoundary(child) || excludeObject(child)
                ) {
                    boundaries[child] = true
                    return
                }
                reach(child, owner)
            }

            val value = node.value
            val type = value.javaClass
            if (type.isArray) {
                if (!type.componentType.isPrimitive) {
                    for (index in 0 until ReflectArray.getLength(value)) {
                        if ((index and 1023) == 0) checkCancelled()
                        visit(ReflectArray.get(value, index))
                    }
                }
            } else if (value !is Buffer || !value.isDirect) {
                // A direct buffer's attachment/cleaner belongs to native allocation management.
                // Heap-backed buffers are traversed normally, including their backing arrays.
                for (field in fieldsByClass.getOrPut(type) { referenceFields(type) }) {
                    val child = try {
                        field.get(value)
                    } catch (error: IllegalAccessException) {
                        throw accessFailure(field, error)
                    }
                    visit(child)
                }
            }
        }

        val unitBytes = LongArray(roots.size)
        val unitCounts = LongArray(roots.size)
        var totalBytes = 0L
        var sharedBytes = 0L
        var sharedCount = 0L
        for (node in nodes.values) {
            totalBytes = Math.addExact(totalBytes, node.bytes)
            if (node.owner == SHARED) {
                sharedBytes = Math.addExact(sharedBytes, node.bytes)
                sharedCount++
            } else {
                check(node.owner != UNOWNED) { "对象图归属传播未完成" }
                unitBytes[node.owner] = Math.addExact(unitBytes[node.owner], node.bytes)
                unitCounts[node.owner]++
            }
        }
        return HeapGraphMeasurement(
            totalBytes = totalBytes,
            sharedBytes = sharedBytes,
            objectCount = nodes.size.toLong(),
            sharedObjectCount = sharedCount,
            boundaryObjectCount = boundaries.size.toLong(),
            units = roots.mapIndexed { index, root ->
                HeapUnitMeasurement(root.name, root.fileName, unitBytes[index], unitCounts[index])
            }.sortedByDescending { it.exclusiveBytes },
            jvmDescription = "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")} (${System.getProperty("os.arch")})",
        )
    }

    // An object can only move from one unit to SHARED, so its references need at most two
    // scans. No adjacency lists or root-sized owner sets are retained: space is O(objects).
    private class GraphNode(val value: Any, val bytes: Long, var owner: Int) {
        var propagatedOwner: Int = UNOWNED
    }

    private fun referenceFields(type: Class<*>): List<Field> = buildList {
        var current: Class<*>? = type
        while (current != null) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                // Transient fields still retain heap, notably utility.m.b and ArrayList.elementData.
                try {
                    if (!field.trySetAccessible()) throw accessFailure(field)
                } catch (error: SecurityException) {
                    throw accessFailure(field, error)
                }
                add(field)
            }
            current = current.superclass
        }
    }

    private fun accessFailure(field: Field, cause: Throwable? = null): HeapGraphAccessException {
        val owner = field.declaringClass
        val module = owner.module.name
        val opening = if (module != null) "；请添加 --add-opens=$module/${owner.packageName}=ALL-UNNAMED" else ""
        return HeapGraphAccessException("无法读取 ${owner.name}.${field.name}，本次测量已停止$opening", cause)
    }

    private fun isRuntimeBoundary(value: Any): Boolean = value is Class<*> || value is ClassLoader ||
        value is Thread || value is ThreadGroup || value is Reference<*>

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("对象图测量已取消")
    }

    private companion object {
        const val UNOWNED = -1
        const val SHARED = -2
    }
}
