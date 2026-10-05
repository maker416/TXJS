/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import android.content.Context
import android.os.Build
import java.io.File

/** 同版本重装也视为一次新安装，不依赖用户版本名或应用内更新入口。 */
@Suppress("DEPRECATION")
fun androidInstallationId(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    val apk = File(context.applicationInfo.sourceDir)
    return "$versionCode:${info.lastUpdateTime}:${apk.length()}:${apk.lastModified()}"
}
