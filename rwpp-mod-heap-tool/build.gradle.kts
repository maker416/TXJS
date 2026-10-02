/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":rwpp-core-api"))
    testImplementation(kotlin("test-junit"))
}

application {
    mainClass.set("io.github.rwpp.tools.heap.ModHeapDesktopKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Xmx768m")
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
