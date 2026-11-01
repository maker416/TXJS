/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

plugins { id("com.android.application"); kotlin("android") }
val integrationHost = providers.gradleProperty("browserKernelIntegrationHost").orNull == "true"
android {
    namespace = "io.github.rwjs.browserkerneltest"
    compileSdk = 37
    compileSdkMinor = 1
    defaultConfig { applicationId = "io.github.rwjs.browserkerneltest"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    sourceSets["main"].assets.srcDirs(rootProject.layout.buildDirectory.dir("browser-kernel-resources/android").get().asFile, rootProject.file("rwpp-core/src/androidMain/assets"))
    packaging { jniLibs.useLegacyPackaging = true }
    if (integrationHost) sourceSets["debug"].manifest.srcFile("src/integration/AndroidManifest.xml")
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":rwpp-core-api"))
    if (integrationHost) implementation(project(":rwpp-core"))
    implementation(files(rootProject.layout.buildDirectory.file("browser-kernels/android/gecko-thin.aar")).builtBy(rootProject.tasks.named("prepareGeckoKernel")))
}
tasks.matching { it.name.contains("Assets") }.configureEach { dependsOn(":prepareGeckoKernel") }
apply(from = rootProject.file("packaging/gecko-runtime.gradle.kts"))
