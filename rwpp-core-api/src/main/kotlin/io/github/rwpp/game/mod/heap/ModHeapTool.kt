/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.game.mod.heap

import java.io.File

/**
 * 命令行入口。Gradle:
 * `./gradlew :rwpp-core-api:estimateModHeap -Pmod=<路径>`
 *
 * 路径可以是 `.rwmod`、含 ini 的目录，或单个 `.ini`。
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args.any { it == "-h" || it == "--help" }) {
        println("用法: gradlew :rwpp-core-api:estimateModHeap -Pmod=<模组路径>")
        println("路径可以是 .rwmod、含 ini 的目录，或单个 .ini")
        return
    }
    var failed = false
    for (path in args) {
        val file = File(path)
        if (!file.exists()) {
            println("找不到: $path")
            failed = true
            continue
        }
        try {
            println(ModHeapEstimator.estimate(file).formatReport())
        } catch (e: Exception) {
            System.err.println("分析失败: $path: ${e.message ?: e.javaClass.simpleName}")
            failed = true
        }
        println()
    }
    if (failed) {
        kotlin.system.exitProcess(1)
    }
}
