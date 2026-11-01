/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.build.BrowserKernelPackaging

val geckoKernelSource by configurations.creating
val desktopKernelSource by configurations.creating
dependencies.add(geckoKernelSource.name, "org.mozilla.geckoview:geckoview:${BrowserKernelPackaging.GECKO}@aar")
val kernelOs = when { System.getProperty("os.name").startsWith("Windows") -> "windows"; System.getProperty("os.name").startsWith("Mac") -> "macosx"; else -> "linux" }
val kernelArch = when (System.getProperty("os.arch")) { "aarch64", "arm64" -> "arm64"; "x86", "i386" -> "i386"; "arm" -> "arm"; else -> "amd64" }
dependencies.add(desktopKernelSource.name, "me.friwi:jcef-natives-$kernelOs-$kernelArch:${BrowserKernelPackaging.JCEF}")
val kernelVersions = java.util.Properties().apply { rootProject.file("packaging/browser-kernels.properties").inputStream().use(::load) }
val geckoComponentVersion = providers.gradleProperty("geckoComponentVersion").orElse(kernelVersions.getProperty("gecko.componentVersion"))
val desktopComponentVersion = providers.gradleProperty("jcefComponentVersion").orElse(kernelVersions.getProperty("jcef.componentVersion"))
val kernelOutput = layout.buildDirectory.dir("browser-kernels")
val kernelReferences = layout.buildDirectory.dir("browser-kernel-resources")
tasks.register("prepareGeckoKernel") {
    inputs.files(geckoKernelSource)
    inputs.property("componentVersion", geckoComponentVersion)
    inputs.files(fileTree("buildSrc/src"))
    outputs.dir(kernelOutput.map { it.dir("android") })
    outputs.dir(kernelReferences.map { it.dir("android") })
    doLast {
        val sdk = java.util.Properties().apply { rootProject.file("local.properties").inputStream().use(::load) }.getProperty("sdk.dir")
        val androidJar = file("$sdk/platforms/android-37.1/android.jar").takeIf { it.isFile }
            ?: file("$sdk/platforms/android-37/android.jar")
        BrowserKernelPackaging.gecko(geckoKernelSource.singleFile, androidJar, kernelOutput.get().dir("android").asFile, geckoComponentVersion.get(), kernelReferences.get().dir("android").asFile)
    }
}
tasks.register("prepareDesktopKernel") {
    inputs.files(desktopKernelSource)
    inputs.property("componentVersion", desktopComponentVersion)
    inputs.files(fileTree("buildSrc/src"))
    outputs.dir(kernelOutput.map { it.dir("desktop") })
    outputs.dir(kernelReferences.map { it.dir("desktop") })
    doLast { BrowserKernelPackaging.desktop(desktopKernelSource.singleFile, kernelOs, kernelArch, kernelOutput.get().dir("desktop").asFile, desktopComponentVersion.get(), kernelReferences.get().dir("desktop").asFile) }
}
tasks.register("browserKernels") { group = "distribution"; dependsOn("prepareGeckoKernel", "prepareDesktopKernel") }
