/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.desktop

import io.github.rwpp.AppContext
import io.github.rwpp.desktop.impl.AppContextImpl
import io.github.rwpp.desktop.impl.DesktopNetworkModCache
import io.github.rwpp.game.mod.NetworkModDescriptor
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import kotlin.system.measureNanoTime
import kotlin.test.Test

/** 手动基准：只记录缓存恢复耗时，不把机器速度作为测试断言。 */
class NetworkCacheStartupBenchmark {
    @Test fun restore96MiB() {
        assumeTrue("Set RWJS_BENCHMARK_STARTUP=1 to run", System.getenv("RWJS_BENCHMARK_STARTUP") == "1")
        val scratch = File("build/tmp/network-cache-benchmark").apply { mkdirs() }.canonicalFile
        val root = Files.createTempDirectory(scratch.toPath(), "cache-").toFile()
        val context = object : AppContext by AppContextImpl() {
            override fun externalStoragePath(path: String) = File(root, path).path
        }
        try {
            val bytes = ByteArray(32 * 1024 * 1024) { (it * 31).toByte() }
            val cache = DesktopNetworkModCache(context)
            repeat(3) { cache.storeVerified(NetworkModDescriptor.fromBytes("benchmark-$it", bytes), bytes) }
            repeat(4) { iteration ->
                val restarted = DesktopNetworkModCache(context)
                val elapsed = measureNanoTime { restarted.prepareStartup() } / 1_000_000
                println("CACHE_STARTUP iteration=$iteration payloadMiB=96 elapsedMs=$elapsed")
                restarted.cleanupWorkingCopiesOnExit()
            }
        } finally {
            check(root.canonicalFile.toPath().startsWith(scratch.toPath()) && root.canonicalFile != scratch)
            root.deleteRecursively()
        }
    }
}
