/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

@file:JvmName("PlatformExpansionFileChanges")

package io.github.rwpp.content

import android.os.Build
import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

// AppContext places expansions on primary external storage. A removable FAT volume has no
// independent change time. Resolve the primary emulated root once, outside the per-entry scan.
private val emulatedStorageRoot by lazy {
    if (Environment.isExternalStorageEmulated()) Environment.getExternalStorageDirectory().toPath() else null
}

internal actual fun readExpansionFileChange(file: File): ExpansionFileChange? {
    return try {
        val root = emulatedStorageRoot ?: return null
        if (!file.toPath().toAbsolutePath().normalize().startsWith(root)) return null
        val stat = Os.lstat(file.path)
        if (!OsConstants.S_ISREG(stat.st_mode) && !OsConstants.S_ISDIR(stat.st_mode)) return null
        if (stat.st_dev == 0L || stat.st_ino == 0L) return null
        val nanos = if (Build.VERSION.SDK_INT >= 27) stat.st_ctim.tv_nsec else 0L
        ExpansionFileChange("${stat.st_dev}:${stat.st_ino}:${stat.st_ctime}:$nanos", stat.st_ctime)
    } catch (_: ErrnoException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
