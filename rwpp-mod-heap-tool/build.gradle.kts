/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import java.util.zip.ZipFile

plugins {
    kotlin("jvm")
    application
}

// 与 rwpp-desktop 相同：android.jar 只取游戏库缺失的 android 兼容类，
// 其中的 java.* 占位类会遮蔽 JDK（File.toPath、Process.waitFor(long, TimeUnit) 等无法解析）。
val heapAndroidCompatJar = tasks.register<Jar>("heapAndroidCompatJar") {
    archiveFileName.set("rwjs-heap-android-compat.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/android-compat"))
    val gameLib = rootProject.file("lib/game-lib.jar")
    inputs.file(gameLib)
    val engineEntries = ZipFile(gameLib).use { jar -> jar.entries().asSequence().map { it.name }.toSet() }
    from(zipTree(rootProject.file("lib/android.jar"))) {
        include("android/**/*.class", "dalvik/**/*.class", "javax/microedition/**/*.class", "org/xmlpull/**/*.class")
        exclude { it.path in engineEntries }
    }
}

dependencies {
    implementation(project(":rwpp-core-api"))
    // 与桌面客户端使用同一份真实核心及 Slick/LWJGL；运行资源和 native 库来自选定游戏目录。
    implementation(files(rootProject.file("lib/game-lib.jar")))
    implementation(fileTree(rootProject.file("lib")) {
        include("*.jar")
        exclude("game-lib.jar", "android-game-lib.jar", "android-platform-lib.jar", "android.jar", "natives-*.jar")
    })
    implementation(files(heapAndroidCompatJar))
    testImplementation(kotlin("test-junit"))
    // ModReloadRetentionIntegrationTest 调用桌面客户端实际使用的缓存清理（DesktopModResourceCache）。
    testImplementation(project(":rwpp-desktop"))
}

application {
    mainClass.set("io.github.rwpp.tools.heap.ModHeapDesktopKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Xmx768m")
}

val heapAgentJar by tasks.registering(Jar::class) {
    archiveFileName.set("RWJS-HeapAgent.jar")
    from(tasks.compileJava.flatMap { it.destinationDirectory }) { include("io/github/rwpp/tools/heap/agent/**") }
    manifest.attributes["Premain-Class"] = "io.github.rwpp.tools.heap.agent.HeapAgent"
}

// Gradle run/tests 与分发 JAR 都使用同一 premain agent；父进程只启动独立测量 JVM。
tasks.processResources {
    dependsOn(heapAgentJar)
    from(heapAgentJar) { into("heap-agent") }
}

tasks.register<JavaExec>("measureModHeap") {
    group = "rwpp"
    description = "调用真实桌面核心测量模组堆，用法：-Pmod=<路径> [-PgameRoot=<游戏目录>]"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    doFirst {
        require(!findProperty("mod")?.toString().isNullOrBlank()) { "请使用 -Pmod=<模组路径> 指定待测模组" }
    }
    findProperty("mod")?.toString()?.takeIf { it.isNotBlank() }?.let { args("--analyze", it) }
    findProperty("gameRoot")?.toString()?.takeIf { it.isNotBlank() }?.let { args("--game-root", it) }
    jvmArgs("-Dfile.encoding=UTF-8")
}

tasks.test {
    systemProperty("rwpp.heap.workerClasspath", sourceSets.main.get().runtimeClasspath.asPath)
    // 重载残留复现的子进程入口在测试源码中。
    systemProperty("rwpp.heap.testWorkerClasspath", sourceSets.test.get().runtimeClasspath.asPath)
    // 显式开启真核心集成测试；常规 CI 无 GPU/游戏资源仍能运行对象图与界面测试。
    System.getProperty("rwpp.heap.integrationGameRoot")?.let {
        systemProperty("rwpp.heap.integrationGameRoot", it)
    }
    // -Drwpp.retention.sweep=false：关闭缓存清理，复现原版残留作对照。
    System.getProperty("rwpp.retention.sweep")?.let { systemProperty("rwpp.retention.sweep", it) }
}

tasks.register<Jar>("packageTool") {
    group = "distribution"
    description = "生成可独立运行的模组堆内存分析工具 JAR"
    archiveFileName.set("RWJS-ModHeapTool.jar")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("mod-heap-tool"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes["Main-Class"] = application.mainClass.get()
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
