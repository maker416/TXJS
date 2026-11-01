/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.rwpp.net.browser.BrowserKernelRequirement
import me.friwi.jcefmaven.CefBuildInfo
import java.io.File

internal actual val browserKernelDownloadRequired: Boolean get() = true

internal object DesktopKernelEnvironment {
    val value: BrowserKernelEnvironment by lazy {
        val os = System.getProperty("os.name").lowercase().let { if (it.contains("win")) "windows" else if (it.contains("mac")) "macosx" else "linux" }
        val arch = when (System.getProperty("os.arch")) { "aarch64", "arm64" -> "arm64"; "x86", "i386" -> "i386"; "arm" -> "arm"; else -> "amd64" }
        val requirement = BrowserKernelRequirement.read(requireNotNull(javaClass.getResourceAsStream("/browser-kernels/$os-$arch.json")).bufferedReader().use { it.readText() })
        require(requirement.manifest.platform == os && requirement.manifest.arch == arch && requirement.manifest.engine == "jcef")
        require(requirement.manifest.version == CefBuildInfo.fromClasspath().releaseTag)
        val root = File(System.getProperty("user.home"), ".rwjs/browser")
        BrowserKernelEnvironment(requirement, File(root, "kernels"), File(root, "runtime-${CefBuildInfo.fromClasspath().releaseTag}"))
    }
}
@Composable internal actual fun rememberBrowserKernelEnvironment(): BrowserKernelEnvironment = remember { DesktopKernelEnvironment.value }
