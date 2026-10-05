/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.io.IOException

/** 直接调用 Windows Shell 请求 UAC，不经过命令解释器。必须在独立工作线程调用。 */
class WindowsUpdateInstaller(
    private val execute: (File, String) -> Long = ::executeWithWindowsShell,
) {
    fun launch(installer: File) {
        require(installer.isFile) { "Missing update installer" }
        val result = execute(installer, "/RWJS_UPDATE=1 /SILENT /SP- /NORESTART /LOG")
        if (result <= 32) throw IOException("Installer launch was denied or failed (Windows code $result)")
    }
}

private interface InstallerShell : StdCallLibrary {
    fun ShellExecuteW(window: Pointer?, verb: WString, file: WString,
        parameters: WString, directory: WString, show: Int): Pointer?
}

private interface InstallerCom : StdCallLibrary {
    fun CoInitializeEx(reserved: Pointer?, mode: Int): Int
    fun CoUninitialize()
}

private fun executeWithWindowsShell(installer: File, parameters: String): Long {
    val com = Native.load("ole32", InstallerCom::class.java)
    val initialized = com.CoInitializeEx(null, 0x2 or 0x4) // STA + disable OLE1 DDE
    if (initialized < 0) throw IOException("Cannot initialize installer COM thread ($initialized)")
    try {
        val shell = Native.load("shell32", InstallerShell::class.java)
        return Pointer.nativeValue(shell.ShellExecuteW(null, WString("runas"), WString(installer.absolutePath),
            WString(parameters), WString(installer.parentFile.absolutePath), 1))
    } finally { com.CoUninitialize() }
}
