/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import android.os.Debug
import android.system.Os
import android.system.OsConstants
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
        totalPhysicalBytes = readTotalPhysicalBytes(),
    )
}

private fun readTotalPhysicalBytes(): Long? = try {
    // 系统可见的物理页总数，不含 swap；不依赖 Activity，启动加载时也能采样。
    val pages = Os.sysconf(OsConstants._SC_PHYS_PAGES)
    val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
    if (pages > 0 && pageSize > 0 && pages <= Long.MAX_VALUE / pageSize) pages * pageSize else null
} catch (_: Exception) {
    null
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
