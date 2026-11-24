/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.core

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinCrypt
import com.sun.jna.platform.win32.WinError
import java.io.File
import java.io.IOException

internal actual fun platformDirectResumeVault(): DirectResumeVault = when {
    Platform.isWindows() -> {
        val local = System.getenv("LOCALAPPDATA") ?: throw IOException("Missing local application data directory")
        WindowsDirectResumeVault(File(local, "RWJS/direct-resume-v1.bin"))
    }
    Platform.isLinux() -> LinuxDirectResumeVault
    else -> throw IOException("Reconnect credential storage is unavailable on this platform")
}

internal class WindowsDirectResumeVault(file: File) : EncryptedDirectResumeFile(file) {
    override fun encrypt(bytes: ByteArray): ByteArray =
        Crypt32Util.cryptProtectData(bytes, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)

    override fun decrypt(bytes: ByteArray): ByteArray = try {
        Crypt32Util.cryptUnprotectData(bytes, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
    } catch (e: Win32Exception) {
        if (e.errorCode != WinError.ERROR_INVALID_DATA) throw e
        throw DirectResumeCorruptException("Reconnect file authentication failed", e)
    }
}
