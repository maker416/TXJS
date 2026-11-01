/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import android.os.Build
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.rwpp.net.browser.BrowserKernelRequirement
import java.io.File

internal actual val browserKernelDownloadRequired: Boolean get() = io.github.rwpp.core.BuildConfig.EXTERNAL_BROWSER_KERNEL

@Composable
internal actual fun rememberBrowserKernelEnvironment(): BrowserKernelEnvironment {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val abi = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS[0] else Build.SUPPORTED_32_BIT_ABIS[0]
        val requirement = BrowserKernelRequirement.read(context.assets.open("browser-kernels/android-$abi.json").bufferedReader().use { it.readText() })
        require(requirement.manifest.platform == "android" && requirement.manifest.arch == abi && requirement.manifest.engine == "gecko")
        require(requirement.manifest.version == "${org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION}.${org.mozilla.geckoview.BuildConfig.MOZ_APP_BUILDID}")
        BrowserKernelEnvironment(requirement,
            File(context.noBackupFilesDir, "browser/kernels"))
    }
}
