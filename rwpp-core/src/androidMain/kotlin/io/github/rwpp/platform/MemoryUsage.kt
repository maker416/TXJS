/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import android.os.Debug
import io.github.rwpp.core.MemoryUsage
import io.github.rwpp.core.parseProcStatusResidentBytes
import java.io.File
import java.io.IOException

actual fun readMemoryUsage(): MemoryUsage {
    val runtime = Runtime.getRuntime()
    val heapUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0)
    return MemoryUsage(
        heapUsedBytes = heapUsed,
        heapMaxBytes = runtime.maxMemory().coerceAtLeast(0),
        processResidentBytes = readResidentBytes(),
        nativeHeapBytes = Debug.getNativeHeapAllocatedSize().coerceAtLeast(0),
    )
}

private fun readResidentBytes(): Long? = try {
    // Reading self/status is cheap and available before Debug.getRss (API 35).
    File("/proc/self/status").bufferedReader().use {
        parseProcStatusResidentBytes(it.lineSequence())
    }
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}
