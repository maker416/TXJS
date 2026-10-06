
/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipFile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

ksp {
    arg("outputDir", project.buildDir.absolutePath + "/generated")
    arg("lib", "game-lib")
    arg("libDir", "$rootDir/lib")
    arg("pathType", "Path")
}

// 原版桌面核心仍引用 Build.VERSION 等 Android 类型。只补充游戏库缺失的兼容类，
// 不把 SDK 中的 java.* / javax.* JDK 占位类或引擎自带的可运行实现放进桌面 classpath。
val desktopAndroidCompatJar = tasks.register<Jar>("desktopAndroidCompatJar") {
    archiveFileName.set("rwjs-android-compat.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/desktop-compat"))
    val gameLib = rootProject.file("lib/game-lib.jar")
    inputs.file(gameLib)
    val engineEntries = ZipFile(gameLib).use { jar ->
        jar.entries().asSequence().map { it.name }.toSet()
    }
    from(zipTree(rootProject.file("lib/android.jar"))) {
        include("android/**/*.class", "dalvik/**/*.class", "javax/microedition/**/*.class", "org/xmlpull/**/*.class")
        exclude { it.path in engineEntries }
    }
}

dependencies {
    // Binds Dispatchers.Main to Swing EDT; required for coroutines using Main on plain JVM desktop.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${findProperty("kotlin.coroutines.version")}")
    api(project(":rwpp-core"))
    implementation(compose.desktop.macos_arm64)
    implementation(compose.desktop.windows_x64)
    implementation(compose.desktop.linux_x64)
    implementation("org.slf4j:slf4j-simple:2.0.16")
    // 运行时切换全屏/窗口模式需要修改 Win32 窗口样式（SetWindowLongPtr），纯 JDK 无此能力
    implementation("net.java.dev.jna:jna:5.15.0")
    compileOnly(fileTree(
        "dir" to rootDir.absolutePath + "/lib",
        "include" to "*.jar",
        "exclude" to listOf("android-game-lib.jar", "android.jar")
    ))

    implementation(files(desktopAndroidCompatJar))
    runtimeOnly("party.iroiro.luajava:lua54-platform:4.0.2:natives-desktop")
    implementation("org.javassist:javassist:3.30.2-GA")
    val koinAnnotationsVersion = findProperty("koin.annotations.version") as String
    ksp("io.insert-koin:koin-ksp-compiler:$koinAnnotationsVersion")
    ksp(project(":rwpp-ksp"))
    testImplementation(kotlin("test-junit"))
    testImplementation(files(rootProject.file("lib/game-lib.jar")))
    testImplementation(files(rootProject.file("lib/lwjgl.jar")))
}

tasks.test {
    systemProperty("rwjs.test.libDir", rootProject.file("lib").absolutePath)
}

sourceSets.main {
    java.srcDirs("build/generated/ksp/main/kotlin")
    resources.srcDir(project.buildDir.absolutePath + "/generated")
    resources.include("config.toml")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

compose.desktop {
    application {
        mainClass = "io.github.rwpp.desktop.MainKt"
        buildTypes {
            release {
                proguard.isEnabled.set(false)
            }
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)

            // Gson 的 SQL 类型探测需要真实 JDK 模块，避免从游戏 Android 占位库加载 java.sql。
            modules("jdk.unsupported", "java.sql")

            packageName = "RWJS"
            packageVersion = rootProject.version.toString()
            mainClass = "io.github.rwpp.desktop.MainKt"
            vendor = "RWJS Contributors"
            description = "Multiplatform launcher for Rusted Warfare"
            copyright = "Copyright 2023-2025 RWPP contributors"
            licenseFile.set(rootProject.file("LICENSE"))

            jvmArgs += listOf(
                "-Djava.net.preferIPv4Stack=true",
             //   "-Xmx2000M",
                "-Dfile.encoding=UTF-8",
                "-XX:ErrorFile=\$ROOTDIR/logs/hs_err_pid%p.log",
                "-Djava.library.path=\$ROOTDIR",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
                "'-cp \$ROOTDIR/generated_lib/*;\$ROOTDIR/extension/*;\$ROOTDIR/app/*;\$ROOTDIR/libs/*'"
            )

            windows {
                iconFile.set(project.file("logo.ico"))
            }

            linux {
                iconFile.set(project.file("logo.png"))
            }

            args += listOf("-native")
        }
    }
}

// Compose 为 Windows jpackage 默认准备 WiX；app-image 不需要它，安装器完全交给 Inno Setup。
rootProject.tasks.matching { it.name == "unzipWix" }.configureEach {
    enabled = false
}

tasks.register<Exec>("packageInnoDistribution") {
    group = "distribution"
    description = "Build the standalone RWJS Windows installer with Inno Setup"
    dependsOn("createReleaseDistributable")
    workingDir(rootProject.rootDir)
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        rootProject.file("packaging/inno/package.ps1").absolutePath,
        "-Version", rootProject.version.toString()
    )
    doFirst {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            throw GradleException("RWJS Inno Setup installer must be built on Windows.")
        }
    }
}

