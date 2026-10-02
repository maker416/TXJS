/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

/**
 * A point-in-time sample; all sizes are bytes.
 *
 * [heapMaxBytes] is the runtime's Java heap ceiling, including Android's ART limit.
 * [processResidentBytes] is current RSS / Windows working set, which includes Java
 * and native memory and shared pages. It must not be added to the heap figures.
 * [nativeHeapBytes] counts native heap allocations where the platform exposes them;
 * it is not all non-Java memory (for example, it does not account for all GPU memory).
 */
data class MemoryUsage(
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val processResidentBytes: Long? = null,
    val nativeHeapBytes: Long? = null,
) {
    val heapUsageFraction: Float
        get() = if (heapMaxBytes > 0) {
            (heapUsedBytes.coerceAtLeast(0).toDouble() / heapMaxBytes)
                .coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }

    /** Capacity remaining before the Java heap ceiling, rather than free committed heap. */
    val heapHeadroomBytes: Long
        get() = if (heapMaxBytes > 0) {
            heapMaxBytes - heapUsedBytes.coerceIn(0, heapMaxBytes)
        } else {
            0
        }
}

private val procResidentLine = Regex("^VmRSS:\\s*(\\d+)\\s+kB\\s*$")

/** Reads only VmRSS, never VmSize (virtual memory) or VmHWM (peak resident memory). */
fun parseProcStatusResidentBytes(lines: Sequence<String>): Long? {
    for (line in lines) {
        if (!line.startsWith("VmRSS:")) continue
        val kilobytes = procResidentLine.matchEntire(line)?.groupValues?.get(1)
            ?.toLongOrNull() ?: return null
        return if (kilobytes <= Long.MAX_VALUE / 1024) kilobytes * 1024 else null
    }
    return null
}
