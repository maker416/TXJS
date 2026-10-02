/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import io.github.rwpp.core.MemoryUsage
import io.github.rwpp.core.parseProcStatusResidentBytes
import java.io.File

actual fun readMemoryUsage(): MemoryUsage {
    val runtime = Runtime.getRuntime()
    val heapUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0)
    return MemoryUsage(
        heapUsedBytes = heapUsed,
        heapMaxBytes = runtime.maxMemory().coerceAtLeast(0),
        processResidentBytes = readResidentBytes(),
    )
}

private fun readResidentBytes(): Long? = try {
    when {
        Platform.isWindows() -> WindowsResidentMemory.read()
        Platform.isMac() -> MacResidentMemory.read()
        Platform.isLinux() -> File("/proc/self/status").bufferedReader().use {
            parseProcStatusResidentBytes(it.lineSequence())
        }
        else -> null
    }
} catch (_: Exception) {
    // OS permissions or an unavailable native binding must not break the monitor.
    null
} catch (_: LinkageError) {
    null
}

private interface MemoryKernel32 : StdCallLibrary {
    fun GetCurrentProcess(): Pointer
}

private interface MemoryPsapi : StdCallLibrary {
    fun GetProcessMemoryInfo(process: Pointer, counters: Pointer, size: Int): Boolean
}

private object WindowsResidentMemory {
    private val kernel = Native.load("kernel32", MemoryKernel32::class.java)
    private val psapi = Native.load("psapi", MemoryPsapi::class.java)

    fun read(): Long? {
        // PROCESS_MEMORY_COUNTERS: two DWORDs followed by eight SIZE_Ts.
        // WorkingSetSize follows PeakWorkingSetSize; SIZE_T is pointer-sized on Windows.
        val size = 8 + 8 * Native.SIZE_T_SIZE
        return Memory(size.toLong()).use { counters ->
            counters.clear()
            counters.setInt(0, size)
            if (!psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(), counters, size)) {
                null
            } else {
                val offset = (8 + Native.SIZE_T_SIZE).toLong()
                val bytes = if (Native.SIZE_T_SIZE == 8) counters.getLong(offset)
                    else counters.getInt(offset).toLong() and 0xffffffffL
                bytes.takeIf { it >= 0 }
            }
        }
    }
}

private interface MemoryLibProc : Library {
    fun proc_pidinfo(pid: Int, flavor: Int, arg: Long, buffer: Pointer, size: Int): Int
}

private object MacResidentMemory {
    private val libproc = Native.load("proc", MemoryLibProc::class.java)
    private const val PROC_PIDTASKINFO = 4
    // proc_taskinfo: six uint64_t fields followed by twelve int32_t fields.
    private const val TASK_INFO_SIZE = 96

    fun read(): Long? = Memory(TASK_INFO_SIZE.toLong()).use { info ->
        val readBytes = libproc.proc_pidinfo(
            ProcessHandle.current().pid().toInt(), PROC_PIDTASKINFO, 0, info, TASK_INFO_SIZE,
        )
        if (readBytes != TASK_INFO_SIZE) null
        // pti_resident_size is the second uint64_t, after pti_virtual_size.
        else info.getLong(8).takeIf { it >= 0 }
    }
}
